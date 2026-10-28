(ns firmament.cli-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [firmament.cli :as cli]
            [firmament.clojure :as clj]
            [firmament.edn :as edn]
            [firmament.gen :as gen]
            [firmament.hash :as hash]
            [firmament.http :as http]
            [firmament.store :as store]
            [firmament.store-test :refer [temp-dir]]
            [firmament.tree :as tree])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream PrintStream)
           (java.nio.file Files OpenOption Path)
           (java.util Arrays)))

(set! *warn-on-reflection* true)

(def ^:private ^:dynamic *env* {})

(defn- run-with-input [^String input store & args]
  (let [out (ByteArrayOutputStream.)
        err (ByteArrayOutputStream.)
        code (cli/run (concat ["--store" (str store)] args)
                      {:env *env* :out out :err (PrintStream. err true "UTF-8")
                       :in (ByteArrayInputStream. (.getBytes input "UTF-8"))})]
    {:code code :out (.toByteArray out) :err (.toString err "UTF-8")}))

(defn- run [store & args] (apply run-with-input "" store args))

(defn- write-file ^Path [^Path dir name ^bytes data]
  (Files/write (.resolve dir ^String name) data ^"[Ljava.nio.file.OpenOption;" (make-array OpenOption 0)))

(deftest put-get-cat
  (let [store (temp-dir)
        files (temp-dir)
        ^bytes small (.getBytes "hello\n" "UTF-8")
        ^bytes large (gen/sha256-ctr "cli-test" 0 (* 1024 1024))
        {:keys [code out]} (run store "put" (str (write-file files "small" small))
                                (str (write-file files "large" large)))
        [small-ref large-ref] (str/split-lines (String. ^bytes out "UTF-8"))]
    (is (= 0 code))
    (is (= (hash/blob-hash small) small-ref))
    (testing "cat gives back the file"
      (is (Arrays/equals small ^bytes (:out (run store "cat" small-ref))))
      (is (Arrays/equals large ^bytes (:out (run store "cat" large-ref)))))
    (testing "get gives the object itself"
      (is (Arrays/equals small ^bytes (:out (run store "get" small-ref))))
      (is (str/starts-with? (String. ^bytes (:out (run store "get" large-ref)) "UTF-8")
                            "{:chunks [")))))

(deftest errors
  (let [store (temp-dir)]
    (is (= 2 (:code (run store))))
    (is (= 2 (:code (run store "frobnicate"))))
    (is (= 2 (:code (run store "get"))))
    (is (= 2 (:code (run store "put"))))
    (let [{:keys [code err]} (run store "cat" (hash/blob-hash (byte-array 1)))]
      (is (= 1 code))
      (is (str/includes? err "object not found")))
    (is (= 1 (:code (run store "get" "sha256:nothex"))))
    (is (= 1 (:code (run store "put" (str (.resolve (temp-dir) "missing"))))))))

(deftest trees
  (let [store (temp-dir)
        dir (temp-dir)
        _ (do (Files/createDirectories (.resolve dir "sub") (make-array java.nio.file.attribute.FileAttribute 0))
              (write-file dir "a.txt" (.getBytes "a\n" "UTF-8"))
              (write-file (.resolve dir "sub") "b.txt" (.getBytes "b\n" "UTF-8")))
        {:keys [code out]} (run store "put" "-r" (str dir))
        root (str/trim (String. ^bytes out "UTF-8"))]
    (is (= 0 code))
    (let [lines (str/split-lines (String. ^bytes (:out (run store "ls" root)) "UTF-8"))]
      (is (= [(str (hash/blob-hash (.getBytes "a\n" "UTF-8")) "  a.txt")] (take 1 lines)))
      (is (str/ends-with? (second lines) "  sub/")))
    (is (str/ends-with? (str/trim (String. ^bytes (:out (run store "ls" root "sub")) "UTF-8"))
                        "  b.txt"))
    (let [target (.resolve (temp-dir) "out")]
      (is (= 0 (:code (run store "export" root (str target)))))
      (is (= "b\n" (String. (Files/readAllBytes (.resolve target "sub/b.txt")) "UTF-8"))))
    (is (= 1 (:code (run store "ls" root "missing"))))
    (is (= 2 (:code (run store "put" "-r"))))
    (is (= 2 (:code (run store "export" root))))))

