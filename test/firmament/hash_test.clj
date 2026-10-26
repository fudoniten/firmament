(ns firmament.hash-test
  (:require [clojure.test :refer [deftest is]]
            [firmament.hash :as hash]))

;; Computed independently, e.g. { printf 'blob\n'; cat f; } | sha256sum.

(deftest blob-hash
  (is (= "sha256:bc103b4a84971ef6459b294a2b98568a2bfb72cded09d4acd1e16366a401f95b"
         (hash/blob-hash (byte-array 0))))
  (is (= "sha256:1be8615f7fbda85e1ef029f02573bdfefe09fb01af39e6ce684104a45a360ae8"
         (hash/blob-hash (.getBytes "hello\n" "UTF-8")))))

(deftest edn-hash
  (is (= "sha256:63fd2b150ed3e191a9a116641e44427e21d75379991e421ef7ddaec30fed59fd"
         (hash/edn-hash {:a 1}))))
