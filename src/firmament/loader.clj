(ns firmament.loader
  "Loading Clojure programs straight from the store (docs/spec/clojure.md,
  Loading): a classloader per manifest that serves sources, resources and
  jars, and `with-root` to run code against one."
  (:require [clojure.string :as str]
            [firmament.clojure :as clj]
            [firmament.edn :as edn]
            [firmament.error :as error]
            [firmament.fs :as fs]
            [firmament.node :as node]
            [firmament.tree :as tree])
  (:import (clojure.lang Compiler LineNumberingPushbackReader)
           (java.io ByteArrayInputStream InputStreamReader)
           (java.net URL URLClassLoader URLConnection URLStreamHandler)
           (java.nio.charset StandardCharsets)
           (java.nio.file Path)
           (java.util Collections)))

(set! *warn-on-reflection* true)

(def ^:dynamic *ns-root*
  "The link to the manifest code is being loaded from, while inside
  `with-root`; nil outside."
  nil)

(def ^:private fail (partial error/fail :loader))

;; ------------------------------------------------------------------ URLs

(defn- url-handler
  "The handler for a loader's firmament: URLs, which look like
  firmament:sha256:<manifest>!/<path>. Opening one reads the file that
  `lookup` gives for <path> from `r`."
  ^URLStreamHandler [r lookup]
  (proxy [URLStreamHandler] []
    (openConnection [^URL u]
      (let [path (subs (.getPath u) (+ 2 (str/index-of (.getPath u) "!/")))]
        (proxy [URLConnection] [u]
          (connect [])
          (getInputStream []
            (if-let [link (lookup path)]
              (ByteArrayInputStream. (node/read-file r link))
              (throw (java.io.FileNotFoundException. (str u))))))))))

(defn- file-url ^URL [^URLStreamHandler handler root path]
  (URL. nil (str "firmament:" (edn/link-ref root) "!/" path) handler))

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

(defn- jar-file!
  "The jar at `link` as a file in `dir`, named by its hash, writing it
  first if it isn't there yet."
  ^Path [r dir link]
  (let [target (fs/path dir (str (edn/link-hex link) ".jar"))]
    (when-not (fs/exists? target)
      (fs/write-atomically! dir target (node/read-file r link)))
    target))

(def ^:private shared-jar-dir
  "A directory for jars when the caller gives none, shared by every loader
  in this process (jars are named by hash, so sharing is safe) and deleted
  when the JVM exits."
  (delay
    (let [dir (fs/temp-dir "firmament-jars")]
      (.addShutdownHook (Runtime/getRuntime) (Thread. #(fs/delete-tree! dir)))
      dir)))

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
  ;; Without a jar-dir, jars go to a temporary directory shared by the
  ;; process and removed when it exits.
  (let [m (clj/manifest r root)
        sources (source-index r m)
        jar-dir (fs/path (or jar-dir @shared-jar-dir))
        ^ClassLoader parent (or parent (.getContextClassLoader (Thread/currentThread)))
        lookup (fn [^String name] (or (first (sources name)) (resource-link r m name)))
        handler (url-handler r lookup)
        own (fn [^String name] (when (lookup name) (file-url handler root name)))]
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
  (let [m (clj/manifest r *ns-root*)
        entry (tree/lookup r (:namespaces m) (str/split (str ns) #"\."))
        [k link] (clj/jvm-source entry)]
    (when-not link
      (fail (str "no JVM source for namespace " ns " in " (edn/link-ref *ns-root*)) {:ns ns}))
    (let [path (clj/source-path (str ns) (clj/source-key->ext k))]
      (with-open [rdr (LineNumberingPushbackReader.
                       (InputStreamReader. (ByteArrayInputStream. (node/read-file r link))
                                           StandardCharsets/UTF_8))]
        (Compiler/load rdr path (last (str/split path #"/"))))
      (dosync (commute @#'clojure.core/*loaded-libs* conj ns))
      ns)))
