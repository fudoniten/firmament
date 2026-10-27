(ns firmament.loader
  "Loading Clojure programs straight from the store (docs/spec/clojure.md,
  Loading): a classloader per manifest that serves sources, resources and
  jars, and `with-root` to run code against one."
  (:require [clojure.string :as str]
            [firmament.clojure :as clj]
            [firmament.edn :as edn]
            [firmament.node :as node]
            [firmament.tree :as tree])
  (:import (clojure.lang Compiler LineNumberingPushbackReader)
           (java.io ByteArrayInputStream InputStreamReader)
           (java.net URL URLClassLoader URLConnection URLStreamHandler)
           (java.nio.charset StandardCharsets)
           (java.nio.file CopyOption Files OpenOption Path Paths StandardCopyOption)
           (java.nio.file.attribute FileAttribute)
           (java.util Collections)))

(set! *warn-on-reflection* true)

(def ^:dynamic *ns-root*
  "The link to the manifest code is being loaded from, while inside
  `with-root`; nil outside."
  nil)

(defn- fail [msg data]
  (throw (ex-info msg (assoc data ::error :loader))))

;; ------------------------------------------------------------------ URLs

(defn- file-url
  "A firmament: URL whose stream is the file at `link`, read from `r` when
  opened. `path` is only there to make the URL readable in stack traces."
  ^URL [r root link path]
  (let [handler (proxy [URLStreamHandler] []
                  (openConnection [^URL u]
                    (proxy [URLConnection] [u]
                      (connect [])
                      (getInputStream []
                        (ByteArrayInputStream. (node/read-file r link))))))]
    (URL. nil (str "firmament:" (edn/link-ref root) "!/" path) ^URLStreamHandler handler)))

;; --------------------------------------------------------------- indexes

(defn- source-index
  "Every classpath path the manifest's sources answer to, such as
  \"my/app_server.clj\", mapped to [link namespace]. Reads only trees.
  Throws if two namespaces munge to the same path (my.a-b and my.a_b)."
  [r m]
  (reduce (fn [index [path ns link]]
            (when-let [[_ other] (index path)]
              (fail (str "namespaces " other " and " ns " both load from " path) {:path path}))
            (assoc index path [link ns]))
          {}
          (for [[ns entry] (clj/namespaces r (:namespaces m))
                [k link] entry
                :let [ext (clj/source-key->ext k)]
                :when ext]
            [(clj/source-path ns ext) ns link])))

(defn- resource-link
  "The file link for resource `path` (a/b/c.edn), or nil."
  [r m ^String path]
  (let [names (str/split path #"/")]
    (when (and (seq names) (every? tree/valid-name? names))
      (:link (tree/lookup r (:resources m) names)))))

;; ------------------------------------------------------------------ jars

(defn- ->path ^Path [p]
  (if (instance? Path p) p (Paths/get (str p) (make-array String 0))))

(defn- jar-file!
  "The jar at `link` as a file in `dir`, named by its hash, writing it
  first if it isn't there yet."
  ^Path [r ^Path dir link]
  (let [target (.resolve dir (str (subs (edn/link-ref link) (count "sha256:")) ".jar"))]
    (when-not (Files/exists target (make-array java.nio.file.LinkOption 0))
      (Files/createDirectories dir (make-array FileAttribute 0))
      (let [tmp (Files/createTempFile dir "jar" ".tmp" (make-array FileAttribute 0))]
        (try
          (Files/write tmp ^bytes (node/read-file r link) ^"[Ljava.nio.file.OpenOption;" (make-array OpenOption 0))
          (Files/move tmp target (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                                          StandardCopyOption/REPLACE_EXISTING]))
          (finally (Files/deleteIfExists tmp)))))
    target))

(defn- jar-urls ^"[Ljava.net.URL;" [r m ^Path dir]
  (into-array URL (for [[_ {:keys [link]}] (sort-by key (:entries (tree/get-tree r (:jars m))))]
                    (.toURL (.toUri (jar-file! r dir link))))))

;; ----------------------------------------------------------- classloader

(defn class-loader
  "A classloader for the manifest at `root` in `r`. Resource lookups check
  the manifest first: a namespace's source at the path Clojure asks for
  (my/app_server.clj), then the resources tree. Everything else, and every
  class, goes to `parent` first and then the manifest's jars, which are
  written to `jar-dir` (named by hash, so a directory can be shared)."
  ^URLClassLoader [r root & {:keys [parent jar-dir]}]
  (let [m (clj/manifest r root)
        sources (source-index r m)
        jar-dir (->path (or jar-dir (Files/createTempDirectory "firmament-jars" (make-array FileAttribute 0))))
        ^ClassLoader parent (or parent (.getContextClassLoader (Thread/currentThread)))
        own (fn [^String name]
              (when-let [link (or (first (sources name)) (resource-link r m name))]
                (file-url r root link name)))]
    (proxy [URLClassLoader] [(jar-urls r m jar-dir) parent]
      (getResource [name]
        (or (own name)
            (.getResource parent name)
            (.findResource ^URLClassLoader this name)))
      (getResources [name]
        (Collections/enumeration
         (concat (some-> (own name) vector)
                 (Collections/list (.getResources parent name))
                 (Collections/list (.findResources ^URLClassLoader this name))))))))

;; --------------------------------------------------------------- loading

(defn call-with-root
  "Call `f` with code loading from the manifest at `root`: `*ns-root*` is
  bound to `root`, and `loader` (a `class-loader` for it) is both
  Clojure's base loader and the thread's context classloader, so
  `require`, `load` and `io/resource` see the manifest."
  [root ^ClassLoader loader f]
  (let [thread (Thread/currentThread)
        previous (.getContextClassLoader thread)]
    (.setContextClassLoader thread loader)
    (try
      (with-bindings {Compiler/LOADER loader #'*ns-root* root}
        (f))
      (finally (.setContextClassLoader thread previous)))))

(defmacro with-root
  "Evaluate `body` loading code from the manifest at link `root` in `r`.
  `opts` go to `class-loader`."
  [[r root & opts] & body]
  `(let [r# ~r root# ~root]
     (call-with-root root# (class-loader r# root# ~@opts) (fn [] ~@body))))

(defn load-ns!
  "Load namespace `ns` (a symbol) from the manifest `*ns-root*` names, by
  namespace rather than by path, and mark it loaded as `require` would. On
  the JVM, :source/clj wins over :source/cljc. Other namespaces it
  requires load through the classloader as usual. Call inside `with-root`."
  [r ns]
  (when-not *ns-root* (fail "load-ns! needs a root; call it inside with-root" {:ns ns}))
  (let [entry (get (into {} (clj/namespaces r (:namespaces (clj/manifest r *ns-root*)))) (str ns))
        [k link] (some #(when-let [link (get entry %)] [% link]) [:source/clj :source/cljc])]
    (when-not link
      (fail (str "no JVM source for namespace " ns " in " (edn/link-ref *ns-root*)) {:ns ns}))
    (let [path (clj/source-path (str ns) (clj/source-key->ext k))]
      (with-open [rdr (LineNumberingPushbackReader.
                       (InputStreamReader. (ByteArrayInputStream. (node/read-file r link))
                                           StandardCharsets/UTF_8))]
        (Compiler/load rdr path (last (str/split path #"/"))))
      (dosync (commute @#'clojure.core/*loaded-libs* conj ns))
      ns)))
