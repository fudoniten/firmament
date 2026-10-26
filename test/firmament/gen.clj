(ns firmament.gen
  "Generated test data, as described in test-resources/vectors/README.md."
  (:import (java.io ByteArrayOutputStream)
           (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Arrays)))

(set! *warn-on-reflection* true)

(defn ctr-block
  "SHA-256(utf8(seed) || uint64be(i))."
  ^bytes [^String seed ^long i]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md (.getBytes seed StandardCharsets/UTF_8))
    (.update md (.array (.putLong (ByteBuffer/allocate 8) i)))
    (.digest md)))

(defn sha256-ctr
  "Bytes [offset, offset + length) of the sha256-ctr stream for `seed`."
  ^bytes [seed ^long offset ^long length]
  (let [first-block (quot offset 32)
        last-block (quot (+ offset length 31) 32)
        out (ByteArrayOutputStream. (int (* 32 (- last-block first-block))))]
    (doseq [i (range first-block last-block)]
      (.write out ^bytes (ctr-block seed i)))
    (let [skip (- offset (* first-block 32))]
      (Arrays/copyOfRange (.toByteArray out) (int skip) (int (+ skip length))))))

(defn data-from
  "The bytes a vector's :data spec describes."
  ^bytes [{:keys [gen length seed offset parts]}]
  (case gen
    :zeros (byte-array length)
    :sha256-ctr (sha256-ctr seed offset length)
    :concat (let [out (ByteArrayOutputStream.)]
              (doseq [p parts] (.write out ^bytes (data-from p)))
              (.toByteArray out))))
