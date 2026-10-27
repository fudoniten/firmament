(ns firmament.runner
  "Running a program by hash (docs/spec/clojure.md, Running): find its entry
  point, prefetch what it needs using the analyses, then load it and call
  its -main."
  (:require [firmament.clojure :as clj]
            [firmament.edn :as edn]
            [firmament.loader :as loader]
            [firmament.node :as node]
            [firmament.storage :as storage]))

(set! *warn-on-reflection* true)

(defn- fail [msg data]
  (throw (ex-info msg (assoc data ::error :runner))))

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
  (let [entries (into {} (clj/namespace-entries r m))]
    (loop [todo [(str ns)] found {}]
      (if-let [[name & more] (seq todo)]
        (let [entry (get entries name)]
          (if (or (nil? entry) (contains? found name))
            (recur more found)
            (let [[k _] (or (clj/jvm-source entry)
                            (fail (str "no JVM source for namespace " name) {:ns name}))
                  analysis (node/load r (get entry (clj/analysis-key k)))]
              (recur (concat more (get-in analysis [:requires :clj])) (assoc found name entry)))))
        found))))

(defn prefetch!
  "Fetch everything running the manifest at `root` needs, so loading never
  waits on a remote store: the trees, the sources and analyses of the main
  namespace's closure, every resource and every jar. Through a
  storage/cache this copies them into the local store. Returns the names
  of the namespaces in the closure, sorted."
  [r root]
  (let [m (clj/manifest r root)
        nss (closure r m (main-ns m))]
    (doseq [link (concat (map (comp second clj/jvm-source) (vals nss))
                         [(:resources m) (:jars m)])]
      (storage/fetch-all! r (edn/link-ref link)))
    (sort (keys nss))))

(defn run-program!
  "Run the manifest at `root`: prefetch, then load its :clj/main namespace
  and call its -main. With `:params` (a link to a structured object), -main
  gets that value as its only argument; otherwise it gets `:args`, a
  sequence of strings, as from a command line. Returns what -main returns.
  `:jar-dir` goes to loader/class-loader."
  [r root & {:keys [params args jar-dir]}]
  (prefetch! r root)
  (let [ns (symbol (main-ns (clj/manifest r root)))
        params (some->> params edn/link-ref (storage/fetch-value r))]
    (loader/with-root [r root :jar-dir jar-dir]
      (require ns)
      (let [main (or (ns-resolve ns '-main)
                     (fail (str ns " has no -main") {:ns (str ns)}))]
        (if (some? params) (main params) (apply main args))))))
