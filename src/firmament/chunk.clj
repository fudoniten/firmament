(ns firmament.chunk
  "Chunker and chunk index (docs/spec/chunking.md)."
  (:require [firmament.edn :as edn]
            [firmament.hash :as hash])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Arrays)))

(set! *warn-on-reflection* true)

(def ^:const min-size 16384)
(def ^:const avg-size 65536)
(def ^:const max-size 262144)

;; Top 18 and top 14 bits set: 0xFFFFC00000000000 and 0xFFFC000000000000.
(def ^:const mask-s (bit-shift-left -1 46))
(def ^:const mask-l (bit-shift-left -1 50))

(def ^:const index-boundary-mask 0x3FF)
(def ^:const index-cap 4096)

(def ^:private ^"[J" gear-table
  (let [seed (.getBytes "firmament-gear-v0" StandardCharsets/US_ASCII)
        table (long-array 256)]
    (dotimes [i 256]
      (let [md (MessageDigest/getInstance "SHA-256")]
        (.update md seed)
        (.update md (unchecked-byte i))
        (aset table i (.getLong (java.nio.ByteBuffer/wrap (.digest md))))))
    table))

(defn gear
  "GEAR[i] as a signed long holding the unsigned 64-bit value's bits."
  ^long [i]
  (aget gear-table (int i)))

(defn cut
  "End offset of the chunk of `data` that starts at `start`. Arithmetic on
  longs wraps, which is exactly the spec's unsigned 64-bit arithmetic."
  ^long [^bytes data ^long start]
  (let [n (- (alength data) start)]
    (if (<= n min-size)
      (+ start n)
      (let [end (+ start (min n max-size))
            normal (+ start (min n avg-size))
            ^longs g gear-table]
        (loop [i (+ start min-size) fp 0]
          (if (>= i end)
            end
            (let [fp (unchecked-add (bit-shift-left fp 1)
                                    (aget g (bit-and (aget data i) 0xFF)))
                  i (inc i)
                  mask (if (<= i normal) mask-s mask-l)]
              (if (zero? (bit-and fp mask))
                i
                (recur i fp)))))))))

(defn cut-points
  "End offsets of every chunk of `data`, in order."
  [^bytes data]
  (loop [start 0 points (transient [])]
    (if (< start (alength data))
      (let [end (cut data start)]
        (recur end (conj! points end)))
      (persistent! points))))

(defn boundary?
  "Whether an index node ends after an entry linking to `link`."
  [link]
  (let [d (edn/link-digest link)
        top (bit-or (bit-shift-left (bit-and (aget d 0) 0xFF) 8)
                    (bit-and (aget d 1) 0xFF))]
    (zero? (bit-and top index-boundary-mask))))

(defn chunked-node
  "The :chunked object for one index node; entries are [link size] pairs."
  [level entries]
  {:type :chunked
   :level level
   :size (reduce + (map second entries))
   :chunks (mapv (fn [[link size]] {:link link :size size}) entries)})

(defn group-entries
  "Split one level's entries into the lists that become its nodes."
  [entries]
  (let [last-i (dec (count entries))]
    (loop [i 0 entries (seq entries) group [] groups []]
      (if-let [[e & more] entries]
        (let [group (conj group e)]
          (if (or (boundary? (first e)) (= index-cap (count group)) (= i last-i))
            (recur (inc i) more [] (conj groups group))
            (recur (inc i) more group groups)))
        groups))))

(defn build-index
  "Build the chunk index over level-0 entries, [link size] pairs. Returns

    :root     link to the root :chunked node
    :levels   per level, the number of entries in each node
    :objects  {ref canonical-bytes} for every index node"
  [entries]
  (when (empty? entries)
    (throw (ex-info "a chunk index needs at least one entry" {})))
  (loop [level 0 entries entries levels [] objects {}]
    (let [groups (group-entries entries)
          nodes (map (fn [g]
                       (let [canonical (edn/encode (chunked-node level g))
                             ref (hash/edn-hash-bytes canonical)]
                         {:ref ref
                          :canonical canonical
                          :entry [(edn/link ref) (reduce + (map second g))]}))
                     groups)
          levels (conj levels (mapv count groups))
          objects (into objects (map (juxt :ref :canonical)) nodes)]
      (if (= 1 (count groups))
        {:root (first (:entry (first nodes))) :levels levels :objects objects}
        (recur (inc level) (mapv :entry nodes) levels objects)))))

(defn store-file
  "Turn file bytes into a single blob or a chunked file. Returns

    :root      link to the file: its blob, or its root :chunked node
    :chunked?  whether it was chunked
    :cuts      chunk end offsets (empty for a single blob)
    :index     the build-index result, when chunked
    :blobs     {ref bytes} for the file's blobs"
  [^bytes data]
  (if (<= (alength data) max-size)
    (let [ref (hash/blob-hash data)]
      {:root (edn/link ref) :chunked? false :cuts [] :blobs {ref data}})
    (let [cuts (cut-points data)
          chunks (map (fn [start end] (Arrays/copyOfRange data (int start) (int end)))
                      (cons 0 cuts) cuts)
          refs (mapv hash/blob-hash chunks)
          index (build-index (mapv (fn [ref ^bytes c] [(edn/link ref) (alength c)])
                                   refs chunks))]
      {:root (:root index)
       :chunked? true
       :cuts cuts
       :index index
       :blobs (zipmap refs chunks)})))
