(ns firmament.node
  "Nodes: objects as values, and the operations that depend on their type.

  A node is what an object holds: a byte array for a blob, or the decoded
  value of a structured object. Operations that differ by type are
  multimethods dispatching on `node-type`, which is :blob for blobs and the
  :type key of a structured object's map. Each node type's namespace adds
  its methods: blobs and :chunked files here, :tree in firmament.tree, and
  higher layers their own types."
  (:refer-clojure :exclude [load])
  (:require [firmament.edn :as edn]
            [firmament.storage :as storage])
  (:import (java.io ByteArrayOutputStream OutputStream)))

(set! *warn-on-reflection* true)

(defn- fail [msg data]
  (throw (ex-info msg (assoc data ::error :node))))

(defn node-type
  "The type a node's methods dispatch on: :blob for a blob, the :type of a
  structured object's map, and nil for any other value."
  [node]
  (cond
    (bytes? node) :blob
    (map? node) (:type node)))

;; ------------------------------------------------------------ validation

(defmulti validate
  "Return `node` if it is a valid node of its type, else throw. Values with
  no type of their own (or a type no namespace has methods for) are any
  canonical EDN, so they pass."
  node-type)

(defmethod validate :default [node] node)

(defmethod validate :blob [node] node)

(defn- size? [x] (and (int? x) (not (neg? x))))

(defmethod validate :chunked [{:keys [level size chunks] :as node}]
  (when-not (and (= #{:type :level :size :chunks} (set (keys node)))
                 (size? level)
                 (size? size)
                 (vector? chunks)
                 (seq chunks)
                 (every? #(and (= #{:link :size} (set (keys %)))
                               (edn/link? (:link %))
                               (size? (:size %)))
                         chunks)
                 (= size (transduce (map :size) + chunks)))
    (fail "invalid :chunked node: needs :level, :size and a non-empty :chunks vector of {:link :size} adding up to :size"
          {:node node}))
  node)

(defn load
  "The node at `link`, validated."
  [r link]
  (let [{:keys [kind bytes]} (storage/fetch! r (edn/link-ref link))]
    (validate (if (= :blob kind) bytes (edn/decode bytes)))))

(defn load-as
  "The node at `link`, which must be of type `t`."
  [r link t]
  (let [node (load r link)]
    (when-not (= t (node-type node))
      (fail (str "expected a " t " node at " (edn/link-ref link) ", found " (pr-str (node-type node)))
            {:link link :expected t :actual (node-type node)}))
    node))

;; ------------------------------------------------------------ file contents

(defmulti write-content!
  "Write the file contents `node` stands for to `out`, reading any children
  from `r`. Returns the number of bytes written. Only file nodes (blobs and
  :chunked) have contents."
  (fn [_r node _out] (node-type node)))

(defmethod write-content! :default [_ node _]
  (fail (str "not a file: a " (pr-str (node-type node)) " node has no contents")
        {:type (node-type node)}))

(defmethod write-content! :blob [_ ^bytes node ^OutputStream out]
  (.write out node)
  (alength node))

(defmethod write-content! :chunked [r {:keys [size chunks]} out]
  (let [written (reduce (fn [total {:keys [link] :as chunk}]
                          (let [n (write-content! r (load r link) out)]
                            (when-not (= n (:size chunk))
                              (fail (str "chunk size mismatch for " (edn/link-ref link))
                                    {:chunk link :expected (:size chunk) :actual n}))
                            (+ total n)))
                        0 chunks)]
    (when-not (= written size)
      (fail "file size mismatch" {:expected size :actual written}))
    written))

(defn write-file!
  "Write the contents of the file at `link` (a blob or a :chunked node) to
  `out`, one chunk at a time, checking every size along the way. Returns the
  number of bytes written."
  [r link out]
  (write-content! r (load r link) out))

(defn read-file
  "The contents of the file at `link`, as a byte array."
  ^bytes [r link]
  (let [out (ByteArrayOutputStream.)]
    (write-file! r link out)
    (.toByteArray out)))
