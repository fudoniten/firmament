(ns firmament.hash
  "Object hashing (docs/spec/objects.md)."
  (:require [firmament.edn :as edn])
  (:import (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Arrays HexFormat)))

(set! *warn-on-reflection* true)

(def ^:private blob-prefix (.getBytes "blob\n" StandardCharsets/US_ASCII))
(def ^:private edn-prefix (.getBytes "edn\n" StandardCharsets/US_ASCII))

(defn- sha256-ref [^bytes prefix ^bytes payload]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md prefix)
    (.update md payload)
    (str "sha256:" (.formatHex (HexFormat/of) (.digest md)))))

(defn blob-hash
  "The \"sha256:<hex>\" name of a blob."
  [^bytes data]
  (sha256-ref blob-prefix data))

(defn edn-hash-bytes
  "The name of a structured object, given its canonical bytes."
  [^bytes canonical]
  (sha256-ref edn-prefix canonical))

(defn edn-hash
  "The name of a structured object, given its value."
  [v]
  (edn-hash-bytes (edn/encode v)))

(defn object-hash
  "The name of an object of `kind`, :blob or :edn, from its payload: a
  blob's bytes or a structured object's canonical bytes."
  [kind ^bytes payload]
  (case kind
    :blob (blob-hash payload)
    :edn (edn-hash-bytes payload)))

(defn blob-link [^bytes data] (edn/link (blob-hash data)))

(defn edn-link [v] (edn/link (edn-hash v)))

;; ---------------------------------------------------------- object files

(defn object-bytes
  "The full hash input of an object: its kind's prefix, then the payload.
  This is what an object file holds (docs/spec/store.md), so its SHA-256 is
  the object's name."
  ^bytes [kind ^bytes payload]
  (let [^bytes prefix (case kind :blob blob-prefix :edn edn-prefix)]
    (.array (doto (ByteBuffer/allocate (+ (alength prefix) (alength payload)))
              (.put prefix)
              (.put payload)))))

(defn- starts-with? [^bytes data ^bytes prefix]
  (and (<= (alength prefix) (alength data))
       (Arrays/equals data 0 (alength prefix) prefix 0 (alength prefix))))

(defn parse-object
  "Split the full hash input `data` into {:kind :bytes}, checking that it
  hashes to `ref`; nil if it doesn't, or if it has no known prefix."
  [ref ^bytes data]
  (when-let [[kind ^bytes prefix] (cond (starts-with? data blob-prefix) [:blob blob-prefix]
                                        (starts-with? data edn-prefix) [:edn edn-prefix])]
    (let [payload (Arrays/copyOfRange data (alength prefix) (alength data))]
      (when (= ref (object-hash kind payload))
        {:kind kind :bytes payload}))))

