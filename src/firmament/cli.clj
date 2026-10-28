(ns firmament.cli
  "The `firmament` command: store files, trees and projects, and load, run
  and serve them."
  (:require [clojure.edn]
            [clojure.main]
            [clojure.string :as str]
            [firmament.clojure :as clj]
            [firmament.edn :as edn]
            [firmament.http :as http]
            [firmament.loader :as loader]
            [firmament.node :as node]
            [firmament.runner :as runner]
            [firmament.storage :as storage]
            [firmament.store :as store]
            [firmament.tree :as tree])
  (:import (clojure.lang LineNumberingPushbackReader)
           (java.io InputStream InputStreamReader OutputStream OutputStreamWriter PrintStream)
           (java.lang ProcessBuilder$Redirect)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files Paths))
  (:gen-class))

(set! *warn-on-reflection* true)

(def usage
  "Usage: firmament [--store DIR] COMMAND ARGS...

Commands:
  put FILE...   store each file; print the hash of each, one per line
  put -r DIR    store a directory as a tree; print the tree's hash
  put --edn FILE
                store the EDN value in FILE as a structured object, such as
                parameters for run; print its hash
  get HASH      print the object: a blob's bytes, or a structured object's
                canonical EDN
  cat HASH      print the contents of the file at HASH (a blob or a chunked
                file)
  import DIR [--classpath CP] [--main NS]
                import the deps.edn project in DIR; print its manifest's hash.
                Jars come from CP and from :local/root jar deps. Without CP,
                if the project has deps to resolve, CP is the output of
                clojure -Spath run in DIR. NS is the namespace run starts
  ls HASH [PATH]
                list a tree, or the subtree at PATH (a/b/c) below it; or list
                a manifest's namespaces, or the one named PATH. One line per
                link, <hash>  <name>, with / after subtrees and the key after
                links other than :link
  export HASH DIR
                write a tree out as directory DIR, or a manifest out as a
                project; DIR must not exist or be empty
  run [--from STORE] HASH [PARAMS] [-- ARG...]
                run the manifest at HASH: call -main of its main namespace with
                the value at PARAMS, or else with the ARGs. With --from, objects
                missing from the store are copied from STORE (a directory or an
                http(s):// URL; each copy is checked against its hash), and
                everything the program needs is fetched before it starts
  serve [--port N] [--bind ADDR]
                serve the store over HTTP (default 127.0.0.1:8080; port 0 picks
                a free port) and print its URL. Read-only unless
                $FIRMAMENT_TOKEN is set, in which case PUTs bearing that token
                can add objects
  push STORE HASH
                copy HASH and everything it links to into STORE (a directory, or
                a URL with $FIRMAMENT_TOKEN as the token); print how many
                objects were sent
  repl [HASH]   start a Clojure REPL that loads code from the manifest at
                HASH: (require 'my.app) reads my.app from the store, and
                io/resource and the manifest's jars work as on a classpath.
                Without HASH, the REPL starts from the empty program: plain
                Clojure

The store is DIR if given, else $FIRMAMENT_STORE, else ~/.firmament/store.
HASH is written sha256:<hex>.")

(def ^:private ^:dynamic *env* {})

(defn- other-store
  "The store named on the command line: a URL, or a directory."
  [spec]
  (if (http/url? spec)
    (http/http-store spec :token (not-empty (get *env* "FIRMAMENT_TOKEN")))
    (store/open spec)))

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

(defn- read-edn-file [f]
  (clojure.edn/read-string {:readers edn/readers} (String. (read-path f) StandardCharsets/UTF_8)))

(defn- put-command [s args _]
  (cond
    (= "-r" (first args))
    (do (when-not (= 2 (count args))
          (usage-error "put -r takes one directory"))
        [(edn/link-ref (tree/import-dir! s (second args)))])

    (= "--edn" (first args))
    (do (when-not (= 2 (count args))
          (usage-error "put --edn takes one file"))
        [(storage/put-value! s (read-edn-file (second args)))])

    (empty? args) (usage-error "put needs at least one file")

    :else (mapv #(edn/link-ref (storage/put-file! s (read-path %))) args)))

(defn- get-command [s args ^OutputStream out]
  (.write out ^bytes (:bytes (storage/fetch! s (edn/link-ref (hash-arg "get" args)))))
  nil)

(defn- cat-command [s args out]
  (node/write-file! s (hash-arg "cat" args) out)
  nil)

(defn- needs-classpath?
  "Whether the deps.edn in `dir` has deps the Clojure CLI has to resolve:
  anything but :local/root jars."
  [dir]
  (let [f (Paths/get (str dir) (into-array String ["deps.edn"]))]
    (when (Files/exists f (make-array java.nio.file.LinkOption 0))
      (->> (:deps (clojure.edn/read-string (String. (Files/readAllBytes f) StandardCharsets/UTF_8)))
           vals
           (some #(not (str/ends-with? (str (:local/root %)) ".jar")))))))

(defn- resolve-classpath
  "The project's classpath, from `clojure -Spath` run in `dir`. The command
  is $FIRMAMENT_CLOJURE if set, else clojure."
  [dir]
  (let [cmd (or (not-empty (get *env* "FIRMAMENT_CLOJURE")) "clojure")
        p (try (-> (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String [cmd "-Spath"]))
                   (.directory (java.io.File. (str dir)))
                   (.redirectError ProcessBuilder$Redirect/INHERIT)
                   (.start))
               (catch java.io.IOException _
                 (throw (ex-info (str "this project's deps need resolving, but " cmd
                                      " (the Clojure CLI) couldn't be run; install it, or pass --classpath CP")
                                 {}))))
        out (slurp (.getInputStream ^Process p))]
    (when-not (zero? (.waitFor ^Process p))
      (throw (ex-info (str cmd " -Spath failed in " dir) {})))
    (str/trim out)))

(defn- import-command [s args _]
  (let [[dir & opts] args
        {:strs [--classpath --main] :as opts} (when (even? (count opts)) (apply hash-map opts))]
    (when (or (nil? dir) (odd? (count (rest args)))
              (seq (remove #{"--classpath" "--main"} (keys opts))))
      (usage-error "import takes a directory, and optionally --classpath CP and --main NS"))
    (let [classpath (or --classpath (when (needs-classpath? dir) (resolve-classpath dir)))]
      [(edn/link-ref (clj/import-project! s dir :classpath classpath :main --main))])))

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
  (when (< 1 (count args))
    (usage-error "repl takes at most one hash"))
  (let [root (if (seq args) (hash-arg "repl" args) (clj/empty-manifest! s))
        out (OutputStreamWriter. out StandardCharsets/UTF_8)]
    (loader/with-root [s root :jar-dir (store/jar-dir s)]
      (binding [*out* out]
        (println (str ";; firmament: loading from " (edn/link-ref root)
                      (when (empty? args) " (the empty program)")))
        (clojure.main/repl)
        (flush))))
  nil)

(defn- run-program-command [s args ^OutputStream out]
  (let [[from args] (if (= "--from" (first args))
                      [(or (second args) (usage-error "--from needs a store directory or URL")) (drop 2 args)]
                      [nil args])
        [positional [_ & program-args]] (split-with #(not= "--" %) args)
        [h p & more] positional]
    (when (or (nil? h) (seq more))
      (usage-error "run takes a hash, an optional parameters hash, and -- before program arguments"))
    (let [r (if from (storage/cache s (other-store from)) s)
          out (OutputStreamWriter. out StandardCharsets/UTF_8)]
      (binding [*out* out]
        (runner/run-program! r (edn/link h)
                             :params (some-> p edn/link)
                             :args program-args
                             :jar-dir (store/jar-dir s))
        (flush))))
  nil)

(defn- serve-command [s args _]
  (let [{:strs [--port --bind] :as opts} (when (even? (count args)) (apply hash-map args))]
    (when (or (odd? (count args)) (seq (remove #{"--port" "--bind"} (keys opts))))
      (usage-error "serve takes an optional --port N and --bind ADDR"))
    (let [port (if --port
                 (or (parse-long --port) (usage-error (str "not a port: " --port)))
                 8080)
          server (http/serve! s :port port :bind (or --bind "127.0.0.1")
                              :token (not-empty (get *env* "FIRMAMENT_TOKEN")))]
      [(http/server-url server)])))

(defn- push-command [s args _]
  (when-not (= 2 (count args))
    (usage-error "push takes a store and a hash"))
  (let [[spec h] args]
    [(storage/copy! s (other-store spec) (edn/link-ref (edn/link h)))]))

(def ^:private commands
  {"put" put-command
   "get" get-command
   "cat" cat-command
   "import" import-command
   "ls" ls-command
   "export" export-command
   "repl" repl-command
   "run" run-program-command
   "serve" serve-command
   "push" push-command})

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
                         *in*)
                  *env* (or env {})]
          (run-command (store/open store) command args out))
        0
        (catch clojure.lang.ExceptionInfo e
          (complain err (ex-message e) false)
          (if (::usage (ex-data e)) 2 1))
        (catch java.io.IOException e
          (complain err (str (.getName (class e)) ": " (ex-message e)) false)
          1)))))

(defn -main
  "Exits with the code `run` returns. On success it returns instead, so a
  program started by run keeps going while it has threads running, as with
  clojure -M -m."
  [& args]
  (let [code (run args {:env (System/getenv) :out System/out :err System/err})]
    (when-not (zero? code) (System/exit code))))
