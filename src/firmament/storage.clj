(ns firmament.storage
  "Protocols for reading and writing objects by hash, and the operations
  every store gets from them. The local store (firmament.store), the HTTP
  store (firmament.http) and the in-memory store here are the
  implementations so far.

  Objects are named by links (firmament.edn/Link) throughout."
  (:require [firmament.chunk :as chunk]
            [firmament.edn :as edn]
            [firmament.error :refer [fail]]
            [firmament.hash :as hash]))

(set! *warn-on-reflection* true)

(defprotocol Resolver
  (fetch [r link]
    "The object `link` names as {:kind :blob|:edn, :bytes payload}, or nil
    when this resolver doesn't have it. The payload is the raw bytes of a
    blob, or the canonical EDN of a structured object. A resolver only
    returns objects whose hash it has checked.")
  (has? [r link]
    "Whether this resolver has the object `link` names."))

(defprotocol Store
  (put-object! [s kind payload]
    "Store `payload` as an object of `kind` (:blob or :edn, the payload
    being canonical EDN) and return the link to it. The store computes the
    name itself. Storing an object it already has is a no-op."))

;; ----------------------------------------------------------------- reading

(defn fetch!
  "Like fetch, but throws when the object is missing."
  [r link]
  (or (fetch r link) (fail :storage (str "object not found: " (edn/link-ref link)) {:link link})))

(defn fetch-value
  "The decoded value of the structured object `link` names."
  [r link]
  (let [{:keys [kind bytes]} (fetch! r link)]
    (when-not (= :edn kind)
      (fail :storage (str "not a structured object: " (edn/link-ref link)) {:link link :kind kind}))
    (edn/decode bytes)))

(defn object-links
  "The links in an object, as fetch returns it: none for a blob."
  [{:keys [kind bytes]}]
  (if (= :edn kind) (edn/links (edn/decode bytes)) #{}))

;; ----------------------------------------------------------------- writing

(defn put-blob!
  "Store `data` as a single blob. Returns the link to it."
  [s ^bytes data]
  (put-object! s :blob data))

(defn put-value!
  "Store the canonical encoding of `v` as a structured object. Returns the
  link to it."
  [s v]
  (put-object! s :edn (edn/encode v)))

(defn put-file!
  "Store file contents, as a single blob or chunked (docs/spec/chunking.md).
  Returns the link to the file: its blob or its root :chunked node.

  The whole file is held in memory for now."
  [s ^bytes data]
  (let [{:keys [root blobs index]} (chunk/store-file data)]
    (run! #(put-blob! s %) (vals blobs))
    ;; Index nodes come lowest level first, so a store never holds a node
    ;; whose children are missing, even after a crash part way through.
    (run! (fn [[_ canonical]] (put-object! s :edn canonical)) (:objects index))
    root))

;; ------------------------------------------------------------ memory store

(defrecord MemoryStore [objects]
  Resolver
  (fetch [_ link] (some-> (get @objects link) (update :bytes #(aclone ^bytes %))))
  (has? [_ link] (contains? @objects link))

  Store
  (put-object! [_ kind payload]
    (let [link (hash/object-link kind payload)]
      (swap! objects assoc link {:kind kind :bytes (aclone ^bytes payload)})
      link)))

(defn memory-store
  "An empty store that keeps objects in memory, for tests and scratch work."
  []
  (->MemoryStore (atom {})))

;; ----------------------------------------------------------------- caching

(defrecord Cache [local remote]
  Resolver
  (fetch [_ link]
    (or (fetch local link)
        (when-let [{:keys [kind bytes] :as object} (fetch remote link)]
          (let [got (hash/object-link kind bytes)]
            (when-not (= link got)
              (fail :storage (str "asked for " (edn/link-ref link) " but got " (edn/link-ref got))
                    {:link link :got got})))
          (put-object! local kind bytes)
          object)))
  (has? [_ link] (or (has? local link) (has? remote link)))

  Store
  (put-object! [_ kind payload] (put-object! local kind payload)))

(defn cache
  "A store that reads from `local`, then `remote`, copying what it gets from
  `remote` into `local`. Copies are checked against their hash, so a
  remote can't substitute one object for another. Writes go to `local`."
  [local remote]
  (->Cache local remote))

;; ------------------------------------------------------------------ graphs

(defn reduce-graph
  "Reduce `f` over every node reachable from `roots`, each once, children
  before parents. `children` gives a node's children; returning nil stops
  the walk below that node, though the node itself is still visited. Uses
  an explicit stack, so deep graphs are fine."
  [children f init roots]
  ;; Each frame is [node remaining-children]; a node goes to `f` once all
  ;; of its children have.
  (loop [stack (list) todo (seq roots) seen #{} acc init]
    (cond
      (seq stack)
      (let [[node kids] (peek stack)]
        (if-let [[k & more] kids]
          (let [stack (conj (pop stack) [node more])]
            (if (seen k)
              (recur stack todo seen acc)
              (recur (conj stack [k (seq (children k))]) todo (conj seen k) acc)))
          (recur (pop stack) todo seen (f acc node))))

      todo
      (let [[r & more] todo]
        (if (seen r)
          (recur stack more seen acc)
          (recur (conj stack [r (seq (children r))]) more (conj seen r) acc)))

      :else acc)))

(defn walk
  "Every node reachable from `roots`, each once, children before parents,
  as a vector. See reduce-graph."
  [children roots]
  (persistent! (reduce-graph children conj! (transient []) roots)))

(defn fetch-all!
  "Fetch the object `link` names and everything it links to, recursively,
  each object once. Through a `cache`, this copies the whole graph into the
  local store. Returns the set of links fetched."
  [r link]
  (set (walk #(object-links (fetch! r %)) [link])))

(defn copy!
  "Copy object `link` and everything it links to from `from` into `to`,
  children before parents, so `to` never holds an object whose links are
  missing. Below an object `to` already has, nothing is checked or sent:
  every store keeps that same rule, so `to` has its whole graph too.
  Returns the number of objects copied."
  [from to link]
  ;; Objects are held from when they're fetched until they're written, so
  ;; only the ones on the walk's current path are in memory.
  (let [pending (volatile! {})]
    (reduce-graph (fn [l]
                    (when-not (has? to l)
                      (let [o (fetch! from l)]
                        (vswap! pending assoc l o)
                        (object-links o))))
                  (fn [n l]
                    (if-let [{:keys [kind bytes]} (@pending l)]
                      (do (put-object! to kind bytes)
                          (vswap! pending dissoc l)
                          (inc n))
                      n))
                  0 [link])))
