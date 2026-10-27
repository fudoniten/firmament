(ns firmament.edn
  "Canonical EDN encoder and strict decoder (docs/spec/encoding.md).

  Clojure values used for EDN values:

    map          any map (not a record)
    vector       vector
    string       String
    integer      Long, Integer, Short or Byte (signed 64-bit)
    boolean      Boolean
    keyword      Keyword
    link         Link
    byte string  Bytes, or a byte array (the decoder returns Bytes)

  Everything else, including nil, lists, sets, symbols and floats, is
  rejected."
  (:require [clojure.string :as str])
  (:import (java.nio ByteBuffer)
           (java.nio.charset CharacterCodingException CodingErrorAction
                             StandardCharsets)
           (java.text Normalizer Normalizer$Form)
           (java.util Arrays Base64 HexFormat)))

(set! *warn-on-reflection* true)

(def max-depth 256)

(defn- fail [kind msg data]
  (throw (ex-info msg (assoc data ::error kind))))

;; ------------------------------------------------------------------ types

(def ^:private link-re #"sha256:[0-9a-f]{64}")

(deftype Link [^String ref]
  Object
  (equals [_ other]
    (and (instance? Link other) (= ref (.-ref ^Link other))))
  (hashCode [_] (.hashCode ref))
  (toString [_] (str "#h \"" ref "\"")))

(defmethod print-method Link [^Link l ^java.io.Writer w]
  (.write w (str l)))

(defn link
  "A link to `ref`, a \"sha256:<64 lowercase hex>\" string."
  [ref]
  (when-not (and (string? ref) (re-matches link-re ref))
    (fail :encode (str "invalid link: " (pr-str ref)) {:value ref}))
  (Link. ref))

(defn link? [x] (instance? Link x))

(defn link-ref
  "The \"sha256:<hex>\" string of a link."
  [^Link l]
  (.-ref l))

(defn link-digest
  "The 32 digest bytes a link names."
  ^bytes [^Link l]
  (.parseHex (HexFormat/of) (subs (.-ref l) (count "sha256:"))))

(deftype Bytes [^bytes data]
  Object
  (equals [_ other]
    (and (instance? Bytes other) (Arrays/equals data ^bytes (.-data ^Bytes other))))
  (hashCode [_] (Arrays/hashCode data))
  (toString [_] (str "#b64 \"" (.encodeToString (Base64/getEncoder) data) "\"")))

(defmethod print-method Bytes [^Bytes b ^java.io.Writer w]
  (.write w (str b)))

(defn ->bytes
  "A byte-string value holding a copy of `data`."
  [^bytes data]
  (Bytes. (Arrays/copyOf data (alength data))))

(defn bytes-data
  "A copy of the bytes in a byte-string value."
  ^bytes [^Bytes b]
  (let [d ^bytes (.-data b)] (Arrays/copyOf d (alength d))))

(def readers
  "Tagged-literal readers for clojure.edn, e.g. for reading vector files."
  {'h link
   'b64 (fn [s] (Bytes. (.decode (Base64/getDecoder) ^String s)))})

;; --------------------------------------------------------------- keywords

(def ^:private name-pat "[A-Za-z*+!_?<>=\\-][A-Za-z0-9*+!_?<>=\\-]*")
(def ^:private name-re (re-pattern name-pat))
(def ^:private ns-re (re-pattern (str name-pat "(?:\\." name-pat ")*")))

(defn- valid-keyword? [k]
  (let [n (name k) ns (namespace k)]
    (and (re-matches name-re n)
         (or (nil? ns) (re-matches ns-re ns))
         ;; (keyword "a/b/c") has namespace "a" and name "b/c", which the
         ;; patterns above reject; this guards keywords built some other way.
         (not (str/includes? n "/")))))

;; --------------------------------------------------------------- encoding

(defn- utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

(defn- unpaired-surrogate?
  "Whether `s` has a surrogate that isn't half of a pair, the only way a
  Java string can fail to be valid Unicode."
  [^String s]
  (not (.canEncode (.newEncoder StandardCharsets/UTF_8) s)))

(defn- nfc ^String [^String s] (Normalizer/normalize s Normalizer$Form/NFC))

(defn- control? [c]
  (or (< (int c) 0x20) (= (int c) 0x7f)))

(def ^:private char-escapes
  {\" "\\\"", \\ "\\\\", \newline "\\n", \tab "\\t", \return "\\r"})

(defn- escape-char [c]
  (or (char-escapes c)
      (if (control? c) (format "\\u%04x" (int c)) c)))

(defn- encode-string ^String [^String s]
  (when (unpaired-surrogate? s)
    (fail :encode "string contains an unpaired surrogate" {:value s}))
  (str \" (apply str (map escape-char (nfc s))) \"))

(defn- compare-unsigned [^bytes a ^bytes b]
  (Arrays/compareUnsigned a b))

(defn- check-depth [depth]
  (when (>= depth max-depth)
    (fail :encode (str "nesting deeper than " max-depth) {})))

(declare encode-str)

(defn- encode-map ^String [m depth]
  (let [encode-child #(encode-str % (inc depth))
        entries (->> m
                     (map (fn [[k v]]
                            (let [ek (encode-child k)]
                              {:key-bytes (utf8 ek) :key ek :val (encode-child v)})))
                     (sort-by :key-bytes compare-unsigned))]
    (doseq [[a b] (partition 2 1 entries)
            :when (zero? (compare-unsigned (:key-bytes a) (:key-bytes b)))]
      (fail :encode (str "duplicate map key: " (:key a)) {}))
    (str "{" (str/join " " (map #(str (:key %) " " (:val %)) entries)) "}")))

(defn- encode-str
  "Canonical EDN text for `v`, nested `depth` levels deep."
  ^String [v depth]
  (cond
    (boolean? v) (str v)
    (int? v) (str (long v))
    (string? v) (encode-string v)
    (keyword? v) (if (valid-keyword? v)
                   (str v)
                   (fail :encode (str "invalid keyword: " v) {:value v}))
    (instance? Link v) (str v)
    (instance? Bytes v) (str v)
    (bytes? v) (str (Bytes. v))
    (vector? v) (do (check-depth depth)
                    (str "[" (str/join " " (map #(encode-str % (inc depth)) v)) "]"))
    (and (map? v) (not (record? v))) (do (check-depth depth)
                                         (encode-map v depth))
    :else (fail :encode (str "value not allowed in canonical EDN: " (pr-str v))
                {:value v :class (some-> v class)})))

(defn encode
  "Canonical EDN bytes for `v`. Strings are normalized to NFC. Throws
  ex-info with ::error :encode for values canonical EDN can't hold."
  ^bytes [v]
  (utf8 (encode-str v 0)))

;; --------------------------------------------------------------- decoding

(defn- decode-utf8 ^String [^bytes data]
  (try
    (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))
                  (ByteBuffer/wrap data)))
    (catch CharacterCodingException e
      (fail :decode (str "invalid UTF-8: " (.getMessage e)) {}))))

(defn- delimiter? [c]
  (contains? #{\space \[ \] \{ \} \"} c))

(def ^:private int-re #"-?(?:0|[1-9][0-9]*)")

;; A small hand-written parser. It accepts only canonical forms: anything
;; it lets through that would still re-encode differently is caught by the
;; final comparison in `decode`.

(defn- pfail [i msg]
  (fail :decode (str msg " at offset " i) {:offset i}))

(declare parse-string)

(defn- parse-value [^String s i depth]
  ;; Returns [value next-index].
  (let [n (.length s)
        char-at (fn [j] (when (< j n) (.charAt s j)))
        token (fn [j]
                (loop [k j]
                  (if (and (< k n) (not (delimiter? (.charAt s k))))
                    (recur (inc k))
                    [(subs s j k) k])))
        expect (fn [j c]
                 (if (= (char-at j) c)
                   (inc j)
                   (pfail j (str "expected " (pr-str (str c))))))
        c (char-at i)]
    (cond
      (nil? c) (pfail i "unexpected end of input")

      (= c \[)
      (let [depth (inc depth)]
        (when (> depth max-depth) (pfail i (str "nesting deeper than " max-depth)))
        (if (= (char-at (inc i)) \])
          [[] (+ i 2)]
          (loop [j (inc i) acc (transient [])]
            (let [[v j] (parse-value s j depth)
                  acc (conj! acc v)]
              (if (= (char-at j) \])
                [(persistent! acc) (inc j)]
                (recur (expect j \space) acc))))))

      (= c \{)
      (let [depth (inc depth)]
        (when (> depth max-depth) (pfail i (str "nesting deeper than " max-depth)))
        (if (= (char-at (inc i)) \})
          [{} (+ i 2)]
          (loop [j (inc i) acc (transient {}) last-key nil]
            (let [[k j'] (parse-value s j depth)
                  ek (encode k)]
              (when (and last-key (<= (compare-unsigned ek last-key) 0))
                (pfail j (if (zero? (compare-unsigned ek last-key))
                             "duplicate map key"
                             "map keys out of order")))
              (let [[v j] (parse-value s (expect j' \space) depth)
                    acc (assoc! acc k v)]
                (if (= (char-at j) \})
                  [(persistent! acc) (inc j)]
                  (recur (expect j \space) acc ek)))))))

      (= c \") (parse-string s i)

      (= c \#)
      (let [[tag j] (token (inc i))]
        (when-not (#{"h" "b64"} tag) (pfail i (str "unknown tag #" tag)))
        (let [j (expect j \space)
              _ (when-not (= (char-at j) \") (pfail j "expected a string after the tag"))
              [^String v j] (parse-string s j)]
          (if (= tag "h")
            (if (re-matches link-re v)
              [(Link. v) j]
              (pfail i (str "invalid link " (pr-str v))))
            (let [data (try (.decode (Base64/getDecoder) v)
                            (catch IllegalArgumentException _
                              (pfail i "invalid base64")))]
              (if (= v (.encodeToString (Base64/getEncoder) ^bytes data))
                [(Bytes. data) j]
                (pfail i "non-canonical base64"))))))

      (= c \:)
      (let [[tok j] (token i)
            body (subs tok 1)
            slash (str/index-of body "/")
            k (cond
                (str/blank? body) nil
                (nil? slash) (keyword body)
                (or (zero? slash) (= slash (dec (count body)))) nil
                :else (keyword (subs body 0 slash) (subs body (inc slash))))]
        (if (and k (valid-keyword? k) (= (str k) tok))
          [k j]
          (pfail i (str "invalid keyword " (pr-str tok)))))

      (or (= c \-) (Character/isDigit (char c)))
      (let [[tok j] (token i)]
        (when (or (not (re-matches int-re tok)) (= tok "-0"))
          (pfail i (str "invalid integer " (pr-str tok))))
        (let [v (try (Long/parseLong tok)
                     (catch NumberFormatException _
                       (pfail i (str "integer out of signed 64-bit range: " tok))))]
          [v j]))

      (or (= c \t) (= c \f))
      (let [[tok j] (token i)]
        (case tok
          "true" [true j]
          "false" [false j]
          (pfail i (str "invalid token " (pr-str tok)))))

      :else (pfail i (str "unexpected " (pr-str (str c)))))))

(defn- parse-string [^String s i]
  (let [n (.length s)
        sb (StringBuilder.)]
    (loop [j (inc i)]
      (when (>= j n) (pfail j "unterminated string"))
      (let [c (.charAt s j)]
        (cond
          (= c \")
          (let [v (.toString sb)]
            (when-not (= v (nfc v)) (pfail i "string is not in NFC"))
            [v (inc j)])

          (= c \\)
          (let [e (when (< (inc j) n) (.charAt s (inc j)))]
            (case e
              \" (do (.append sb \") (recur (+ j 2)))
              \\ (do (.append sb \\) (recur (+ j 2)))
              \n (do (.append sb \newline) (recur (+ j 2)))
              \t (do (.append sb \tab) (recur (+ j 2)))
              \r (do (.append sb \return) (recur (+ j 2)))
              \u (let [hex (when (<= (+ j 6) n) (subs s (+ j 2) (+ j 6)))]
                   (when-not (and hex (re-matches #"[0-9a-f]{4}" hex))
                     (pfail j "\\u escape needs 4 lowercase hex digits"))
                   (let [ch (char (Integer/parseInt hex 16))]
                     (when (or (not (control? ch)) (#{\newline \tab \return} ch))
                       (pfail j (str "\\u" hex " is not the canonical form of that character")))
                     (.append sb ch)
                     (recur (+ j 6))))
              (pfail j (str "invalid escape \\" e))))

          (control? c)
          (pfail j (format "unescaped control character U+%04X" (int c)))

          :else (do (.append sb c) (recur (inc j))))))))

(defn decode
  "Decode canonical EDN bytes, rejecting anything non-canonical. Throws
  ex-info with ::error :decode on invalid input."
  [^bytes data]
  (let [s (decode-utf8 data)
        [v i] (parse-value s 0 0)]
    (when (< i (.length s)) (pfail i "trailing input"))
    ;; Belt and braces: the parser should already reject everything whose
    ;; re-encoding differs.
    (when-not (Arrays/equals (encode v) data)
      (fail :decode "input is not the canonical encoding of its value" {}))
    v))

;; ------------------------------------------------------------------ links

(defn links
  "The set of links anywhere in `v`. Needs no schema: links are the only way
  objects refer to each other, so this finds every reference."
  [v]
  (letfn [(walk [acc x]
            (cond
              (link? x) (conj! acc x)
              (map? x) (reduce-kv (fn [acc k v] (walk (walk acc k) v)) acc x)
              (vector? x) (reduce walk acc x)
              :else acc))]
    (persistent! (walk (transient #{}) v))))
