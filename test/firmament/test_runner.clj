(ns firmament.test-runner
  "Runs every *_test.clj namespace under test/. Exits non-zero on failure."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]))

(defn- test-namespaces [dir]
  (->> (file-seq (io/file dir))
       (map #(str (.relativize (.toPath (io/file dir)) (.toPath ^java.io.File %))))
       (filter #(str/ends-with? % "_test.clj"))
       (map #(-> %
                 (str/replace #"\.clj$" "")
                 (str/replace java.io.File/separator ".")
                 (str/replace "_" "-")
                 symbol))
       sort))

(defn -main [& _]
  (let [nss (test-namespaces "test")]
    (apply require nss)
    (let [{:keys [fail error]} (apply test/run-tests nss)]
      (shutdown-agents)
      (System/exit (if (zero? (+ fail error)) 0 1)))))
