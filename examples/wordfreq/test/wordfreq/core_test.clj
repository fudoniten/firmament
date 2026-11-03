(ns wordfreq.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [wordfreq.core :as core]))

(deftest words
  (is (= ["it's" "a" "hash" "42"] (core/words "It's a HASH -- 42!"))))

(deftest frequencies-of
  (testing "stopwords are dropped"
    (is (= {"hash" 2 "tree" 1} (core/frequencies-of "the hash, a tree, the hash"))))
  (testing "unless :all?"
    (is (= {"the" 2 "hash" 2 "a" 1 "tree" 1}
           (core/frequencies-of "the hash, a tree, the hash" :all? true)))))

(deftest top
  (is (= [["b" 3] ["a" 1] ["c" 1]]
         (core/top 3 {"c" 1 "b" 3 "a" 1 "d" 1}))))