(deftest projects
  (let [store (temp-dir)
        {:keys [code out err]} (run store "import" "test-resources/projects/sample")
        root (str/trim (String. ^bytes out "UTF-8"))]
    (is (= 0 code) err)
    (let [lines (str/split-lines (String. ^bytes (:out (run store "ls" root)) "UTF-8"))]
      (is (some #(str/ends-with? % "  sample.util  :source/cljc") lines))
      (is (some #(str/ends-with? % "  resources  :resources") lines)))
    (is (= 2 (count (str/split-lines (String. ^bytes (:out (run store "ls" root "sample.core")) "UTF-8")))))
    (let [target (.resolve (temp-dir) "out")]
      (is (= 0 (:code (run store "export" root (str target)))))
      (is (= root (str/trim (String. ^bytes (:out (run store "import" (str target))) "UTF-8")))))
    (is (= 2 (:code (run store "import"))))
    (is (= 2 (:code (run store "import" "x" "--bogus" "y"))))))

(deftest repl
  (let [store (temp-dir)
        root (str/trim (String. ^bytes (:out (run store "import" "test-resources/projects/app")) "UTF-8"))
        {:keys [code out err]} (run-with-input "(require 'app.core :reload-all)\n(app.core/hello)\n"
                                               store "repl" root)]
    (is (= 0 code) err)
    (is (str/includes? (String. ^bytes out "UTF-8") "\"HELLO, STORE!\""))
    (is (str/includes? (String. ^bytes out "UTF-8") (str ";; firmament: loading from " root)))
    (testing "without a hash, a plain Clojure REPL on the empty program"
      (let [{:keys [code out err]} (run-with-input "(+ 1 2)\n" store "repl")
            out (String. ^bytes out "UTF-8")]
        (is (= 0 code) err)
        (is (str/includes? out "(the empty program)"))
        (is (str/includes? out "3"))))
    (is (= 2 (:code (run store "repl" root root))))))

(deftest running
  (let [remote (temp-dir)
        local (temp-dir)
        root (str/trim (String. ^bytes (:out (run remote "import" "test-resources/projects/app" "--main" "app.core")) "UTF-8"))
        p-file (.resolve (temp-dir) "p.edn")
        _ (spit (str p-file) "{:who \"params\"}")
        p (str/trim (String. ^bytes (:out (run remote "put" "--edn" (str p-file))) "UTF-8"))]
    (let [{:keys [code out err]} (run local "run" "--from" (str remote) root "--" "x")]
      (is (= 0 code) err)
      (is (= "[\"HELLO, STORE!\" \"x\"]\n" (String. ^bytes out "UTF-8"))))
    (testing "the first run copied what the program needs into the local store"
      (is (= 0 (:code (run local "run" root)))))
    (let [{:keys [code out err]} (run local "run" "--from" (str remote) root p)]
      (is (= 0 code) err)
      (is (= "[\"HELLO, STORE!\" {:who \"params\"}]\n" (String. ^bytes out "UTF-8"))))
    (is (= 2 (:code (run local "run"))))
    (is (= 2 (:code (run local "run" root p "extra"))))))

(deftest serving
  (let [a (temp-dir)
        root (str/trim (String. ^bytes (:out (run a "import" "test-resources/projects/app" "--main" "app.core")) "UTF-8"))
        server-store (store/open (temp-dir))
        ^com.sun.net.httpserver.HttpServer server (http/serve! server-store :port 0 :token "secret")
        url (http/server-url server)]
    (try
      (testing "push needs the token"
        (is (= 1 (:code (run a "push" url root)))))
      (binding [*env* {"FIRMAMENT_TOKEN" "secret"}]
        (let [{:keys [code out err]} (run a "push" url root)]
          (is (= 0 code) err)
          (is (pos? (parse-long (str/trim (String. ^bytes out "UTF-8"))))))
        (is (= "0\n" (String. ^bytes (:out (run a "push" url root)) "UTF-8"))))
      (let [{:keys [code out err]} (run (temp-dir) "run" "--from" url root "--" "b")]
        (is (= 0 code) err)
        (is (= "[\"HELLO, STORE!\" \"b\"]\n" (String. ^bytes out "UTF-8"))))
      (finally (.stop server 0)))))

(deftest import-resolves-the-classpath
  (let [dir (temp-dir)
        bin (temp-dir)
        fake (.resolve bin "clojure")]
    (spit (str (.resolve dir "deps.edn")) "{:deps {some/lib {:mvn/version \"1.0\"}}}")
    (Files/createDirectories (.resolve dir "src") (make-array java.nio.file.attribute.FileAttribute 0))
    (spit (str (.resolve dir "src/a.clj")) "(ns a)")
    (Files/copy (.toPath (java.io.File. "test-resources/projects/sample/lib/vendored.jar"))
                (.resolve dir "lib.jar")
                ^"[Ljava.nio.file.CopyOption;" (make-array java.nio.file.CopyOption 0))
    ;; A stand-in for the Clojure CLI, which must be run in the project.
    (spit (str fake) "#!/bin/sh\n[ -f deps.edn ] && echo src:lib.jar\n")
    (.setExecutable (.toFile fake) true)
    (let [store (temp-dir)
          {:keys [code out err]} (binding [*env* {"FIRMAMENT_CLOJURE" (str fake)}]
                                   (run store "import" (str dir)))
          root (str/trim (String. ^bytes out "UTF-8"))]
      (is (= 0 code) err)
      (let [s (store/open store)]
        (is (= ["lib.jar"] (map key (tree/ls s (:jars (clj/manifest s (edn/link root))) []))))))
    (testing "without the Clojure CLI, import says what to do"
      (let [{:keys [code err]} (binding [*env* {"FIRMAMENT_CLOJURE" (str (.resolve bin "missing"))}]
                                 (run (temp-dir) "import" (str dir)))]
        (is (= 1 code))
        (is (str/includes? err "pass --classpath"))))
    (testing "--classpath skips the CLI"
      (binding [*env* {"FIRMAMENT_CLOJURE" (str (.resolve bin "missing"))}]
        (is (= 0 (:code (run (temp-dir) "import" (str dir) "--classpath" "lib.jar"))))))))

