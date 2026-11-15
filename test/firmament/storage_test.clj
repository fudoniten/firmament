(ns firmament.storage-test
  (:require [clojure.test :refer [deftest is testing]]
            [firmament.edn :as edn]
            [firmament.storage :as storage]))

(set! *warn-on-reflection* true)

(deftest walking
  (let [g {:a [:b :c] :b [:d] :c [:d] :d []}]
    (testing "children come before parents, and each node once"
      (is (= [:d :b :c :a] (storage/walk g [:a]))))
    (testing "several roots share what they reach"
      (is (= [:d :b :c :a] (storage/walk g [:b :a]))))
    (testing "nil children stop the walk below a node"
      (is (= [:b :c :a] (storage/walk #(when-not (#{:b :c} %) (g %)) [:a]))))
    (testing "deep graphs don't use the stack"
      (is (= 100001 (count (storage/walk #(when (pos? %) [(dec %)]) [100000])))))))

(defn- counting
  "A store over `s` that counts fetches and has? calls."
  [s counts]
  (reify
    storage/Resolver
    (fetch [_ l] (swap! counts update :fetch (fnil inc 0)) (storage/fetch s l))
    (has? [_ l] (swap! counts update :has? (fnil inc 0)) (storage/has? s l))
    storage/Store
    (put-object! [_ k p] (storage/put-object! s k p))))

(defn- chain
  "A chain of `n` values in `s`, each linking to the one before; returns
  the links, oldest first."
  [s n]
  (reductions (fn [prev i] (storage/put-value! s {:i i :prev prev}))
              (storage/put-value! s {:i 0})
              (range 1 n)))

(deftest copying
  (let [from (storage/memory-store)
        links (vec (chain from 50))
        to (storage/memory-store)]
    (is (= 10 (storage/copy! from to (links 9))))
    (testing "below an object the target has, nothing is checked"
      (let [counts (atom {})
            n (storage/copy! (counting from counts) (counting to (atom {})) (links 49))]
        (is (= 40 n))
        (is (= 40 (:fetch @counts)) "only the 40 new objects are fetched")))
    (is (zero? (storage/copy! from to (links 49))))
    (is (every? #(storage/has? to %) links))))

(deftest object-links
  (let [s (storage/memory-store)
        b (storage/put-blob! s (byte-array 1))
        v (storage/put-value! s {:x b :y [b]})]
    (is (= #{} (storage/object-links (storage/fetch s b))))
    (is (= #{b} (storage/object-links (storage/fetch s v))))
    (is (edn/link? v))))
