(ns wordfreq.report-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [wordfreq.main :as main]
            [wordfreq.report :as report]))

(deftest text
  (is (= "12  hash\n 3  tree" (report/text [["hash" 12] ["tree" 3]]))))

(deftest as-json
  (is (= [{"word" "hash" "count" 2}]
         (json/read-str (report/as-json [["hash" 2]])))))

(deftest sample
  (is (= "6  hash" (main/run ["--top" "1"]))))
