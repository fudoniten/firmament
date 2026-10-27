(ns firmament.storage
  "Protocols for reading and writing objects by hash, and the operations
  every store gets from them. The local store (firmament.store) and the
  in-memory store here are the implementations so far."
  (:require [firmament.chunk :as chunk]
            [firmament.edn :as edn]
            [firmament.hash :as hash]))

(set! *warn-on-reflection* true)

(defprotocol Resolver
  (fetch [r ref]
    "The object named `ref` (a \"sha256:<hex>\" string) as
    {:kind :blob|:edn, :bytes payload}, or nil when this resolver doesn't
    have it. The payload is the raw bytes of a blob, or the canonical EDN of
    a structured object. A resolver only returns objects whose hash it has
    checked.")
  (has? [r ref]
    "Whether this resolver has the object named `ref`."))

(defprotocol Store
  (put-object! [s kind payload]
    "Store `payload` as an object of `kind` (:blob or :edn, the payload
    being canonical EDN) and return its \"sha256:<hex>\" name. The store
    computes the name itself. Storing an object it already has is a
    no-op."))

(defn- fail [msg data]
  (throw (ex-info msg (assoc data ::error :storage))))

;; ----------------------------------------------------------------- reading

(defn fetch!
  "Like fetch, but throws when the object is missing."
  [r ref]
  (or (fetch r ref) (fail (str "object not found: " ref) {:ref ref})))

(defn fetch-value
  "The decoded value of the structured object named `ref`."
  [r ref]
  (let [{:keys [kind bytes]} (fetch! r ref)]
    (when-not (= :edn kind)
      (fail (str "not a structured object: " ref) {:ref ref :kind kind}))
    (edn/decode bytes)))

;; ----------------------------------------------------------------- writing

(defn put-blob!
  "Store `data` as a single blob. Returns its name."
  [s ^bytes data]
  (put-object! s :blob data))

(defn put-value!
  "Store the canonical encoding of `v` as a structured object. Returns its
  name."
  [s v]
  (put-object! s :edn (edn/encode v)))

(defn put-file!
  "Store file contents, as a single blob or chunked (docs/spec/chunking.md).
  Returns the link to the file: its blob or its root :chunked node.

  The whole file is held in memory for now."
  [s ^bytes data]
  (let [{:keys [root blobs index]} (chunk/store-file data)]
    (doseq [b (vals blobs)] (put-blob! s b))
    ;; Children before parents, so a store never holds a node whose children
    ;; are missing, even after a crash part way through.
    (doseq [canonical (sort-by (comp :level edn/decode) (vals (:objects index)))]
      (put-object! s :edn canonical))
    root))

;; ------------------------------------------------------------ memory store

(defrecord MemoryStore [objects]
  Resolver
  (fetch [_ ref] (some-> (get @objects ref) (update :bytes #(aclone ^bytes %))))
  (has? [_ ref] (contains? @objects ref))

  Store
  (put-object! [_ kind payload]
    (let [ref (hash/object-hash kind payload)]
      (swap! objects assoc ref {:kind kind :bytes (aclone ^bytes payload)})
      ref)))

(defn memory-store
  "An empty store that keeps objects in memory, for tests and scratch work."
  []
  (->MemoryStore (atom {})))
