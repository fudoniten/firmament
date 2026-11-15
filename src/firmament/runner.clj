(ns firmament.runner
  "Running a program by hash (docs/spec/clojure.md, Running): find its entry
  point, prefetch what it needs using the analyses, then load it and call
  its -main."
  (:require [firmament.clojure :as clj]
            [firmament.error :as error]
            [firmament.loader :as loader]
            [firmament.node :as node]
            [firmament.storage :as storage]
            [firmament.tree :as tree]))

(set! *warn-on-reflection* true)

(def ^:private fail (partial error/fail :runner))

(defn main-ns
  "The namespace manifest `m` runs, from its :clj/main tag."
  [m]
  (or (get-in m [:tags :clj/main])
      (fail "the manifest has no :clj/main tag naming a namespace to run" {})))

(defn closure
  "The namespaces of manifest `m` that loading `ns` on the JVM needs: `ns`
  and every namespace it requires, transitively, that the manifest has.
  Requires the manifest doesn't have (Clojure's own, or a jar's) are left
  to the classloader. Reads analyses, never source. Returns
  {namespace-name entry}."
  [r m ns]
  (let [entries (into {} (clj/namespace-entries r m))
        requires (fn [name]
                   (let [entry (entries name)
                         [k _] (or (clj/jvm-source entry)
                                   (fail (str "no JVM source for namespace " name) {:ns name}))]
                     (filter entries (get-in (node/load r (get entry (clj/analysis-key k)))
                                             [:requires :clj]))))]
    (if (entries (str ns))
      (into {} (map (juxt identity entries)) (storage/walk requires [(str ns)]))
      {})))

(defn prefetch!
  "Fetch everything running manifest `m` (whose link is `root`) needs, so
  loading never waits on a remote store for the namespaces its main
  namespace requires in `ns` forms: the trees, the sources and analyses of
  that closure, every resource and every jar. A namespace loaded some other
  way (`requiring-resolve`, a `require` in a function) is fetched when it
  loads. Through a storage/cache this copies everything into the local
  store. Returns the names of the namespaces in the closure, sorted."
  ([r root] (prefetch! r root (clj/manifest r root)))
  ([r _root m]
   (let [nss (closure r m (main-ns m))]
     (doseq [link (concat (map (comp second clj/jvm-source) (vals nss))
                          [(:resources m) (:jars m)])]
       (storage/fetch-all! r link))
     (sort (keys nss)))))

(defn host-mismatches
  "Warnings for the ways this JVM differs from the host manifest `m` was
  imported for. Clojure always comes from the host, never from the
  manifest's jars, so a Clojure jar in :jars whose version isn't the
  running one means the program runs on a Clojure it wasn't built
  against."
  [r m]
  (let [running (clojure-version)]
    (for [[name _] (:entries (tree/get-tree r (:jars m)))
          :let [[_ v] (re-matches #"clojure-(\d+\.\d+\.\d+.*)\.jar" name)]
          :when (and v (not= v running))]
      (str "the program was imported with Clojure " v " (" name "), but runs on the host's "
           running))))

(defn run-program!
  "Run the manifest at `root`: prefetch, then load its :clj/main namespace
  and call its -main. With `:params` (a link to a structured object), -main
  gets that value as its only argument; otherwise it gets `:args`, a
  sequence of strings, as from a command line. Returns what -main returns.
  `:jar-dir` goes to loader/class-loader. Host mismatches (see
  host-mismatches) are printed to *err* as warnings."
  [r root & {:keys [params args jar-dir]}]
  (let [m (clj/manifest r root)
        ns (symbol (main-ns m))]
    (prefetch! r root m)
    (doseq [w (host-mismatches r m)]
      (binding [*out* *err*] (println (str "firmament: warning: " w))))
    (let [params (some->> params (storage/fetch-value r))]
      (loader/with-root [r root :jar-dir jar-dir]
        (require ns)
        (let [main (or (ns-resolve ns '-main)
                       (fail (str ns " has no -main") {:ns (str ns)}))]
          (if (some? params) (main params) (apply main args)))))))
