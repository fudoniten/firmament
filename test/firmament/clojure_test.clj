(ns firmament.clojure-test
  (:require [clojure.edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [firmament.clojure :as clj]
            [firmament.edn :as edn]
            [firmament.node :as node]
            [firmament.storage :as storage]
            [firmament.store-test :refer [temp-dir]]
            [firmament.tree :as tree])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files OpenOption Path Paths)))

(set! *warn-on-reflection* true)

(def ^:private sample "test-resources/projects/sample")

(defn- ns-form [s platform] (clj/read-ns-form s platform))

(deftest reading-ns-forms
  (is (= 'a.b (second (ns-form "; hi\n(ns a.b)" :clj))))
  (is (nil? (ns-form "(in-ns 'a.b)" :clj)))
  (is (nil? (ns-form "{}" :clj)))
  (is (nil? (ns-form "" :clj)))
  (testing "reader conditionals are resolved per platform"
    (let [src "(ns a (:require #?(:clj [x.clj] :cljs [x.cljs]) #?@(:cljs [y.z]) #?(:default [d])))"]
      (is (= ["d" "x.clj"] (clj/ns-requires (ns-form src :clj))))
      (is (= ["d" "x.cljs" "y.z"] (clj/ns-requires (ns-form src :cljs))))))
  (testing "reading never evaluates"
    (is (thrown? Exception (ns-form "(ns a (:require #=(java.lang.System/exit 1)))" :clj))))
  (testing "unknown tagged literals are read, not rejected"
    (is (= 'a (second (ns-form "(ns a {:x #js {}})" :cljs))))))

(deftest requires
  (is (= ["a" "b.c" "b.d" "e" "react"]
         (clj/ns-requires '(ns x "doc" {:author "me"}
                             (:require a [b c [d :as dd]] (b.c) :reload ["react" :as r])
                             (:use [e :only [f]])
                             (:import (java.util UUID)))))))

(deftest source-paths
  (is (= "my/app_server.clj" (clj/source-path "my.app-server" "clj")))
  (is (= "a.cljc" (clj/source-path "a" "cljc"))))

(defn- import! [s & opts] (apply clj/import-project! s sample opts))

(deftest importing
  (let [s (storage/memory-store)
        root (import! s)
        m (clj/manifest s root)
        nss (into {} (clj/namespace-entries s m))]
    (testing "namespaces come from ns forms, not paths"
      (is (= ["sample.core" "sample.my-app" "sample.renamed" "sample.ui" "sample.util"]
             (sort (keys nss))))
      (is (= #{:source/clj :analysis/clj} (set (keys (nss "sample.core")))))
      (is (= #{:source/cljc :analysis/cljc} (set (keys (nss "sample.util"))))))
    (testing "sources and analyses are separate trees with the same layout"
      (is (= #{:source/clj} (set (keys (get (into {} (clj/namespaces s (:namespaces m))) "sample.core")))))
      (is (= #{:analysis/clj} (set (keys (get (into {} (clj/namespaces s (:analyses m))) "sample.core"))))))
    (testing "sources are the raw file bytes"
      (is (= (slurp (str sample "/src/elsewhere/misplaced.clj"))
             (String. (node/read-file s (:source/clj (nss "sample.renamed"))) StandardCharsets/UTF_8))))
    (testing "analysis"
      (let [a (node/load s (:analysis/cljc (nss "sample.util")))]
        (is (= "sample.util" (:ns a)))
        (is (= (:source/cljc (nss "sample.util")) (:source a)))
        (is (= {:clj ["clojure.java.io" "clojure.set"]
                :cljs ["clojure.set" "goog.string" "react"]}
               (:requires a)))
        (is (= clj/analyzer (node/load s (:analyzer a)))))
      (is (= {:clj ["clojure.string" "sample.my-app" "sample.util"]}
             (:requires (node/load s (:analysis/clj (nss "sample.core"))))))
      (is (= {:cljs ["sample.macros" "sample.util"]}
             (:requires (node/load s (:analysis/cljs (nss "sample.ui")))))))
    (testing "everything else under :paths is a resource"
      (is (= ["config.edn" "data_readers.clj" "public"]
             (map key (tree/ls s (:resources m) []))))
      (is (some? (tree/lookup s (:resources m) ["public" "index.html"]))))
    (testing "jars"
      (is (= ["vendored.jar"] (map key (tree/ls s (:jars m) [])))))
    (testing "the same project gives the same hash"
      (is (= root (import! s)))
      (is (= root (import! (storage/memory-store)))))))

(deftest round-trip
  (let [s (storage/memory-store)
        root (import! s)
        out (.resolve (temp-dir) "project")]
    (node/export! s (node/load s root) out)
    (testing "the projection is a normal project layout"
      (is (Files/exists (.resolve out "src/sample/my_app.clj") (make-array java.nio.file.LinkOption 0)))
      (is (Files/exists (.resolve out "src/sample/renamed.clj") (make-array java.nio.file.LinkOption 0)))
      (is (Files/exists (.resolve out "resources/public/index.html") (make-array java.nio.file.LinkOption 0)))
      (is (= {:paths ["src" "resources"] :deps {'jars/vendored {:local/root "jars/vendored.jar"}}}
             (clojure.edn/read-string (slurp (str (.resolve out "deps.edn")))))))
    (testing "import, project and import again gives the same hash"
      (is (= root (clj/import-project! s out))))))

(deftest editing-one-namespace
  (let [s (storage/memory-store)
        dir (.resolve (temp-dir) "p")
        _ (node/export! s (node/load s (import! s)) dir)
        before (clj/manifest s (clj/import-project! s dir))
        f (.resolve dir "src/sample/ui.cljs")
        _ (Files/write f (.getBytes (str (slurp (str f)) "\n(def x 1)\n") StandardCharsets/UTF_8)
                       ^"[Ljava.nio.file.OpenOption;" (make-array OpenOption 0))
        after (clj/manifest s (clj/import-project! s dir))
        trees (fn [m] (mapv #(tree/resolve-path s (:namespaces m) %) [[] ["sample"]]))]
    (is (every? true? (map not= (trees before) (trees after))) "the path to the root changes")
    (is (= (:resources before) (:resources after)))
    (is (= (:jars before) (:jars after)))
    (is (= (dissoc (into {} (clj/namespace-entries s before)) "sample.ui")
           (dissoc (into {} (clj/namespace-entries s after)) "sample.ui"))
        "every other namespace keeps its links")
    (is (not= (:analysis/cljs (get (into {} (clj/namespace-entries s before)) "sample.ui"))
              (:analysis/cljs (get (into {} (clj/namespace-entries s after)) "sample.ui")))
        "the edited source gets a new analysis")))

(deftest code-hash-ignores-the-analyzer
  (let [s (storage/memory-store)
        v1 (clj/manifest s (import! s))
        v2 (with-redefs [clj/analyzer (assoc clj/analyzer :version 2)]
             (clj/manifest s (import! s)))]
    (is (= (:namespaces v1) (:namespaces v2)) "the code's hash doesn't change")
    (is (not= (:analyses v1) (:analyses v2)) "the analyses do")))

(deftest sources-and-analyses-must-match
  (let [s (storage/memory-store)
        m (clj/manifest s (import! s))
        empty-link (edn/link (storage/put-value! s tree/empty-tree))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has sources"
                          (clj/namespace-entries s (assoc m :analyses empty-link))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"analyses without sources"
                          (clj/namespace-entries s (assoc m :namespaces empty-link))))))

(deftest firmament-itself
  (testing "a second real project: this repository"
    (let [s (storage/memory-store)
          m (clj/manifest s (clj/import-project! s "."))
          nss (into {} (clj/namespace-entries s m))]
      (is (contains? nss "firmament.clojure"))
      (is (= ["clojure.edn" "clojure.string" "firmament.edn" "firmament.node"
              "firmament.storage" "firmament.tree"]
             (:clj (:requires (node/load s (:analysis/clj (nss "firmament.clojure"))))))))))

(deftest refusals
  (let [s (storage/memory-store)]
    (testing "two files for the same namespace and platform"
      (let [d (temp-dir)]
        (doseq [f ["src/a.clj" "src/b.clj"]]
          (let [p (.resolve d ^String f)]
            (Files/createDirectories (.getParent p) (make-array java.nio.file.attribute.FileAttribute 0))
            (Files/write p (.getBytes "(ns same)") ^"[Ljava.nio.file.OpenOption;" (make-array OpenOption 0))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"two :source/clj sources"
                              (clj/import-project! s d)))))
    (testing "classpath directories outside the project"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outside the project"
                            (import! s :classpath "src:/somewhere/else"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (node/validate {:type :clj/manifest :namespaces "nope"})))))

(deftest the-empty-program
  (let [a (clj/empty-manifest! (storage/memory-store))]
    (is (= a (clj/empty-manifest! (storage/memory-store))) "the same hash in every store")
    (let [s (storage/memory-store)]
      (clj/empty-manifest! s)
      (is (= [] (clj/namespace-entries s (clj/manifest s a)))))))

