(ns firmament.loader-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [firmament.clojure :as clj]
            [firmament.edn :as edn]
            [firmament.loader :as loader]
            [firmament.storage :as storage]
            [firmament.store-test :refer [temp-dir]]
            [firmament.tree :as tree])
  (:import (java.nio.file Files LinkOption OpenOption)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(def ^:private app "test-resources/projects/app")

;; Namespaces stay loaded for the life of the JVM (*loaded-libs* is global),
;; so these tests share one import of the fixture.

(def ^:private s (storage/memory-store))

(def ^:private root (clj/import-project! s app))

(defn- call [sym & args] (apply (requiring-resolve sym) args))

(deftest requiring-from-the-store
  (loader/with-root [s root]
    (is (= root loader/*ns-root*))
    (require 'app.core)
    (testing "sources, a .cljc source and a namespace from a jar"
      (is (= "HELLO, STORE!" (call 'app.core/hello))))
    (testing "the JVM picks :source/clj over :source/cljc"
      (is (= :clj (call 'app.core/platform))))
    (testing "resources from the manifest and from a jar"
      (is (= {:name "store"} (call 'app.core/config)))
      (is (= "from a jar\n" (call 'app.core/banner))))
    (testing "sources are served from the store, not a checkout"
      (is (= "firmament" (.getProtocol (io/resource "app/core.clj"))))
      (is (= "firmament" (.getProtocol (io/resource "app/config.edn"))))
      (is (nil? (io/resource "app/missing.clj")))))
  (is (nil? loader/*ns-root*)))

(deftest loading-by-namespace
  (loader/with-root [s root]
    (is (= 'app.platform (loader/load-ns! s 'app.platform)))
    (is (= :clj @(resolve 'app.platform/which)))
    (is (contains? (loaded-libs) 'app.platform))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no JVM source"
                          (loader/load-ns! s 'app.nowhere))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"inside with-root"
                        (loader/load-ns! s 'app.platform))))

(deftest jars-are-written-by-hash
  (let [dir (temp-dir)
        jar (:link (get-in (tree/get-tree s (:jars (clj/manifest s root))) [:entries "greeting.jar"]))
        file (.resolve dir (str (subs (edn/link-ref jar) (count "sha256:")) ".jar"))]
    (loader/class-loader s root :jar-dir dir)
    (is (Files/exists file (make-array LinkOption 0)))
    (testing "and reused"
      (loader/class-loader s root :jar-dir dir)
      (is (= 1 (count (.list (.toFile dir))))))))

(deftest munged-path-collisions
  (let [d (temp-dir)
        s (storage/memory-store)]
    (doseq [[f src] {"src/my/a_b.clj" "(ns my.a-b)" "src/other.clj" "(ns my.a_b)"}]
      (let [p (.resolve d ^String f)]
        (Files/createDirectories (.getParent p) (make-array FileAttribute 0))
        (Files/write p (.getBytes ^String src) ^"[Ljava.nio.file.OpenOption;" (make-array OpenOption 0))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"both load from my/a_b.clj"
                          (loader/class-loader s (clj/import-project! s d))))))

