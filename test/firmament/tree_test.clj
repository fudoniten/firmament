(ns firmament.tree-test
  (:require [clojure.test :refer [deftest is testing]]
            [firmament.edn :as edn]
            [firmament.gen :as gen]
            [firmament.hash :as hash]
            [firmament.node :as node]
            [firmament.storage :as storage]
            [firmament.store :as store]
            [firmament.store-test :refer [temp-dir]]
            [firmament.tree :as tree])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files OpenOption Path)
           (java.nio.file.attribute FileAttribute)
           (java.util Arrays)))

(set! *warn-on-reflection* true)

(def ^:private ^"[Ljava.nio.file.OpenOption;" no-options (make-array OpenOption 0))

(defn- utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

(defn- write! [^Path dir ^String rel ^bytes data]
  (let [p (.resolve dir rel)]
    (Files/createDirectories (.getParent p) (make-array FileAttribute 0))
    (Files/write p data no-options)))

(defn- sample-dir
  "A small project-like directory: nested dirs, an empty dir, a large file."
  ^Path []
  (let [d (temp-dir)]
    (write! d "README.md" (utf8 "# sample\n"))
    (write! d "src/my/app/core.clj" (utf8 "(ns my.app.core)\n"))
    (write! d "src/my/app/util.cljc" (utf8 "(ns my.app.util)\n"))
    (write! d "resources/data.bin" (gen/sha256-ctr "tree-test" 0 (* 600 1024)))
    (Files/createDirectories (.resolve d "empty") (make-array FileAttribute 0))
    d))

(defn- link-of [ref] (edn/link ref))

(deftest validation
  (let [l (link-of (hash/blob-hash (byte-array 0)))]
    (is (tree/tree {"a" {:link l} "b" {:children l :tags {:posix/mode 420}}}))
    (doseq [bad ["" "." ".." "a/b" "é"]]
      (is (thrown? clojure.lang.ExceptionInfo (tree/tree {bad {:link l}})) (pr-str bad)))
    (testing "entries"
      (is (thrown? clojure.lang.ExceptionInfo (tree/tree {"a" {}})) "no links")
      (is (thrown? clojure.lang.ExceptionInfo (tree/tree {"a" {:tags {:x/y 1}}})) "only tags")
      (is (thrown? clojure.lang.ExceptionInfo (tree/tree {"a" {:link l :tags {}}})) "empty tags")
      (is (thrown? clojure.lang.ExceptionInfo (tree/tree {"a" {:link l :tags {:plain 1}}}))
          "un-namespaced tag")
      (is (thrown? clojure.lang.ExceptionInfo (tree/tree {"a" {:link "sha256:00"}})) "not a link"))
    (is (thrown? clojure.lang.ExceptionInfo (node/validate {:type :tree :entries {} :extra 1})))))

(deftest path-copying
  (let [s (store/open (temp-dir))
        f1 (hash/blob-link (utf8 "one"))
        f2 (hash/blob-link (utf8 "two"))
        root (reduce (fn [r path] (tree/assoc-entry! s r path {:link f1}))
                     nil [["a" "b" "c" "f"] ["a" "x" "g"] ["top"]])]
    (is (= {:link f1} (tree/lookup s root ["a" "b" "c" "f"])))
    (is (nil? (tree/lookup s root ["a" "nope"])))
    (is (nil? (tree/lookup s root ["top" "below-a-file"])))
    (testing "an update rewrites only the trees on its path"
      (let [root' (tree/assoc-entry! s root ["a" "b" "c" "f"] {:link f2})
            path-links (fn [r] (mapv #(tree/resolve-path s r %) [[] ["a"] ["a" "b"] ["a" "b" "c"]]))]
        (is (= {:link f2} (tree/lookup s root' ["a" "b" "c" "f"])))
        (is (every? true? (map not= (path-links root) (path-links root'))))
        (is (= (tree/resolve-path s root ["a" "x"]) (tree/resolve-path s root' ["a" "x"]))
            "a sibling subtree keeps its hash")
        (is (= root (tree/assoc-entry! s root' ["a" "b" "c" "f"] {:link f1}))
            "undoing the change gives the original root")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :children"
                          (tree/assoc-entry! s root ["top" "below"] {:link f2})))
    (testing "removal"
      (let [root' (tree/dissoc-entry! s root ["a" "x" "g"])]
        (is (nil? (tree/lookup s root' ["a" "x" "g"])))
        (is (= tree/empty-tree (tree/get-tree s (tree/resolve-path s root' ["a" "x"]))))))))

(deftest import-export
  (let [s (store/open (temp-dir))
        dir (sample-dir)
        root (tree/import-dir! s dir)]
    (testing "importing the same directory twice gives the same hash"
      (is (= root (tree/import-dir! s dir)))
      (is (= root (tree/import-dir! (store/open (temp-dir)) dir)) "even into another store"))
    (testing "the tree has the expected shape"
      (is (= ["README.md" "empty" "resources" "src"] (map key (tree/ls s root []))))
      (is (= tree/empty-tree (tree/get-tree s (tree/resolve-path s root ["empty"]))))
      (is (= (hash/blob-link (utf8 "(ns my.app.core)\n"))
             (:link (tree/lookup s root ["src" "my" "app" "core.clj"]))))
      (is (= :chunked (:type (storage/fetch-value
                              s (edn/link-ref (:link (tree/lookup s root ["resources" "data.bin"]))))))))
    (testing "import, export and import again gives the same hash"
      (let [out (.resolve (temp-dir) "out")]
        (tree/export! s root out)
        (is (Arrays/equals (Files/readAllBytes (.resolve dir "resources/data.bin"))
                           (Files/readAllBytes (.resolve out "resources/data.bin"))))
        (is (Files/isDirectory (.resolve out "empty") (make-array java.nio.file.LinkOption 0)))
        (is (= root (tree/import-dir! s out)))))
    (testing "editing one file changes only the hashes on its path"
      (let [before (into {} (for [p [[] ["src"] ["src" "my"] ["src" "my" "app"] ["resources"] ["empty"]]]
                              [p (tree/resolve-path s root p)]))
            _ (write! dir "src/my/app/core.clj" (utf8 "(ns my.app.core)\n(def x 1)\n"))
            root' (tree/import-dir! s dir)
            after (into {} (for [p (keys before)] [p (tree/resolve-path s root' p)]))]
        (doseq [p [[] ["src"] ["src" "my"] ["src" "my" "app"]]]
          (is (not= (before p) (after p)) (pr-str p)))
        (doseq [p [["resources"] ["empty"]]]
          (is (= (before p) (after p)) (pr-str p)))
        (is (= root' (tree/assoc-entry! s root ["src" "my" "app" "core.clj"]
                                        {:link (storage/put-file! s (utf8 "(ns my.app.core)\n(def x 1)\n"))}))
            "the same edit made with a path-copying update gives the same root")))))

(deftest import-refusals
  (let [s (store/open (temp-dir))]
    (testing "symbolic links"
      (let [d (temp-dir)]
        (write! d "real" (utf8 "x"))
        (Files/createSymbolicLink (.resolve d "alias") (.resolve d "real") (make-array FileAttribute 0))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"symbolic links" (tree/import-dir! s d)))))
    (testing "export to a non-empty directory"
      (let [d (temp-dir)]
        (write! d "x" (utf8 "x"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an empty directory"
                              (tree/export! s (tree/import-dir! s (sample-dir)) d)))))
    (testing "export of entries that aren't plain files or directories"
      (let [l (storage/put-file! s (utf8 "src"))
            root (tree/assoc-entry! s nil ["my"] {:source/clj l})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"only :link or :children"
                              (tree/export! s root (.resolve (temp-dir) "out"))))))))
