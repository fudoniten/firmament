(ns firmament.hash
  "Object hashing (docs/spec/objects.md).

  The *-hash functions give an object's name as text, \"sha256:<hex>\",
  the form the spec and the golden vectors use. Everything above this
  namespace names objects with links (firmament.edn/Link), which the
  *-link functions give."
  (:require [firmament.edn :as edn])
  (:import (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Arrays HexFormat)))

(set! *warn-on-reflection* true)

(def format-version
  "The format version this code reads and writes (docs/spec/objects.md,
  Format versions). Stores record it, and refuse any other."
  "firmament-0")

(def ^:private prefixes
  "Each kind of object, with the bytes its hash input starts with."
  {:blob (.getBytes "blob\n" StandardCharsets/US_ASCII)
   :edn (.getBytes "edn\n" StandardCharsets/US_ASCII)})

(defn- prefix ^bytes [kind]
  (or (prefixes kind) (throw (IllegalArgumentException. (str "unknown object kind: " kind)))))

(defn object-hash
  "The \"sha256:<hex>\" name of an object of `kind`, :blob or :edn, from
  its payload: a blob's bytes or a structured object's canonical bytes."
  [kind ^bytes payload]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md (prefix kind))
    (.update md payload)
    (str "sha256:" (.formatHex (HexFormat/of) (.digest md)))))

(defn blob-hash
  "The \"sha256:<hex>\" name of a blob."
  [^bytes data]
  (object-hash :blob data))

(defn edn-hash-bytes
  "The name of a structured object, given its canonical bytes."
  [^bytes canonical]
  (object-hash :edn canonical))

(defn edn-hash
  "The name of a structured object, given its value."
  [v]
  (edn-hash-bytes (edn/encode v)))

(defn object-link
  "The link to an object of `kind` with this payload."
  [kind ^bytes payload]
  (edn/link (object-hash kind payload)))

(defn blob-link [^bytes data] (object-link :blob data))

(defn edn-link [v] (object-link :edn (edn/encode v)))

;; ---------------------------------------------------------- object files

(defn object-path
  "Where object `link` lives below a store's root, on disk or over HTTP:
  objects/ab/cdef… for sha256:abcdef…."
  [link]
  (let [hex (edn/link-hex link)]
    (str "objects/" (subs hex 0 2) "/" (subs hex 2))))

(defn object-bytes
  "The full hash input of an object: its kind's prefix, then the payload.
  This is what an object file holds (docs/spec/store.md), so its SHA-256 is
  the object's name."
  ^bytes [kind ^bytes payload]
  (let [p (prefix kind)]
    (.array (doto (ByteBuffer/allocate (+ (alength p) (alength payload)))
              (.put p)
              (.put payload)))))

(defn- starts-with? [^bytes data ^bytes prefix]
  (and (<= (alength prefix) (alength data))
       (Arrays/equals data 0 (alength prefix) prefix 0 (alength prefix))))

(defn parse-object
  "Split the full hash input `data` into {:kind :bytes}, checking that it
  hashes to `link`; nil if it doesn't, or if it has no known prefix."
  [link ^bytes data]
  (some (fn [[kind ^bytes p]]
          (when (starts-with? data p)
            (let [payload (Arrays/copyOfRange data (alength p) (alength data))]
              (when (= (edn/link-ref link) (object-hash kind payload))
                {:kind kind :bytes payload}))))
        prefixes))
