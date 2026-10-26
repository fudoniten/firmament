(ns firmament.chunk-test
  (:require [clojure.test :refer [deftest is testing]]
            [firmament.chunk :as chunk]
            [firmament.edn :as edn]
            [firmament.gen :as gen]
            [firmament.vectors :refer [hex load-vectors]]))

(def ^:private mib (* 1024 1024))

;; ---------------------------------------------------------- golden vectors

(defn- format-hex [^long x] (String/format "%016x" (object-array [x])))

(deftest gear-vectors
  (let [{:keys [format gear]} (load-vectors "gear.edn")]
    (is (= "firmament-0" format))
    (is (= 256 (count gear)))
    (doseq [i (range 256)]
      (is (= (nth gear i) (format-hex (chunk/gear i))) (str "GEAR[" i "]")))))

(deftest chunking-vectors
  (doseq [{:keys [name data chunked cuts root]} (:files (load-vectors "chunking.edn"))]
    (testing name
      (let [f (chunk/store-file (gen/data-from data))]
        (is (= chunked (:chunked? f)))
        (when chunked (is (= cuts (:cuts f))))
        (is (= root (edn/link-ref (:root f))))))))

(defn- index-entries [seed n]
  (mapv (fn [i] [(edn/link (str "sha256:" (hex (gen/ctr-block seed i)))) (+ 65536 i)])
        (range n)))

(deftest index-vectors
  (doseq [{:keys [name entries levels root]} (:indexes (load-vectors "index.edn"))]
    (testing name
      (let [idx (chunk/build-index (index-entries (:seed entries) (:count entries)))]
        (is (= levels (:levels idx)))
        (is (= root (edn/link-ref (:root idx))))))))

;; ------------------------------------------------------------- properties

(deftest chunk-sizes
  (let [data (gen/sha256-ctr "sizes" 0 (+ (* 3 mib) 77))
        cuts (chunk/cut-points data)
        sizes (map - cuts (cons 0 cuts))]
    (is (= (alength data) (last cuts)))
    (is (every? #(<= chunk/min-size % chunk/max-size) (butlast sizes)))
    (is (<= 1 (last sizes) chunk/max-size))))

(deftest zeros-cut-at-max
  (is (= [chunk/max-size (* 2 chunk/max-size) (* 3 chunk/max-size)]
         (chunk/cut-points (byte-array (* 3 chunk/max-size))))))

(deftest insert-is-local
  (let [a (gen/sha256-ctr "local" 0 (* 4 mib))
        b (gen/data-from {:gen :concat
                          :parts [{:gen :sha256-ctr :seed "local" :offset 0 :length (* 2 mib)}
                                  {:gen :sha256-ctr :seed "insert" :offset 0 :length 8}
                                  {:gen :sha256-ctr :seed "local" :offset (* 2 mib)
                                   :length (* 2 mib)}]})
        fa (chunk/store-file a)
        fb (chunk/store-file b)
        changed (remove (set (keys (:blobs fa))) (keys (:blobs fb)))]
    (is (<= (count changed) 2))
    (is (not= (:root fa) (:root fb)))))

(deftest blob-or-chunked
  (is (not (:chunked? (chunk/store-file (byte-array chunk/max-size)))))
  (let [f (chunk/store-file (gen/sha256-ctr "x" 0 (inc chunk/max-size)))]
    (is (:chunked? f))
    (is (<= 2 (count (:cuts f))))))

(deftest root-node-reassembles-the-file
  (let [data (gen/sha256-ctr "root" 0 mib)
        f (chunk/store-file data)
        node (edn/decode (get-in f [:index :objects (edn/link-ref (:root f))]))]
    (is (= {:type :chunked :level 0 :size mib} (dissoc node :chunks)))
    (is (= (hex data)
           (apply str (map #(hex (get (:blobs f) (edn/link-ref (:link %)))) (:chunks node)))))))

(deftest index-cap
  (let [entries (->> (index-entries "cap" 6000)
                     (remove (comp chunk/boundary? first))
                     (take (inc chunk/index-cap))
                     vec)]
    (is (= [[chunk/index-cap 1] [2]] (:levels (chunk/build-index entries))))))
