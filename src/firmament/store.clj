(ns firmament.store
  "The local content store (docs/spec/store.md): objects on disk keyed by
  hash, with local metadata kept beside them and never hashed."
  (:require [clojure.edn]
            [firmament.chunk :as chunk]
            [firmament.edn :as edn]
            [firmament.hash :as hash]
            [firmament.resolver :as resolver])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file AtomicMoveNotSupportedException CopyOption Files
                          LinkOption OpenOption Path Paths StandardCopyOption)
           (java.nio.file.attribute FileAttribute)
           (java.security MessageDigest)
           (java.util Arrays HexFormat)))

(set! *warn-on-reflection* true)

(def format-version "firmament-0")

(def ^:private ^"[Ljava.nio.file.OpenOption;" no-options (make-array OpenOption 0))

(def ^:private kinds {:blob "blob\n" :edn "edn\n"})

(defn- fail [msg data]
  (throw (ex-info msg (assoc data ::error :store))))

(defn- path ^Path [^Path root & parts]
  (reduce (fn [^Path p ^String part] (.resolve p part)) root parts))

(defn- exists? [^Path p] (Files/exists p (make-array LinkOption 0)))

(defn- mkdirs [^Path p] (Files/createDirectories p (make-array FileAttribute 0)))

(defn- ascii ^bytes [^String s] (.getBytes s StandardCharsets/US_ASCII))

(defn- utf8-str ^String [^bytes b] (String. b StandardCharsets/UTF_8))

(defn- hex-of [ref]
  (edn/link ref) ; validates
  (subs ref (count "sha256:")))

(defn- object-path ^Path [^Path root ref]
  (let [h (hex-of ref)]
    (path root "objects" (subs h 0 2) (subs h 2))))

(defn- meta-path ^Path [^Path root ref]
  (let [h (hex-of ref)]
    (path root "meta" (subs h 0 2) (str (subs h 2) ".edn"))))

(defn- write-atomically!
  "Write `data` to `target` via a temporary file, so readers never see a
  partial file."
  [^Path root ^Path target ^bytes data]
  (mkdirs (.getParent target))
  (let [tmp (Files/createTempFile (path root "tmp") "obj" ".tmp" (make-array FileAttribute 0))]
    (try
      (Files/write tmp data no-options)
      (try
        (Files/move tmp target (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                                        StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException _
          (Files/move tmp target (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
      (finally (Files/deleteIfExists tmp)))))

(defn- sha256-ref [^bytes data]
  (str "sha256:" (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") data))))

(defn- read-object
  "Read and check the object file for `ref`: its bytes are the full hash
  input, so their SHA-256 must be `ref`."
  [^Path root ref]
  (let [p (object-path root ref)]
    (when (exists? p)
      (let [data (Files/readAllBytes p)
            nl (loop [i 0] (cond (= i (alength data)) -1
                                 (= 10 (aget data i)) i
                                 :else (recur (inc i))))
            prefix (when (pos? nl) (utf8-str (Arrays/copyOf data (int (inc nl)))))
            kind (some (fn [[k s]] (when (= s prefix) k)) kinds)]
        (when-not (and kind (= ref (sha256-ref data)))
          (fail (str "corrupt object: " ref) {:ref ref :path (str p)}))
        {:kind kind :bytes (Arrays/copyOfRange data (int (inc nl)) (alength data))}))))

(deftype LocalStore [^Path root]
  resolver/Resolver
  (fetch [_ ref] (read-object root ref))
  (has? [_ ref] (exists? (object-path root ref)))

  Object
  (toString [_] (str "#<LocalStore " root ">")))

(defn root
  "The directory a store lives in."
  ^Path [^LocalStore s]
  (.-root s))

(defn open
  "Open the store in directory `dir`, creating it if it doesn't exist or is
  empty. Throws if `dir` holds something else, or a store for another
  format version."
  [dir]
  (let [root (.toAbsolutePath (Paths/get (str dir) (make-array String 0)))
        fmt (path root "format")]
    (cond
      (exists? fmt)
      (let [v (.strip (utf8-str (Files/readAllBytes fmt)))]
        (when-not (= format-version v)
          (fail (str "store at " root " holds format " v ", not " format-version)
                {:root (str root) :format v})))

      (and (exists? root)
           (with-open [entries (Files/list root)] (.isPresent (.findAny entries))))
      (fail (str root " is not empty and is not a firmament store") {:root (str root)})

      :else
      (do (mkdirs root)
          (Files/write fmt (ascii (str format-version "\n")) no-options)))
    (doseq [d ["objects" "meta" "tmp"]] (mkdirs (path root d)))
    (LocalStore. root)))

(defn- put-raw!
  "Store an object of `kind` whose hash `ref` the caller has computed from
  `payload`."
  [^LocalStore s kind ref ^bytes payload]
  (let [target (object-path (root s) ref)]
    (when-not (exists? target)
      (let [prefix (ascii (kinds kind))
            data (byte-array (+ (alength prefix) (alength payload)))]
        (System/arraycopy prefix 0 data 0 (alength prefix))
        (System/arraycopy payload 0 data (alength prefix) (alength payload))
        (write-atomically! (root s) target data)))
    ref))

(defn put-blob!
  "Store `data` as a single blob. Returns its \"sha256:<hex>\" name."
  [s ^bytes data]
  (put-raw! s :blob (hash/blob-hash data) data))

(defn put-value!
  "Store the canonical encoding of `v` as a structured object. Returns its
  name."
  [s v]
  (let [canonical (edn/encode v)]
    (put-raw! s :edn (hash/edn-hash-bytes canonical) canonical)))

(defn put-file!
  "Store file contents, as a single blob or chunked (docs/spec/chunking.md).
  Returns the link to the file: its blob or its root :chunked node.

  The whole file is held in memory for now."
  [s ^bytes data]
  (let [{:keys [root blobs index]} (chunk/store-file data)]
    (doseq [[ref b] blobs] (put-raw! s :blob ref b))
    ;; Children before parents, so a store never holds a node whose children
    ;; are missing, even after a crash part way through.
    (doseq [[ref canonical] (sort-by (comp :level edn/decode val) (:objects index))]
      (put-raw! s :edn ref canonical))
    root))

(defn get-object
  "The object named `ref` as {:kind :blob|:edn, :bytes payload}, or nil."
  [s ref]
  (resolver/fetch s ref))

(defn has? [s ref] (resolver/has? s ref))

;; --------------------------------------------------------------- metadata

(defn object-meta
  "The local metadata map for `ref`, such as {:pinned? true}; {} if none."
  [^LocalStore s ref]
  (let [p (meta-path (root s) ref)]
    (if (exists? p)
      (clojure.edn/read-string {:readers edn/readers} (utf8-str (Files/readAllBytes p)))
      {})))

(defn update-meta!
  "Set the local metadata for `ref` to (apply f current-meta args). The
  object must be in the store. Returns the new map. Not safe against
  concurrent updates to the same object."
  [^LocalStore s ref f & args]
  (when-not (has? s ref)
    (fail (str "object not found: " ref) {:ref ref}))
  (let [m (apply f (object-meta s ref) args)]
    (when-not (map? m)
      (fail "metadata must be a map" {:ref ref :value m}))
    (write-atomically! (root s) (meta-path (root s) ref)
                       (.getBytes (pr-str m) StandardCharsets/UTF_8))
    m))
