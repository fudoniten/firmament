(ns firmament.fs
  "Small wrappers over java.nio.file for the places Firmament touches the
  filesystem: the local store, directory import and export, jar caching and
  the importer."
  (:refer-clojure :exclude [list])
  (:require [clojure.edn])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file AtomicMoveNotSupportedException CopyOption Files
                          FileVisitOption LinkOption OpenOption Path Paths
                          StandardCopyOption)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(def ^:private ^"[Ljava.nio.file.LinkOption;" follow (make-array LinkOption 0))
(def ^:private ^"[Ljava.nio.file.LinkOption;" no-follow
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(def ^:private ^"[Ljava.nio.file.OpenOption;" no-open-options (make-array OpenOption 0))
(def ^:private ^"[Ljava.nio.file.attribute.FileAttribute;" no-attributes
  (make-array FileAttribute 0))
(def ^:private ^"[Ljava.nio.file.FileVisitOption;" no-visit-options
  (make-array FileVisitOption 0))

(defn path
  "`p` (a Path or anything `str` makes a path of) as a Path, with `parts`
  resolved below it in turn."
  ^Path [p & parts]
  (reduce (fn [^Path acc ^String part] (.resolve acc part))
          (if (instance? Path p) p (Paths/get (str p) (make-array String 0)))
          parts))

(defn absolute ^Path [p] (.normalize (.toAbsolutePath (path p))))

(defn exists? [p] (Files/exists (path p) follow))

(defn directory?
  "Whether `p` is a directory, not following a symbolic link at `p`."
  [p]
  (Files/isDirectory (path p) no-follow))

(defn regular-file?
  "Whether `p` is a regular file. With `:follow? false`, a symbolic link to
  one isn't."
  [p & {:keys [follow?] :or {follow? true}}]
  (Files/isRegularFile (path p) (if follow? follow no-follow)))

(defn symbolic-link? [p] (Files/isSymbolicLink (path p)))

(defn mkdirs ^Path [p] (Files/createDirectories (path p) no-attributes))

(defn list
  "The entries of directory `dir`, as Paths."
  [dir]
  (with-open [ls (Files/list (path dir))] (vec (iterator-seq (.iterator ls)))))

(defn walk
  "Every path below `dir`, `dir` included, as Paths."
  [dir]
  (with-open [w (Files/walk (path dir) no-visit-options)] (vec (iterator-seq (.iterator w)))))

(defn empty-dir?
  "Whether `p` is a directory with nothing in it."
  [p]
  (and (directory? p)
       (with-open [ls (Files/list (path p))] (not (.isPresent (.findAny ls))))))

(defn ensure-empty-dir!
  "Create directory `dir`, which must not exist or be an empty directory.
  Returns its Path. Throws ex-info with :firmament/error `kind` otherwise."
  ^Path [dir kind]
  (let [dir (path dir)]
    (when (and (Files/exists dir no-follow) (not (empty-dir? dir)))
      (throw (ex-info (str dir " exists and is not an empty directory")
                      {:firmament/error kind :path (str dir)})))
    (mkdirs dir)))

(defn read-bytes ^bytes [p] (Files/readAllBytes (path p)))

(defn read-string-utf8 ^String [p] (String. (read-bytes p) StandardCharsets/UTF_8))

(defn read-edn
  "The EDN value in file `p`, or `not-found` (nil) if there is no file.
  `opts` go to clojure.edn/read-string."
  ([p] (read-edn p {} nil))
  ([p opts not-found]
   (if (exists? p)
     (clojure.edn/read-string opts (read-string-utf8 p))
     not-found)))

(defn write-bytes! [p ^bytes data] (Files/write (path p) data no-open-options))

(defn output-stream ^java.io.OutputStream [p] (Files/newOutputStream (path p) no-open-options))

(defn write-atomically!
  "Write `data` to `target` through a temporary file in `tmp-dir`, so
  readers never see a partial file. `tmp-dir` must be on the same file
  system as `target`."
  [tmp-dir target ^bytes data]
  (let [target (path target)
        tmp-dir (mkdirs tmp-dir)]
    (mkdirs (.getParent target))
    (let [tmp (Files/createTempFile tmp-dir "firmament" ".tmp" no-attributes)]
      (try
        (Files/write tmp data no-open-options)
        (try
          (Files/move tmp target (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                                          StandardCopyOption/REPLACE_EXISTING]))
          (catch AtomicMoveNotSupportedException _
            (Files/move tmp target (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
        (finally (Files/deleteIfExists tmp))))
    target))

(defn temp-dir ^Path [prefix] (Files/createTempDirectory prefix no-attributes))

(defn delete-tree!
  "Delete `p` and everything below it, if it exists."
  [p]
  (when (Files/exists (path p) no-follow)
    (doseq [^Path q (rseq (walk p))] (Files/deleteIfExists q))))
