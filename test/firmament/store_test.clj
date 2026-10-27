(ns firmament.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [firmament.edn :as edn]
            [firmament.gen :as gen]
            [firmament.hash :as hash]
            [firmament.resolver :as resolver]
            [firmament.store :as store]
            [firmament.vectors :refer [hex]])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files OpenOption Path)
           (java.nio.file.attribute FileAttribute)
           (java.security MessageDigest)
           (java.util Arrays)))

(set! *warn-on-reflection* true)

(defn temp-dir ^Path []
  (Files/createTempDirectory "firmament-test" (make-array FileAttribute 0)))

(defn- utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

(defn- object-file ^Path [s ref]
  (let [h (subs ref (count "sha256:"))]
    (.resolve ^Path (:root s) (str "objects/" (subs h 0 2) "/" (subs h 2)))))

(deftest blobs
  (let [s (store/open (temp-dir))
        ref (store/put-blob! s (utf8 "hello\n"))]
    (is (= "sha256:1be8615f7fbda85e1ef029f02573bdfefe09fb01af39e6ce684104a45a360ae8" ref))
    (is (store/has? s ref))
    (let [{:keys [kind bytes]} (store/get-object s ref)]
      (is (= :blob kind))
      (is (= "68656c6c6f0a" (hex bytes))))
    (testing "the object file is the hash input, so sha256sum of it is the hash"
      (let [data (Files/readAllBytes (object-file s ref))]
        (is (= (str "sha256:" (hex (.digest (MessageDigest/getInstance "SHA-256") data))) ref))
        (is (= "blob\nhello\n" (String. data StandardCharsets/UTF_8)))))
    (testing "putting again is a no-op"
      (is (= ref (store/put-blob! s (utf8 "hello\n")))))))

(deftest values
  (let [s (store/open (temp-dir))
        v {:type :note :text "hi" :see (edn/link (hash/blob-hash (byte-array 0)))}
        ref (store/put-value! s v)]
    (is (= (hash/edn-hash v) ref))
    (is (= :edn (:kind (store/get-object s ref))))
    (is (= v (resolver/fetch-value s ref)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (resolver/fetch-value s (store/put-blob! s (byte-array 1)))))))

(deftest missing
  (let [s (store/open (temp-dir))
        ref (hash/blob-hash (utf8 "absent"))]
    (is (not (store/has? s ref)))
    (is (nil? (store/get-object s ref)))
    (is (thrown? clojure.lang.ExceptionInfo (resolver/fetch! s ref)))))

(deftest files
  (let [s (store/open (temp-dir))]
    (testing "a small file is one blob"
      (let [data (utf8 "small file\n")
            root (store/put-file! s data)]
        (is (= (hash/blob-link data) root))
        (is (Arrays/equals data (resolver/read-file s root)))))
    (testing "a large file is chunked, and reads back byte for byte"
      (let [^bytes data (gen/sha256-ctr "store-test" 0 (* 3 1024 1024))
            root (store/put-file! s data)
            node (resolver/fetch-value s (edn/link-ref root))]
        (is (= :chunked (:type node)))
        (is (= (alength data) (:size node)))
        (is (every? #(store/has? s (edn/link-ref %)) (edn/links node)))
        (is (Arrays/equals data ^bytes (resolver/read-file s root)))))))

(deftest corruption-is-detected
  (let [s (store/open (temp-dir))
        ref (store/put-blob! s (utf8 "hello\n"))]
    (Files/write (object-file s ref) (utf8 "blob\nhellO\n") ^"[Ljava.nio.file.OpenOption;" (make-array OpenOption 0))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"corrupt object"
                          (store/get-object s ref)))))

(deftest chunk-size-mismatch-is-detected
  (let [s (store/open (temp-dir))
        blob (store/put-blob! s (utf8 "abc"))
        node (store/put-value! s {:type :chunked :level 0 :size 4
                                  :chunks [{:link (edn/link blob) :size 4}]})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"size mismatch"
                          (resolver/read-file s (edn/link node))))))

(deftest metadata
  (let [s (store/open (temp-dir))
        ref (store/put-blob! s (utf8 "kept"))]
    (is (= {} (store/object-meta s ref)))
    (is (= {:pinned? true} (store/update-meta! s ref assoc :pinned? true)))
    (store/update-meta! s ref assoc :source (edn/link ref) :expires #inst "2030-01-01")
    (is (= {:pinned? true :source (edn/link ref) :expires #inst "2030-01-01"}
           (store/object-meta (store/open (:root s)) ref)))
    (testing "metadata never changes the object"
      (is (= ref (hash/blob-hash (:bytes (store/get-object s ref))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (store/update-meta! s (hash/blob-hash (utf8 "absent")) assoc :pinned? true)))))

(deftest opening
  (let [dir (temp-dir)]
    (testing "a new store records its format version"
      (store/open dir)
      (is (= "firmament-0\n" (String. (Files/readAllBytes (.resolve dir "format"))
                                      StandardCharsets/US_ASCII)))
      (is (store/open dir) "reopens"))
    (testing "another format version is refused"
      (Files/write (.resolve dir "format") (utf8 "firmament-1\n") ^"[Ljava.nio.file.OpenOption;" (make-array OpenOption 0))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"firmament-1" (store/open dir)))))
  (testing "a non-empty directory that isn't a store is refused"
    (let [dir (temp-dir)]
      (Files/write (.resolve dir "other") (utf8 "x") ^"[Ljava.nio.file.OpenOption;" (make-array OpenOption 0))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a firmament store"
                            (store/open dir))))))

(deftest default-dir
  (is (= "/srv/fm" (store/default-dir {"FIRMAMENT_STORE" "/srv/fm"})))
  (is (= (str (System/getProperty "user.home") "/.firmament/store")
         (store/default-dir {})
         (store/default-dir {"FIRMAMENT_STORE" ""}))))
