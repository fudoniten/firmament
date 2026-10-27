(ns firmament.node-test
  (:require [clojure.test :refer [deftest is testing]]
            [firmament.edn :as edn]
            [firmament.hash :as hash]
            [firmament.node :as node]
            [firmament.storage :as storage]
            [firmament.tree :as tree])
  (:import (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(defn- utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

(deftest node-types
  (is (= :blob (node/node-type (byte-array 0))))
  (is (= :chunked (node/node-type {:type :chunked})))
  (is (= :tree (node/node-type tree/empty-tree)))
  (is (nil? (node/node-type {:no "type"}))))

(deftest chunked-validation
  (let [l (hash/blob-link (utf8 "x"))
        good {:type :chunked :level 0 :size 3 :chunks [{:link l :size 1} {:link l :size 2}]}]
    (is (= good (node/validate good)))
    (doseq [[why bad] {"sizes don't add up" (assoc good :size 4)
                       "no chunks" (assoc good :chunks [] :size 0)
                       "negative level" (assoc good :level -1)
                       "extra key" (assoc good :extra 1)
                       "chunk without a link" (assoc-in good [:chunks 0 :link] "sha256:00")}]
      (is (thrown? clojure.lang.ExceptionInfo (node/validate bad)) why))))

(deftest load-validates
  (let [s (storage/memory-store)
        bad (edn/link (storage/put-value! s {:type :chunked :level 0 :size 1 :chunks []}))
        other (edn/link (storage/put-value! s {:type :note :text "hi"}))]
    (is (thrown? clojure.lang.ExceptionInfo (node/load s bad)))
    (testing "types with no methods are any EDN"
      (is (= {:type :note :text "hi"} (node/load s other))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expected a :tree node"
                          (node/load-as s other :tree)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a file"
                          (node/write-file! s other (java.io.ByteArrayOutputStream.))))))

(defmethod node/validate ::example [n]
  (when-not (string? (:text n))
    (throw (ex-info "an example needs :text" {})))
  n)

(deftest new-types-plug-in
  (is (node/validate {:type ::example :text "x"}))
  (is (thrown? clojure.lang.ExceptionInfo (node/validate {:type ::example}))))
