(ns firmament.store
  "The local content store (docs/spec/store.md): objects on disk keyed by
  hash, with local metadata kept beside them and never hashed. Reading and
  writing objects goes through the protocols in firmament.storage."
  (:require [firmament.edn :as edn]
            [firmament.error :as error]
            [firmament.fs :as fs]
            [firmament.hash :as hash]
            [firmament.storage :as storage])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Path)))

(set! *warn-on-reflection* true)

(def format-version
  "The format version a local store records. See hash/format-version."
  hash/format-version)

(def ^:private fail (partial error/fail :store))

(defn- object-path ^Path [root link]
  (fs/path root (hash/object-path link)))

(defn- meta-path ^Path [root link]
  (let [h (edn/link-hex link)]
    (fs/path root "meta" (subs h 0 2) (str (subs h 2) ".edn"))))

(defn- read-object
  "Read and check the object file for `link`: its bytes are the full hash
  input, so their SHA-256 must be what `link` names."
  [root link]
  (let [p (object-path root link)]
    (when (fs/exists? p)
      (or (hash/parse-object link (fs/read-bytes p))
          (fail (str "corrupt object: " (edn/link-ref link)) {:link link :path (str p)})))))

(defn- write-object!
  "Write the object file for `payload`, unless the store already has it."
  [root kind ^bytes payload]
  (let [link (hash/object-link kind payload)
        target (object-path root link)]
    (when-not (fs/exists? target)
      (fs/write-atomically! (fs/path root "tmp") target (hash/object-bytes kind payload)))
    link))

(defrecord LocalStore [^Path root]
  storage/Resolver
  (fetch [_ link] (read-object root link))
  (has? [_ link] (fs/exists? (object-path root link)))

  storage/Store
  (put-object! [_ kind payload] (write-object! root kind payload)))

(defn jar-dir
  "Where a local store keeps jars written out for loading (cache/jars),
  named by hash. Like metadata it is local and never hashed, and can be
  deleted at any time."
  ^Path [^LocalStore s]
  (fs/path (:root s) "cache" "jars"))

(defn object-file
  "The path of the file that holds object `link` in local store `s`,
  whether or not the store has it."
  ^Path [^LocalStore s link]
  (object-path (:root s) link))

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
  (let [root (fs/absolute dir)
        fmt (fs/path root "format")]
    (cond
      (fs/exists? fmt)
      (let [v (.strip (fs/read-string-utf8 fmt))]
        (when-not (= format-version v)
          (fail (str "store at " root " holds format " v ", not " format-version)
                {:root (str root) :format v})))

      (and (fs/exists? root) (not (fs/empty-dir? root)))
      (fail (str root " is not empty and is not a firmament store") {:root (str root)})

      :else
      (do (fs/mkdirs root)
          (fs/write-bytes! fmt (.getBytes (str format-version "\n") StandardCharsets/US_ASCII))))
    (run! #(fs/mkdirs (fs/path root %)) ["objects" "meta" "tmp"])
    (->LocalStore root)))

;; --------------------------------------------------------------- metadata

(defn object-meta
  "The local metadata map for `link` in local store `s`, such as
  {:pinned? true}; {} if none."
  [^LocalStore s link]
  (fs/read-edn (meta-path (:root s) link) {:readers edn/readers} {}))

(defn update-meta!
  "Set the local metadata for `link` to (apply f current-meta args). The
  object must be in the store. Returns the new map. Not safe against
  concurrent updates to the same object."
  [^LocalStore s link f & args]
  (when-not (storage/has? s link)
    (fail (str "object not found: " (edn/link-ref link)) {:link link}))
  (let [m (apply f (object-meta s link) args)]
    (when-not (map? m)
      (fail "metadata must be a map" {:link link :value m}))
    (fs/write-atomically! (fs/path (:root s) "tmp") (meta-path (:root s) link)
                          (.getBytes (pr-str m) StandardCharsets/UTF_8))
    m))
