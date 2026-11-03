(ns wordfreq.test-runner
  "Runs every test namespace; exits 1 if any test fails, so it works both
  as clojure -M:test and as the main namespace of a Firmament program."
  (:require [clojure.test :as t]
            wordfreq.core-test
            wordfreq.report-test))

(def test-namespaces '[wordfreq.core-test wordfreq.report-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply t/run-tests test-namespaces)]
    (when (pos? (+ fail error))
      (System/exit 1))))
