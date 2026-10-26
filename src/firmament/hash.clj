(ns firmament.hash
  "Object hashing (docs/spec/objects.md)."
  (:require [firmament.edn :as edn])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util HexFormat)))

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

(defn blob-link [^bytes data] (edn/link (blob-hash data)))

(defn edn-link [v] (edn/link (edn-hash v)))
