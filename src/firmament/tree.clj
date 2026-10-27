(ns firmament.tree
  "Trees (docs/spec/objects.md): building and checking them, looking paths
  up, path-copying updates, and moving directories in and out of the store."
  (:require [clojure.string :as str]
            [firmament.edn :as edn]
            [firmament.node :as node]
            [firmament.storage :as storage])
  (:import (java.nio.file Files LinkOption OpenOption Path Paths)
           (java.nio.file.attribute FileAttribute)
           (java.text Normalizer Normalizer$Form)))

(set! *warn-on-reflection* true)

(defn- fail [msg data]
  (throw (ex-info msg (assoc data ::error :tree))))

;; ------------------------------------------------------------ validation

(defn valid-name?
  "Whether `s` may name a tree entry: a non-empty NFC string with no `/`,
  and not `.` or `..`."
  [s]
  (and (string? s)
       (not (#{"" "." ".."} s))
       (not (str/includes? s "/"))
       (Normalizer/isNormalized s Normalizer$Form/NFC)))

(defn- check-entry [name entry]
  (when-not (valid-name? name)
    (fail (str "invalid entry name: " (pr-str name)) {:name name}))
  (when-not (map? entry)
    (fail (str "entry " (pr-str name) " is not a map") {:name name :entry entry}))
  (let [{:keys [tags]} entry
        links (dissoc entry :tags)]
    (when (contains? entry :tags)
      (when-not (and (map? tags) (seq tags) (every? qualified-keyword? (keys tags)))
        (fail (str "entry " (pr-str name) " has invalid :tags; they must be a non-empty"
                   " map with namespaced keyword keys")
              {:name name :tags tags})))
    (when (empty? links)
      (fail (str "entry " (pr-str name) " has no links") {:name name}))
    (doseq [[k v] links]
      (when-not (and (keyword? k) (edn/link? v))
        (fail (str "entry " (pr-str name) ": " (pr-str k) " must map to a link")
              {:name name :key k})))))

(defmethod node/validate :tree [t]
  (when-not (and (= #{:type :entries} (set (keys t))) (map? (:entries t)))
    (fail "invalid tree: needs exactly :type :tree and an :entries map" {:value t}))
  (doseq [[name entry] (:entries t)] (check-entry name entry))
  t)

(defn tree
  "A tree object with these entries, a map of names to entry maps."
  [entries]
  (node/validate {:type :tree :entries entries}))

(def empty-tree (tree {}))

;; --------------------------------------------------------------- reading

(defn get-tree
  "The tree object at `link`."
  [r link]
  (node/load-as r link :tree))

(defn- step [r link name]
  (let [entry (get-in (get-tree r link) [:entries name])]
    (when-not entry
      (fail (str "no entry " (pr-str name)) {:name name}))
    entry))

(defn lookup
  "The entry at `path` (a sequence of names) below the tree at `root`, or nil
  if there is none. Every name but the last must be an entry with
  :children."
  [r root path]
  (when (empty? path) (fail "empty path" {}))
  (loop [link root [name & more] path]
    (let [entry (get-in (get-tree r link) [:entries name])]
      (cond
        (nil? entry) nil
        (empty? more) entry
        (:children entry) (recur (:children entry) more)
        :else nil))))

(defn resolve-path
  "The link to the tree at `path` below `root`; `root` itself for an empty
  path. Throws if a name on the path is missing or has no :children."
  [r root path]
  (reduce (fn [link name]
            (or (:children (step r link name))
                (fail (str (pr-str name) " has no :children") {:name name})))
          root path))

;; -------------------------------------------------------------- updating

(defn update-entry!
  "Path-copying update: replace the entry at `path` below the tree at `root`
  with (f entry), where entry is nil if absent. If f returns nil the entry is
  removed. Missing trees along the path are created. Only the trees on the
  path to the root are rewritten. Returns the link to the new root."
  [s root path f]
  (when (empty? path) (fail "empty path" {}))
  (let [t (if root (get-tree s root) empty-tree)
        [name & more] path
        old (get-in t [:entries name])
        new (cond
              (empty? more) (f old)
              (and old (not (:children old)))
              (fail (str (pr-str name) " has no :children, so nothing can go below it")
                    {:name name})
              :else (assoc old :children (update-entry! s (:children old) more f)))
        entries (if new
                  (assoc (:entries t) name new)
                  (dissoc (:entries t) name))]
    (edn/link (storage/put-value! s (tree entries)))))

(defn assoc-entry!
  "Set the entry at `path` to `entry`. Returns the new root link."
  [s root path entry]
  (update-entry! s root path (constantly entry)))

(defn dissoc-entry!
  "Remove the entry at `path`. Returns the new root link."
  [s root path]
  (update-entry! s root path (constantly nil)))

;; ------------------------------------------------------ directories in/out

(def ^:private ^"[Ljava.nio.file.LinkOption;" no-follow
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- ->path ^Path [p]
  (if (instance? Path p) p (Paths/get (str p) (make-array String 0))))

(defn- file-name ^String [^Path p] (str (.getFileName p)))

(declare import-dir!)

(defn- path-entry
  "The tree entry for the file or directory at `p`, storing its contents."
  [s ^Path p]
  (cond
    (Files/isSymbolicLink p)
    (fail (str "symbolic links are not supported: " p) {:path (str p)})

    (Files/isDirectory p no-follow) {:children (import-dir! s p)}

    (Files/isRegularFile p no-follow) {:link (storage/put-file! s (Files/readAllBytes p))}

    :else (fail (str "not a regular file or directory: " p) {:path (str p)})))

(defn import-dir!
  "Store the directory `dir` as a tree, recursively: each file becomes an
  entry {:link <file>} and each subdirectory {:children <tree>}. Names are
  normalized to NFC. Symbolic links and special files are refused. Returns
  the link to the tree."
  [s dir]
  (let [dir (->path dir)]
    (when-not (Files/isDirectory dir no-follow)
      (fail (str "not a directory: " dir) {:path (str dir)}))
    (let [children (with-open [ls (Files/list dir)] (vec (iterator-seq (.iterator ls))))
          entries (reduce
                   (fn [entries p]
                     (let [name (Normalizer/normalize (file-name p) Normalizer$Form/NFC)]
                       (when (contains? entries name)
                         (fail (str "two names in " dir " are the same after NFC: " (pr-str name))
                               {:path (str dir) :name name}))
                       (assoc entries name (path-entry s p))))
                   {} children)]
      (edn/link (storage/put-value! s (tree entries))))))

(defn export!
  "Write the tree at `link` out as directory `dir`, which must not exist or
  be empty. Entries with :children become directories and entries with only
  :link become files; anything else can't be written as a plain directory
  and is refused."
  [r link dir]
  (let [dir (->path dir)]
    (when (and (Files/exists dir no-follow)
               (or (not (Files/isDirectory dir no-follow))
                   (with-open [ls (Files/list dir)] (.isPresent (.findAny ls)))))
      (fail (str dir " exists and is not an empty directory") {:path (str dir)}))
    (Files/createDirectories dir (make-array FileAttribute 0))
    (doseq [[name entry] (:entries (get-tree r link))]
      (let [target (.resolve dir ^String name)
            link-keys (set (keys (dissoc entry :tags)))]
        (case link-keys
          #{:children} (export! r (:children entry) target)
          #{:link} (with-open [out (Files/newOutputStream target (make-array OpenOption 0))]
                     (node/write-file! r (:link entry) out))
          (fail (str "entry " (pr-str name) " has links " (vec (sort link-keys))
                     "; only :link or :children can be written to a directory")
                {:name name :entry entry}))))))

(defn ls
  "The entries of the tree at `path` below `root`, sorted by name, as
  [name entry] pairs."
  [r root path]
  (sort-by key (:entries (get-tree r (resolve-path r root path)))))
