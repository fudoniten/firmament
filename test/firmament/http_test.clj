(ns firmament.http-test
  (:require [clojure.test :refer [deftest is testing]]
            [firmament.clojure :as clj]
            [firmament.edn :as edn]
            [firmament.http :as http]
            [firmament.loader :as loader]
            [firmament.runner :as runner]
            [firmament.storage :as storage]
            [firmament.store :as store]
            [firmament.store-test :refer [temp-dir]])
  (:import (com.sun.net.httpserver HttpServer SimpleFileServer SimpleFileServer$OutputLevel)
           (java.net InetSocketAddress URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files OpenOption)))

(set! *warn-on-reflection* true)

(def ^:private app "test-resources/projects/app")

(defmacro ^:private with-server [[binding s & opts] & body]
  `(let [server# (http/serve! ~s :port 0 ~@opts)
         ~binding (http/server-url server#)]
     (try ~@body (finally (.stop server# 0)))))

(defn- put-raw
  "PUT `body` at `path` below `url`, returning the status code."
  [url path ^bytes body token]
  (let [b (-> (HttpRequest/newBuilder (URI. (str url path)))
              (.PUT (HttpRequest$BodyPublishers/ofByteArray body)))]
    (when token (.header b "Authorization" (str "Bearer " token)))
    (.statusCode (.send (HttpClient/newHttpClient) (.build b) (HttpResponse$BodyHandlers/discarding)))))

(deftest reading
  (let [s (store/open (temp-dir))
        ref (storage/put-blob! s (.getBytes "hello"))
        missing (storage/put-blob! (storage/memory-store) (.getBytes "elsewhere"))]
    (with-server [url s]
      (let [r (http/http-store url)]
        (is (= "hello" (String. ^bytes (:bytes (storage/fetch r ref)) StandardCharsets/UTF_8)))
        (is (storage/has? r ref))
        (is (nil? (storage/fetch r missing)))
        (is (not (storage/has? r missing)))
        (testing "only object paths and format are served"
          (is (= 404 (put-raw url "objects/../format" (byte-array 0) nil))))))))

(deftest a-static-file-server-is-a-remote-too
  (let [dir (temp-dir)
        s (store/open dir)
        ref (storage/put-blob! s (.getBytes "static"))
        server (doto (SimpleFileServer/createFileServer (InetSocketAddress. "127.0.0.1" 0)
                                                        (.toAbsolutePath dir)
                                                        SimpleFileServer$OutputLevel/NONE)
                 (.start))]
    (try
      (let [r (http/http-store (str "http://127.0.0.1:" (.getPort (.getAddress ^HttpServer server))))]
        (is (= "static" (String. ^bytes (:bytes (storage/fetch r ref)) StandardCharsets/UTF_8))))
      (finally (.stop ^HttpServer server 0)))))

(deftest machine-b-runs-what-machine-a-serves
  (let [a (store/open (temp-dir))
        root (clj/import-project! a app :main 'app.core)]
    (with-server [url a]
      (let [b (storage/cache (storage/memory-store) (http/http-store url))]
        (is (= "[\"HELLO, STORE!\" \"over http\"]\n"
               (with-out-str (runner/run-program! b root :args ["over http"]))))
        (testing "and loads its code from what it fetched"
          (loader/with-root [b root]
            (require 'app.core :reload-all)
            (is (= "HELLO, STORE!" ((resolve 'app.core/hello))))))))))

(deftest tampering-is-detected
  (let [a (store/open (temp-dir))
        root (clj/import-project! a app :main 'app.core)
        m (clj/manifest a root)
        source (second (clj/jvm-source (get (into {} (clj/namespace-entries a m)) "app.text-util")))
        f (store/object-file a (edn/link-ref source))]
    (Files/write f (.getBytes "blob\n(ns app.text-util) (defn shout [s] :pwned)" StandardCharsets/UTF_8)
                 ^"[Ljava.nio.file.OpenOption;" (make-array OpenOption 0))
    (with-server [url a]
      (let [b (storage/cache (storage/memory-store) (http/http-store url))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"doesn't match its hash"
                              (runner/prefetch! b root)))
        (is (not (storage/has? (:local b) (edn/link-ref source))))))))

(deftest pushing
  (let [a (store/open (temp-dir))
        root (clj/import-project! a app :main 'app.core)
        server-store (store/open (temp-dir))]
    (with-server [url server-store :token "secret"]
      (testing "a read-only client can't write"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs a token"
                              (storage/put-blob! (http/http-store url) (.getBytes "x")))))
      (testing "a wrong token is refused"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"HTTP 403"
                              (storage/put-blob! (http/http-store url :token "guess") (.getBytes "x")))))
      (testing "a body that doesn't match its path is refused"
        (let [ref (storage/put-blob! (storage/memory-store) (.getBytes "real"))]
          (is (= 400 (put-raw url (http/object-path ref) (.getBytes "blob\nfake") "secret")))))
      (let [remote (http/http-store url :token "secret")
            n (storage/copy! a remote (edn/link-ref root))]
        (is (pos? n))
        (is (zero? (storage/copy! a remote (edn/link-ref root))) "pushing again sends nothing")
        (testing "the pushed program runs from the server"
          (let [b (storage/cache (storage/memory-store) (http/http-store url))]
            (is (= ["app.core" "app.platform" "app.text-util"] (runner/prefetch! b root)))))))))
