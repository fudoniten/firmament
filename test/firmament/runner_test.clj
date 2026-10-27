(ns firmament.runner-test
  (:require [clojure.edn]
            [clojure.test :refer [deftest is testing]]
            [firmament.clojure :as clj]
            [firmament.edn :as edn]
            [firmament.loader :as loader]
            [firmament.node :as node]
            [firmament.runner :as runner]
            [firmament.storage :as storage]
            [firmament.store-test :refer [temp-dir]])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file CopyOption Files LinkOption OpenOption Path)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(def ^:private app "test-resources/projects/app")

(defn- counting
  "A resolver over `r` that records the name of every object fetched."
  [r fetched]
  (reify storage/Resolver
    (fetch [_ ref] (when-let [o (storage/fetch r ref)] (swap! fetched conj ref) o))
    (has? [_ ref] (storage/has? r ref))))

(defn- copy-dir! [^Path from ^Path to]
  (with-open [walk (Files/walk from (make-array java.nio.file.FileVisitOption 0))]
    (doseq [^Path p (iterator-seq (.iterator walk))
            :let [target (.resolve to (str (.relativize from p)))]]
      (if (Files/isDirectory p (make-array LinkOption 0))
        (Files/createDirectories target (make-array FileAttribute 0))
        (Files/copy p target ^"[Ljava.nio.file.CopyOption;" (make-array CopyOption 0))))))

(deftest closures-come-from-analyses
  (let [s (storage/memory-store)
        root (clj/import-project! s app :main 'app.core)
        m (clj/manifest s root)]
    (is (= "app.core" (runner/main-ns m)))
    (is (= ["app.core" "app.platform" "app.text-util"]
           (sort (keys (runner/closure s m "app.core"))))
        "greeting.core comes from a jar and clojure.* from the host, so neither is in it")
    (is (= ["app.platform"] (keys (runner/closure s m "app.platform"))))))

(deftest running
  (let [remote (storage/memory-store)
        root (clj/import-project! remote app :main 'app.core)
        params (edn/link (storage/put-value! remote {:who "params"}))]
    (testing "from an empty local store, with arguments"
      (let [r (storage/cache (storage/memory-store) remote)]
        (is (= "[\"HELLO, STORE!\" \"a\" \"b\"]\n"
               (with-out-str (runner/run-program! r root :args ["a" "b"]))))))
    (testing "with parameters"
      (is (= "[\"HELLO, STORE!\" {:who \"params\"}]\n"
             (with-out-str (runner/run-program! remote root :params params)))))
    (testing "a manifest without :clj/main"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :clj/main"
                            (runner/run-program! remote (clj/import-project! remote app)))))
    (testing "--main must name a namespace in the project"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no namespace app.nowhere"
                            (clj/import-project! remote app :main 'app.nowhere))))))

(deftest prefetching
  (let [remote (storage/memory-store)
        root (clj/import-project! remote app :main 'app.core)
        fetched (atom #{})
        local (storage/memory-store)
        r (storage/cache local (counting remote fetched))]
    (is (= ["app.core" "app.platform" "app.text-util"] (runner/prefetch! r root)))
    (testing "after prefetching, loading needs nothing from the remote"
      (reset! fetched #{})
      (loader/with-root [r root]
        (require 'app.core :reload-all)
        (is (= "HELLO, STORE!" ((resolve 'app.core/hello)))))
      (is (= #{} @fetched)))
    (testing "running a changed program fetches only what changed"
      (let [dir (.resolve (temp-dir) "app")
            _ (copy-dir! (.toPath (java.io.File. ^String app)) dir)
            f (.resolve dir "src/app/text_util.cljc")
            _ (Files/write f (.getBytes (str (slurp (str f)) "\n(def edited true)\n") StandardCharsets/UTF_8)
                           ^"[Ljava.nio.file.OpenOption;" (make-array OpenOption 0))
            root' (clj/import-project! remote dir :main 'app.core)
            m' (clj/manifest remote root')]
        (reset! fetched #{})
        (runner/prefetch! r root')
        ;; The manifest; the root and app trees of :namespaces and of
        ;; :analyses; app.text-util's new source and new analysis.
        (is (= 7 (count @fetched)))
        (is (contains? @fetched (edn/link-ref root')))
        (is (not (contains? @fetched (edn/link-ref (:jars m')))))
        (is (not (contains? @fetched (edn/link-ref (:resources m')))))))))

(deftest caches-check-what-they-copy
  (let [real (storage/memory-store)
        ref (storage/put-blob! real (.getBytes "real"))
        liar (reify storage/Resolver
               (fetch [_ _] {:kind :blob :bytes (.getBytes "fake")})
               (has? [_ _] true))
        local (storage/memory-store)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"asked for"
                          (storage/fetch (storage/cache local liar) ref)))
    (is (not (storage/has? local ref)))
    (testing "and fetch-all! copies a chunked file's chunks"
      (let [big (byte-array (* 1024 1024) (byte 7))
            link (storage/put-file! real big)
            local (storage/memory-store)
            names (storage/fetch-all! (storage/cache local real) (edn/link-ref link))]
        (is (< 1 (count names)))
        (is (every? #(storage/has? local %) names))
        (is (= (seq big) (seq (node/read-file local link))))))))
