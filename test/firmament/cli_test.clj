(ns firmament.cli-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [firmament.cli :as cli]
            [firmament.gen :as gen]
            [firmament.hash :as hash]
            [firmament.store-test :refer [temp-dir]])
  (:import (java.io ByteArrayOutputStream PrintStream)
           (java.nio.file Files OpenOption Path)
           (java.util Arrays)))

(set! *warn-on-reflection* true)

(defn- run [store & args]
  (let [out (ByteArrayOutputStream.)
        err (ByteArrayOutputStream.)
        code (cli/run (concat ["--store" (str store)] args)
                      {:env {} :out out :err (PrintStream. err true "UTF-8")})]
    {:code code :out (.toByteArray out) :err (.toString err "UTF-8")}))

(defn- write-file ^Path [^Path dir name ^bytes data]
  (Files/write (.resolve dir ^String name) data ^"[Ljava.nio.file.OpenOption;" (make-array OpenOption 0)))

(deftest put-get-cat
  (let [store (temp-dir)
        files (temp-dir)
        ^bytes small (.getBytes "hello\n" "UTF-8")
        ^bytes large (gen/sha256-ctr "cli-test" 0 (* 1024 1024))
        {:keys [code out]} (run store "put" (str (write-file files "small" small))
                                (str (write-file files "large" large)))
        [small-ref large-ref] (str/split-lines (String. ^bytes out "UTF-8"))]
    (is (= 0 code))
    (is (= (hash/blob-hash small) small-ref))
    (testing "cat gives back the file"
      (is (Arrays/equals small ^bytes (:out (run store "cat" small-ref))))
      (is (Arrays/equals large ^bytes (:out (run store "cat" large-ref)))))
    (testing "get gives the object itself"
      (is (Arrays/equals small ^bytes (:out (run store "get" small-ref))))
      (is (str/starts-with? (String. ^bytes (:out (run store "get" large-ref)) "UTF-8")
                            "{:chunks [")))))

(deftest errors
  (let [store (temp-dir)]
    (is (= 2 (:code (run store))))
    (is (= 2 (:code (run store "frobnicate"))))
    (is (= 2 (:code (run store "get"))))
    (is (= 2 (:code (run store "put"))))
    (let [{:keys [code err]} (run store "cat" (hash/blob-hash (byte-array 1)))]
      (is (= 1 code))
      (is (str/includes? err "object not found")))
    (is (= 1 (:code (run store "get" "sha256:nothex"))))
    (is (= 1 (:code (run store "put" (str (.resolve (temp-dir) "missing"))))))))

(deftest trees
  (let [store (temp-dir)
        dir (temp-dir)
        _ (do (Files/createDirectories (.resolve dir "sub") (make-array java.nio.file.attribute.FileAttribute 0))
              (write-file dir "a.txt" (.getBytes "a\n" "UTF-8"))
              (write-file (.resolve dir "sub") "b.txt" (.getBytes "b\n" "UTF-8")))
        {:keys [code out]} (run store "put" "-r" (str dir))
        root (str/trim (String. ^bytes out "UTF-8"))]
    (is (= 0 code))
    (let [lines (str/split-lines (String. ^bytes (:out (run store "ls" root)) "UTF-8"))]
      (is (= [(str (hash/blob-hash (.getBytes "a\n" "UTF-8")) "  a.txt")] (take 1 lines)))
      (is (str/ends-with? (second lines) "  sub/")))
    (is (str/ends-with? (str/trim (String. ^bytes (:out (run store "ls" root "sub")) "UTF-8"))
                        "  b.txt"))
    (let [target (.resolve (temp-dir) "out")]
      (is (= 0 (:code (run store "export" root (str target)))))
      (is (= "b\n" (String. (Files/readAllBytes (.resolve target "sub/b.txt")) "UTF-8"))))
    (is (= 1 (:code (run store "ls" root "missing"))))
    (is (= 2 (:code (run store "put" "-r"))))
    (is (= 2 (:code (run store "export" root))))))
