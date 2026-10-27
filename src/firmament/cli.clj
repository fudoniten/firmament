(ns firmament.cli
  "The `firmament` command: put files into the local store and read them back."
  (:require [clojure.main]
            [clojure.string :as str]
            [firmament.clojure :as clj]
            [firmament.edn :as edn]
            [firmament.loader :as loader]
            [firmament.node :as node]
            [firmament.storage :as storage]
            [firmament.store :as store]
            [firmament.tree :as tree])
  (:import (clojure.lang LineNumberingPushbackReader)
           (java.io InputStream InputStreamReader OutputStream OutputStreamWriter PrintStream)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files Paths))
  (:gen-class))

(set! *warn-on-reflection* true)

(def usage
  "Usage: firmament [--store DIR] COMMAND ARGS...

Commands:
  put FILE...   store each file; print the hash of each, one per line
  put -r DIR    store a directory as a tree; print the tree's hash
  get HASH      print the object: a blob's bytes, or a structured object's
                canonical EDN
  cat HASH      print the contents of the file at HASH (a blob or a chunked
                file)
  import DIR [--classpath CP]
                import the deps.edn project in DIR; print its manifest's hash.
                Jars come from CP (e.g. the output of clojure -Spath) and from
                :local/root jar deps
  ls HASH [PATH]
                list a tree, or the subtree at PATH (a/b/c) below it; or list
                a manifest's namespaces, or the one named PATH. One line per
                link, <hash>  <name>, with / after subtrees and the key after
                links other than :link
  export HASH DIR
                write a tree out as directory DIR, or a manifest out as a
                project; DIR must not exist or be empty
  repl HASH     start a Clojure REPL that loads code from the manifest at
                HASH: (require 'my.app) reads my.app from the store, and
                io/resource and the manifest's jars work as on a classpath

The store is DIR if given, else $FIRMAMENT_STORE, else ~/.firmament/store.
HASH is written sha256:<hex>.")

(defn- parse-args [args env]
  (loop [args args opts {:store (store/default-dir env)}]
    (let [[a b & more] args]
      (cond
        (= "--store" a) (if b (recur more (assoc opts :store b)) (assoc opts :error "--store needs a directory"))
        (#{"-h" "--help"} a) (assoc opts :help true)
        (nil? a) (assoc opts :error "no command given")
        :else (assoc opts :command a :args (vec (rest args)))))))

(defn- parse-path [path]
  (if (or (nil? path) (= "" path)) [] (str/split path #"/")))

(defn- read-path ^bytes [^String f]
  (Files/readAllBytes (Paths/get f (make-array String 0))))

(defn- usage-error [msg]
  (throw (ex-info msg {::usage true})))

(defn- hash-arg
  "The single hash in `args`, as a link."
  [command args]
  (when-not (= 1 (count args))
    (usage-error (str command " takes one hash")))
  (edn/link (first args)))

;; Each command takes the store, its arguments and the output stream. A
;; command that returns lines has them printed; the others write to `out`
;; themselves.

(defn- put-command [s args _]
  (cond
    (= "-r" (first args))
    (do (when-not (= 2 (count args))
          (usage-error "put -r takes one directory"))
        [(edn/link-ref (tree/import-dir! s (second args)))])

    (empty? args) (usage-error "put needs at least one file")

    :else (mapv #(edn/link-ref (storage/put-file! s (read-path %))) args)))

(defn- get-command [s args ^OutputStream out]
  (.write out ^bytes (:bytes (storage/fetch! s (edn/link-ref (hash-arg "get" args)))))
  nil)

(defn- cat-command [s args out]
  (node/write-file! s (hash-arg "cat" args) out)
  nil)

(defn- import-command [s args _]
  (let [[dir & opts] args
        {:strs [--classpath] :as opts} (apply hash-map opts)]
    (when (or (nil? dir) (not= #{} (disj (set (keys opts)) "--classpath")))
      (usage-error "import takes a directory and an optional --classpath CP"))
    [(edn/link-ref (clj/import-project! s dir :classpath --classpath))]))

(defn- ls-line [{:keys [name key link]}]
  (str (edn/link-ref link) "  " name
       (case key :link "" :children "/" (str "  " key))))

(defn- ls-command [s [h path & more] _]
  (when (or (nil? h) (seq more))
    (usage-error "ls takes a hash and an optional path"))
  (map ls-line (node/listing s (node/load s (edn/link h)) (parse-path path))))

(defn- export-command [s args _]
  (when-not (= 2 (count args))
    (usage-error "export takes a hash and a directory"))
  (node/export! s (node/load s (edn/link (first args))) (second args))
  nil)

(defn- repl-command [s args ^OutputStream out]
  (let [root (hash-arg "repl" args)
        out (OutputStreamWriter. out StandardCharsets/UTF_8)]
    (loader/with-root [s root :jar-dir (store/jar-dir s)]
      (binding [*out* out]
        (clojure.main/repl)
        (flush))))
  nil)

(def ^:private commands
  {"put" put-command
   "get" get-command
   "cat" cat-command
   "import" import-command
   "ls" ls-command
   "export" export-command
   "repl" repl-command})

(defn- run-command [s command args ^OutputStream out]
  (doseq [line ((commands command) s args out)]
    (.write out (.getBytes (str line "\n") StandardCharsets/UTF_8)))
  (.flush out))

(defn- complain
  "Print `msg` to `err`, followed by the usage text if `usage?`."
  [^PrintStream err msg usage?]
  (.println err (str "firmament: " msg))
  (when usage? (.println err ^String usage)))

(defn run
  "Run the command line `args`, writing output to `out` and messages to
  `err` (a PrintStream), and reading `in` (an InputStream, for repl) if
  given. Returns the exit code: 0 on success, 1 on failure, 2 on a usage
  error."
  [args {:keys [env ^OutputStream out ^PrintStream err ^InputStream in]}]
  (let [{:keys [store command args error help]} (parse-args args env)]
    (cond
      help (do (.println err ^String usage) 0)
      error (do (complain err error true) 2)
      (not (contains? commands command))
      (do (complain err (str "unknown command: " command) true) 2)
      :else
      (try
        (binding [*in* (if in
                         (LineNumberingPushbackReader. (InputStreamReader. in StandardCharsets/UTF_8))
                         *in*)]
          (run-command (store/open store) command args out))
        0
        (catch clojure.lang.ExceptionInfo e
          (complain err (ex-message e) false)
          (if (::usage (ex-data e)) 2 1))
        (catch java.io.IOException e
          (complain err (str (.getName (class e)) ": " (ex-message e)) false)
          1)))))

(defn -main [& args]
  (System/exit (run args {:env (System/getenv) :out System/out :err System/err})))
