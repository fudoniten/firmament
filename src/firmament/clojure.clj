(ns firmament.clojure
  "The Clojure layer (docs/spec/clojure.md): importing a deps.edn project
  into a :clj/manifest, :clj/analysis meta-objects, and projecting a
  manifest back out as a project directory."
  (:require [clojure.edn]
            [clojure.string :as str]
            [firmament.edn :as edn]
            [firmament.node :as node]
            [firmament.storage :as storage]
            [firmament.tree :as tree])
  (:import (java.io File PushbackReader StringReader)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files LinkOption OpenOption Path Paths)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(defn- fail [msg data]
  (throw (ex-info msg (assoc data ::error :clojure))))

;; -------------------------------------------------------------- platforms

(def source-types
  "Source file extensions, with the platforms each is read for and the
  entry keys its source and analysis go under."
  {"clj" {:platforms [:clj] :source :source/clj :analysis :analysis/clj}
   "cljs" {:platforms [:cljs] :source :source/cljs :analysis :analysis/cljs}
   "cljc" {:platforms [:clj :cljs] :source :source/cljc :analysis :analysis/cljc}})

(def ^:private source-key->ext
  (into {} (map (fn [[ext {:keys [source]}]] [source ext])) source-types))

;; ------------------------------------------------------- reading ns forms

(defn- choose
  "The branch of reader conditional `rc` for `platform`, as a sequence of
  zero forms (no branch matches) or one."
  [rc platform]
  (->> (partition 2 (:form rc))
       (some (fn [[feature form]] (when (#{platform :default} feature) [form])))))

(declare for-platform)

(defn- expand-children [xs platform]
  (mapcat (fn [x]
            (if (reader-conditional? x)
              (let [chosen (map #(for-platform % platform) (choose x platform))]
                (if (:splicing? x) (mapcat identity chosen) chosen))
              [(for-platform x platform)]))
          xs))

(defn- for-platform
  "`form` with its reader conditionals resolved for `platform`."
  [form platform]
  (cond
    (reader-conditional? form) (first (expand-children [form] platform))
    (seq? form) (apply list (expand-children form platform))
    (vector? form) (vec (expand-children form platform))
    (map? form) (into {} (map (fn [[k v]] [(for-platform k platform) (for-platform v platform)])) form)
    :else form))

(defn read-ns-form
  "The `ns` form that starts `source`, with reader conditionals resolved
  for `platform` (:clj or :cljs), or nil if the first form isn't one.
  Reading never evaluates anything."
  [^String source platform]
  (let [form (binding [*read-eval* false
                       *default-data-reader-fn* tagged-literal
                       *ns* (create-ns 'firmament.clojure.reading)]
               (read {:read-cond :preserve :eof nil}
                     (PushbackReader. (StringReader. source))))
        form (for-platform form platform)]
    (when (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
      form)))

(defn- libspec-names
  "The namespaces one libspec of a :require or :use clause names."
  [spec]
  (cond
    (symbol? spec) [(str spec)]
    (string? spec) [spec]
    (sequential? spec)
    (let [[head & more] spec]
      (if (and (seq more) (not (keyword? (first more))))
        ;; A prefix list: (prefix lib1 [lib2 :as x] ...)
        (for [item more, lib (libspec-names item)] (str head "." lib))
        (libspec-names head)))
    :else []))

(defn ns-requires
  "The namespaces an `ns` form requires, sorted: everything named in its
  :require, :use, :require-macros and :use-macros clauses."
  [ns-form]
  (->> (drop 2 ns-form)
       (filter #(and (seq? %) (#{:require :use :require-macros :use-macros} (first %))))
       (mapcat rest)
       (mapcat libspec-names)
       distinct
       sort
       vec))

;; ---------------------------------------------------------- node types

(def analyzer
  "Names the code that produces :clj/analysis objects. Bump :version when
  the analysis it gives for the same source changes."
  {:type :clj/analyzer :name "firmament.clojure" :version 1})

(defmethod node/validate :clj/manifest [m]
  (when-not (and (every? #(edn/link? (get m %)) [:namespaces :resources :jars])
                 (every? #{:type :namespaces :resources :jars :tags} (keys m))
                 (or (not (contains? m :tags)) (and (map? (:tags m)) (seq (:tags m)))))
    (fail "invalid :clj/manifest: needs :namespaces, :resources and :jars links, and optional :tags"
          {:node m}))
  m)

(defmethod node/validate :clj/analysis [{:keys [requires] :as a}]
  (when-not (and (= #{:type :source :analyzer :ns :requires} (set (keys a)))
                 (edn/link? (:source a))
                 (edn/link? (:analyzer a))
                 (string? (:ns a))
                 (map? requires)
                 (every? #{:clj :cljs} (keys requires))
                 (every? #(and (vector? %) (every? string? %)) (vals requires)))
    (fail "invalid :clj/analysis" {:node a}))
  a)

;; ----------------------------------------------------------------- import

(def ^:private ^"[Ljava.nio.file.LinkOption;" follow (make-array LinkOption 0))

(defn- ->path ^Path [p]
  (if (instance? Path p) p (Paths/get (str p) (make-array String 0))))

(defn- extension [^Path p]
  (let [n (str (.getFileName p))
        i (str/last-index-of n ".")]
    (when (and i (pos? i)) (subs n (inc i)))))

(defn- files-under
  "Every regular file below `dir`, as [path relative-names] pairs, sorted."
  [^Path dir]
  (with-open [walk (Files/walk dir (make-array java.nio.file.FileVisitOption 0))]
    (->> (iterator-seq (.iterator walk))
         (filter #(Files/isRegularFile % follow))
         (map (fn [^Path p] [p (mapv str (.relativize dir p))]))
         (sort-by second)
         vec)))

(defn- read-deps [^Path dir]
  (let [f (.resolve dir "deps.edn")]
    (if (Files/exists f follow)
      (clojure.edn/read-string (String. (Files/readAllBytes f) StandardCharsets/UTF_8))
      {})))

(defn- source-file
  "Analyze one file: {:ns name :key source-key :requires {...}} if it is a
  namespace source, else nil."
  [^Path p ^bytes data]
  (when-let [{:keys [platforms source]} (source-types (extension p))]
    (let [text (String. data StandardCharsets/UTF_8)
          forms (try (into {} (map (fn [pl] [pl (read-ns-form text pl)])) platforms)
                     (catch Exception e
                       (fail (str "can't read the ns form of " p ": " (ex-message e)) {:path (str p)})))
          names (set (keep (fn [[_ form]] (some-> form second str)) forms))]
      (when (seq names)
        (when (< 1 (count names))
          (fail (str p " declares different namespaces per platform: " (sort names)) {:path (str p)}))
        {:ns (first names)
         :key source
         :requires (into {} (map (fn [[pl form]] [pl (if form (ns-requires form) [])])) forms)}))))

(defn- put-analysis! [s analyzer-link {:keys [ns requires]} source-link]
  (edn/link (storage/put-value! s {:type :clj/analysis
                                   :source source-link
                                   :analyzer analyzer-link
                                   :ns ns
                                   :requires requires})))

(defn- add-unique [m k v what]
  (when (contains? m k)
    (fail (str "two " what " for " (pr-str k)) {:key k}))
  (assoc m k v))

(defn- jar-paths
  "The jars to import: those on `classpath` (a path-separated string, as
  `clojure -Spath` prints) and :local/root jar deps in deps.edn. Classpath
  directories must be the project's own paths."
  [^Path dir deps paths classpath]
  (let [own (set (map #(.normalize (.toAbsolutePath (.resolve dir ^String %))) paths))
        from-cp (for [^String e (when classpath (str/split classpath (re-pattern File/pathSeparator)))
                      :when (seq e)
                      :let [p (.normalize (.toAbsolutePath (.resolve dir e)))]]
                  (cond
                    (str/ends-with? e ".jar") p
                    (own p) nil
                    :else (fail (str "classpath directory outside the project's :paths: " e
                                     "; only jars can come from the classpath for now")
                                {:entry e})))
        local (for [[_ {:keys [local/root]}] (:deps deps)
                    :when (and root (str/ends-with? root ".jar"))]
                (.normalize (.toAbsolutePath (.resolve dir ^String root))))]
    (distinct (remove nil? (concat from-cp local)))))

(defn- add-source
  "Add a source to `namespaces`, a map of namespace paths to entries, with
  its analysis beside it."
  [namespaces s analyzer-link {:keys [ns key] :as src} link ^Path p]
  (let [path (str/split ns #"\.")
        entry (get namespaces path {})]
    (when (contains? entry key)
      (fail (str "two " key " sources for namespace " ns) {:ns ns :path (str p)}))
    (assoc namespaces path
           (assoc entry
                  key link
                  (get-in source-types [(source-key->ext key) :analysis])
                  (put-analysis! s analyzer-link src link)))))

(defn- add-path-file
  "Store one file found under the project's :paths, as a source or a
  resource."
  [s analyzer-link acc [^Path p names]]
  (let [data (Files/readAllBytes p)
        link (storage/put-file! s data)]
    (if-let [src (source-file p data)]
      (update acc :namespaces add-source s analyzer-link src link p)
      (update acc :resources add-unique names {:link link} "resources at"))))

(defn- add-jar [s jars ^Path p]
  (let [link (storage/put-file! s (Files/readAllBytes p))
        name (str (.getFileName p))]
    (if (= (get jars [name]) {:link link})
      jars
      (add-unique jars [name] {:link link} "jars named"))))

(defn import-project!
  "Import the deps.edn project in `dir` into store `s` and return the link
  to its :clj/manifest. Every file under the project's :paths is either a
  namespace source (a .clj, .cljs or .cljc file starting with an ns form),
  filed under the namespace it declares, or a resource. Jars come from
  `:classpath` (`clojure -Spath` output) and :local/root jar deps."
  [s dir & {:keys [classpath]}]
  (let [dir (->path dir)
        deps (read-deps dir)
        paths (get deps :paths ["src"])
        _ (when-not (every? string? paths)
            (fail "only string :paths are supported" {:paths paths}))
        analyzer-link (edn/link (storage/put-value! s analyzer))
        {:keys [namespaces resources]}
        (reduce (partial add-path-file s analyzer-link)
                {:namespaces {} :resources {}}
                (for [path paths
                      :let [root (.resolve dir ^String path)]
                      :when (Files/isDirectory root follow)
                      file (files-under root)]
                  file))
        jars (reduce (partial add-jar s) {} (jar-paths dir deps paths classpath))]
    (edn/link (storage/put-value! s {:type :clj/manifest
                                     :namespaces (tree/put-paths! s namespaces)
                                     :resources (tree/put-paths! s resources)
                                     :jars (tree/put-paths! s jars)}))))

;; ------------------------------------------------------------- namespaces

(defn namespaces
  "Every namespace in the namespace tree at `link`, sorted, as [name entry]
  pairs; an entry holds the namespace's :source/* and :analysis/* links."
  [r link]
  (letfn [(walk [prefix link]
            (mapcat (fn [[segment entry]]
                      (let [ns (if prefix (str prefix "." segment) segment)
                            own (dissoc entry :children :tags)]
                        (concat (when (seq own) [[ns own]])
                                (when-let [c (:children entry)] (walk ns c)))))
                    (:entries (tree/get-tree r link))))]
    (sort-by first (walk nil link))))

(defn manifest
  "The :clj/manifest at `link`."
  [r link]
  (node/load-as r link :clj/manifest))

(defmethod node/listing :clj/manifest [r m [ns & more]]
  (when (seq more) (fail "a manifest listing takes at most one namespace" {}))
  (let [rows (for [[name entry] (namespaces r (:namespaces m))
                   :when (or (nil? ns) (= ns name))
                   [k link] (sort-by key entry)]
               {:name name :key k :link link})]
    (if ns
      (or (seq rows) (fail (str "no namespace " ns) {:ns ns}))
      (concat [{:name "resources" :key :resources :link (:resources m)}
               {:name "jars" :key :jars :link (:jars m)}]
              rows))))

;; ------------------------------------------------------------- projection

(defn- write-file! [r ^Path target link]
  (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
  (with-open [out (Files/newOutputStream target (make-array OpenOption 0))]
    (node/write-file! r link out)))

(defn source-path
  "Where a namespace's source goes below a source root, as Clojure looks for
  it: my.app-server with \"clj\" is my/app_server.clj."
  [ns ext]
  (str (str/replace (namespace-munge ns) "." "/") "." ext))

(defn- projected-deps
  "The deps.edn text for a projection with these jars."
  ^String [jar-names]
  (binding [*print-namespace-maps* false]
    (pr-str {:paths ["src" "resources"]
             :deps (into (sorted-map)
                         (map (fn [n] [(symbol "jars" (str/replace n #"\.jar$" ""))
                                       {:local/root (str "jars/" n)}]))
                         jar-names)})))

(defmethod node/export! :clj/manifest [r m dir]
  (let [dir (tree/empty-dir! dir)
        jar-names (map key (:entries (tree/get-tree r (:jars m))))]
    (tree/export! r (:resources m) (.resolve dir "resources"))
    (tree/export! r (:jars m) (.resolve dir "jars"))
    (reduce (fn [written [ns entry]]
              (reduce (fn [written [k link]]
                        (if-let [ext (source-key->ext k)]
                          (let [rel (source-path ns ext)]
                            (when (written rel)
                              (fail (str "namespaces " (written rel) " and " ns " both project to " rel)
                                    {:path rel}))
                            (write-file! r (.resolve dir (str "src/" rel)) link)
                            (assoc written rel ns))
                          written))
                      written entry))
            {} (namespaces r (:namespaces m)))
    (Files/write (.resolve dir "deps.edn")
                 (.getBytes (projected-deps jar-names) StandardCharsets/UTF_8)
                 ^"[Ljava.nio.file.OpenOption;" (make-array OpenOption 0))
    dir))
