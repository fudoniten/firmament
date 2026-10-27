(ns firmament.cli
  "The `firmament` command: put files into the local store and read them back."
  (:require [clojure.string :as str]
            [firmament.edn :as edn]
            [firmament.resolver :as resolver]
            [firmament.store :as store])
  (:import (java.io OutputStream PrintStream)
           (java.nio.file Files Paths))
  (:gen-class))

(set! *warn-on-reflection* true)

(def usage
  "Usage: firmament [--store DIR] COMMAND ARGS...

Commands:
  put FILE...   store each file; print the link to each, one per line
  get HASH      print the object: a blob's bytes, or a structured object's
                canonical EDN
  cat HASH      print the contents of the file at HASH (a blob or a chunked
                file)

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

(defn- read-path ^bytes [^String f]
  (Files/readAllBytes (Paths/get f (make-array String 0))))

(defn- run-command [s command args ^OutputStream out]
  (let [ps (PrintStream. out true "UTF-8")
        one-hash (fn []
                   (when-not (= 1 (count args))
                     (throw (ex-info (str command " takes one hash") {::usage true})))
                   (edn/link-ref (edn/link (first args))))]
    (case command
      "put" (do (when (empty? args)
                  (throw (ex-info "put needs at least one file" {::usage true})))
                (doseq [f args]
                  (.println ps (edn/link-ref (store/put-file! s (read-path f))))))
      "get" (.write out ^bytes (:bytes (resolver/fetch! s (one-hash))))
      "cat" (resolver/write-file! s (edn/link (one-hash)) out))
    (.flush out)))

(defn run
  "Run the command line `args`, writing output to `out` and messages to
  `err` (a PrintStream). Returns the exit code: 0 on success, 1 on failure,
  2 on a usage error."
  [args {:keys [env ^OutputStream out ^PrintStream err]}]
  (let [{:keys [store command args error help]} (parse-args args env)]
    (cond
      help (do (.println err ^String usage) 0)
      error (do (.println err (str "firmament: " error)) (.println err ^String usage) 2)
      (not (#{"put" "get" "cat"} command))
      (do (.println err (str "firmament: unknown command: " command)) (.println err ^String usage) 2)
      :else
      (try
        (run-command (store/open store) command args out)
        0
        (catch clojure.lang.ExceptionInfo e
          (.println err (str "firmament: " (ex-message e)))
          (if (::usage (ex-data e)) 2 1))
        (catch java.io.IOException e
          (.println err (str "firmament: " (str/trim (str (.getClass e) ": " (ex-message e)))))
          1)))))

(defn -main [& args]
  (System/exit (run args {:env (System/getenv) :out System/out :err System/err})))
