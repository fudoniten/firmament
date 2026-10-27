(ns firmament.store
  "The local content store (docs/spec/store.md): objects on disk keyed by
  hash, with local metadata kept beside them and never hashed. Reading and
  writing objects goes through the protocols in firmament.storage."
  (:require [clojure.edn]
            [clojure.set :as set]
            [firmament.edn :as edn]
            [firmament.hash :as hash]
            [firmament.storage :as storage])
  (:import (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)
           (java.nio.file AtomicMoveNotSupportedException CopyOption Files
                          LinkOption OpenOption Path Paths StandardCopyOption)
           (java.nio.file.attribute FileAttribute)
           (java.security MessageDigest)
           (java.util Arrays HexFormat)))

(set! *warn-on-reflection* true)

(def format-version "firmament-0")

(def ^:private ^"[Ljava.nio.file.OpenOption;" no-options (make-array OpenOption 0))

(def ^:private kinds {:blob "blob\n" :edn "edn\n"})

(def ^:private prefix->kind (set/map-invert kinds))

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
            nl (or (some #(when (= 10 (aget data %)) %) (range (min 8 (alength data)))) -1)
            kind (when (pos? nl) (prefix->kind (utf8-str (Arrays/copyOf data (int (inc nl))))))]
        (when-not (and kind (= ref (sha256-ref data)))
          (fail (str "corrupt object: " ref) {:ref ref :path (str p)}))
        {:kind kind :bytes (Arrays/copyOfRange data (int (inc nl)) (alength data))}))))

(defn- write-object!
  "Write the object file for `payload`, unless the store already has it."
  [^Path root kind ^bytes payload]
  (let [ref (hash/object-hash kind payload)
        target (object-path root ref)]
    (when-not (exists? target)
      (let [prefix (ascii (kinds kind))]
        (write-atomically! root target
                           (.array (doto (ByteBuffer/allocate (+ (alength prefix) (alength payload)))
                                     (.put prefix)
                                     (.put payload))))))
    ref))

(defrecord LocalStore [^Path root]
  storage/Resolver
  (fetch [_ ref] (read-object root ref))
  (has? [_ ref] (exists? (object-path root ref)))

  storage/Store
  (put-object! [_ kind payload] (write-object! root kind payload)))

(defn default-dir
  "Where the store is when no directory is given: $FIRMAMENT_STORE, else
  ~/.firmament/store. `env` is a map of environment variables."
  ([] (default-dir (System/getenv)))
  ([env]
   (or (not-empty (get env "FIRMAMENT_STORE"))
       (str (System/getProperty "user.home") "/.firmament/store"))))

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
    (->LocalStore root)))

;; --------------------------------------------------------------- metadata

(defn object-meta
  "The local metadata map for `ref`, such as {:pinned? true}; {} if none."
  [s ref]
  (let [p (meta-path (:root s) ref)]
    (if (exists? p)
      (clojure.edn/read-string {:readers edn/readers} (utf8-str (Files/readAllBytes p)))
      {})))

(defn update-meta!
  "Set the local metadata for `ref` to (apply f current-meta args). The
  object must be in the store. Returns the new map. Not safe against
  concurrent updates to the same object."
  [s ref f & args]
  (when-not (storage/has? s ref)
    (fail (str "object not found: " ref) {:ref ref}))
  (let [m (apply f (object-meta s ref) args)]
    (when-not (map? m)
      (fail "metadata must be a map" {:ref ref :value m}))
    (write-atomically! (:root s) (meta-path (:root s) ref)
                       (.getBytes (pr-str m) StandardCharsets/UTF_8))
    m))
