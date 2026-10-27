(ns firmament.edn-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [firmament.edn :as edn]
            [firmament.hash :as hash]
            [firmament.vectors :refer [hex load-vectors unhex]]))

(def vectors (load-vectors "encoding.edn"))

(defn- error-kind [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (::edn/error (ex-data e)))))

;; ---------------------------------------------------------- golden vectors

(deftest format-version
  (is (= "firmament-0" (:format vectors))))

(deftest valid-vectors
  (doseq [{:keys [name value hex] h :hash} (:valid vectors)]
    (testing name
      (let [canonical (edn/encode value)]
        (is (= hex (firmament.vectors/hex canonical)) "encode")
        (is (= value (edn/decode (unhex hex))) "decode")
        (is (= h (hash/edn-hash-bytes canonical)) "hash")))))

(deftest normalize-vectors
  (doseq [{:keys [name value hex] h :hash} (:normalize vectors)]
    (testing name
      (let [canonical (edn/encode value)]
        (is (= hex (firmament.vectors/hex canonical)) "encoders normalize")
        (is (not= value (edn/decode canonical)) "decoding gives the NFC value")
        (is (= h (hash/edn-hash-bytes canonical)))))))

(deftest invalid-vectors
  (doseq [{:keys [name hex]} (:invalid vectors)]
    (testing name
      (is (= :decode (error-kind #(edn/decode (unhex hex))))))))

(deftest blob-vectors
  (doseq [{:keys [name hex] h :hash} (:blobs (load-vectors "blobs.edn"))]
    (testing name
      (is (= h (hash/blob-hash (unhex hex)))))))

;; --------------------------------------------------------------- encoder

(deftest encoder-examples
  (is (= "{:a 1 :b [true \"x\"]}" (String. (edn/encode {:b [true "x"] :a 1}) "UTF-8")))
  (is (= "{\"z\" 1 :a 2}" (String. (edn/encode {:a 2 "z" 1}) "UTF-8")))
  (is (= "{1 2 true 1}" (String. (edn/encode {true 1 1 2}) "UTF-8")))
  (is (= (hex (edn/encode (edn/->bytes (byte-array [1 2]))))
         (hex (edn/encode (byte-array [1 2]))))
      "byte arrays encode like Bytes"))

(defrecord Rec [a])

(deftest encoder-rejects
  (doseq [v [nil 1.5 1/2 1N #{1} '(1) 'sym \a (Object.)
             [nil] {:a nil} {nil 1} (keyword "a.b") (keyword "1a")
             (keyword "a" "b/c") "\ud800"]]
    (is (= :encode (error-kind #(edn/encode v))) (pr-str v)))
  (is (= :encode (error-kind #(edn/encode {"é" 1 "é" 2})))
      "keys equal after NFC")
  (is (= :encode (error-kind #(edn/encode (->Rec 1)))) "records are not maps"))

(deftest depth-limit
  (let [nest (fn [n] (nth (iterate vector []) (dec n)))]
    (is (edn/encode (nest edn/max-depth)))
    (is (= :encode (error-kind #(edn/encode (nest (inc edn/max-depth))))))
    (is (= :encode (error-kind #(edn/encode {:a (nest edn/max-depth)}))))))

(deftest link-and-bytes-values
  (let [ref (str "sha256:" (apply str (repeat 64 "a")))]
    (is (= (edn/link ref) (edn/link ref)))
    (is (= 32 (alength (edn/link-digest (edn/link ref)))))
    (is (= :encode (error-kind #(edn/link "sha256:AA"))))
    (is (= (edn/->bytes (byte-array [1])) (edn/->bytes (byte-array [1]))))
    (is (= #{(edn/->bytes (byte-array [1]))} #{(edn/->bytes (byte-array [1]))}))))

;; ------------------------------------------------------------ properties

(def gen-keyword
  (gen/elements [:a :b/c :my.app/x-y? :- :+ :true (keyword "-1")]))

(def gen-link
  (gen/fmap #(edn/link (str "sha256:" (apply str %)))
            (gen/vector (gen/elements (seq "0123456789abcdef")) 64)))

(def gen-string
  (gen/fmap #(apply str %)
            (gen/vector (gen/elements ["a" "\"" "\\" "\n" "\t" "\r" "\u0000" "\u001f"
                                       "\u007f" "\u0080" "é" "é" " "
                                       "￿" "😀" " "]))))

(def gen-scalar
  (gen/one-of [gen/boolean gen/large-integer
               (gen/elements [Long/MIN_VALUE Long/MAX_VALUE 0 -1])
               gen-string gen-keyword gen-link
               (gen/fmap #(edn/->bytes (byte-array %)) (gen/vector gen/byte))]))

(def gen-value
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner) (gen/map inner inner {:max-elements 4})]))
   gen-scalar))

(defspec round-trip 1000
  (prop/for-all [v gen-value]
    (let [b (try (edn/encode v)
                 ;; Distinct keys can collide after NFC; that rejection is
                 ;; tested above.
                 (catch clojure.lang.ExceptionInfo _ nil))]
      (or (nil? b)
          (let [d (edn/decode b)]
            (and (= (hex b) (hex (edn/encode d)))
                 (= (hex b) (hex (edn/encode v)))))))))

(defspec decode-of-encode-is-identity-for-nfc-values 1000
  (prop/for-all [v (gen/recursive-gen
                    (fn [inner] (gen/one-of [(gen/vector inner)
                                             (gen/map gen-keyword inner {:max-elements 4})]))
                    (gen/one-of [gen/boolean gen/large-integer gen-keyword gen-link]))]
    (= v (edn/decode (edn/encode v)))))

(deftest links
  (let [a (edn/link (str "sha256:" (apply str (repeat 64 "a"))))
        b (edn/link (str "sha256:" (apply str (repeat 64 "b"))))]
    (is (= #{} (edn/links {:x [1 "s" :k true]})))
    (is (= #{a b} (edn/links {:type :tree
                              :entries {"x" {:link a :tags {:t/v [b a]}}}
                              a "links as map keys count too"})))))

(deftest unpaired-surrogates
  (doseq [s ["a\uD800" "\uDC00b" "\uDC00\uD800"]]
    (is (= :encode (error-kind #(edn/encode s))) (pr-str s)))
  (is (edn/encode "😀") "a pair is fine"))

(deftest links-and-bytes-are-values
  (let [ref-a (str "sha256:" (apply str (repeat 64 "a")))
        ref-b (str "sha256:" (apply str (repeat 64 "b")))
        a (edn/link ref-a)
        b (edn/link ref-b)]
    (is (= (edn/link ref-a) a))
    (is (= (hash (edn/link ref-a)) (hash a)))
    (is (= #{a b} (set [a b (edn/link ref-b)])))
    (is (= [a b] (sort [b a])))
    (is (= "x" (get {a "x"} (edn/link ref-a)))))
  (let [x (edn/->bytes (byte-array [1 2 3]))
        y (edn/->bytes (byte-array [(unchecked-byte 0xff)]))]
    (is (= 3 (count x)))
    (is (= x (edn/->bytes (byte-array [1 2 3]))))
    (is (= (hash x) (hash (edn/->bytes (byte-array [1 2 3])))))
    (is (= [x y] (sort [y x])) "0xff sorts after 0x01, as unsigned bytes")))
