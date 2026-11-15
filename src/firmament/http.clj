(ns firmament.http
  "Stores over HTTP (docs/spec/http.md): a client that reads (and, with a
  token, writes) a remote store, and a server for a local store. The URL
  layout is the local store's own directory layout, so any static file
  server pointed at a store directory is a read-only remote too."
  (:require [clojure.string :as str]
            [firmament.edn :as edn]
            [firmament.error :as error]
            [firmament.hash :as hash]
            [firmament.storage :as storage])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io InputStream)
           (java.net InetSocketAddress URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.time Duration)
           (java.util.concurrent Executors ThreadFactory)))

(set! *warn-on-reflection* true)

(def ^:private fail (partial error/fail :http))

(def max-object-size
  "The largest object a server accepts in a PUT. Files over 256 KiB are
  chunked, so real objects are far smaller."
  (* 16 1024 1024))

(def object-path
  "The path of object `link` below a store's base URL:
  objects/ab/cdef… for sha256:abcdef…, the same as on disk."
  hash/object-path)

;; ---------------------------------------------------------------- client

(defn- request ^HttpRequest [^String base path method body token]
  (let [b (-> (HttpRequest/newBuilder (URI. (str base path)))
              (.timeout (Duration/ofSeconds 60)))]
    (when token (.header b "Authorization" (str "Bearer " token)))
    (case method
      :get (.GET b)
      :head (.method b "HEAD" (HttpRequest$BodyPublishers/noBody))
      :put (.PUT b (HttpRequest$BodyPublishers/ofByteArray body)))
    (.build b)))

(defn- send! ^HttpResponse [^HttpClient client ^HttpRequest req]
  (.send client req (HttpResponse$BodyHandlers/ofByteArray)))

(defn- status-error [^HttpResponse resp what]
  (fail (str what ": HTTP " (.statusCode resp) " from " (.uri resp))
        {:status (.statusCode resp) :uri (str (.uri resp))}))

(defrecord HttpStore [^HttpClient client ^String base token]
  storage/Resolver
  (fetch [_ link]
    (let [resp (send! client (request base (object-path link) :get nil token))]
      (case (.statusCode resp)
        200 (or (hash/parse-object link (.body resp))
                (fail (str "the object served for " (edn/link-ref link) " doesn't match its hash")
                      {:link link :uri (str (.uri resp))}))
        404 nil
        (status-error resp (str "fetching " (edn/link-ref link))))))
  (has? [_ link]
    (let [resp (send! client (request base (object-path link) :head nil token))]
      (case (.statusCode resp)
        200 true
        404 false
        (status-error resp (str "checking for " (edn/link-ref link))))))

  storage/Store
  (put-object! [_ kind payload]
    (when-not token (fail (str "writing to " base " needs a token") {:base base}))
    (let [link (hash/object-link kind payload)
          resp (send! client (request base (object-path link) :put (hash/object-bytes kind payload) token))]
      (when-not (#{200 201 204} (.statusCode resp))
        (status-error resp (str "storing " (edn/link-ref link))))
      link)))

(defn http-store
  "The store at `base-url` (http:// or https://). Reads need nothing more;
  writes need `:token`, which the server must accept. Checks the store's
  format version first."
  [base-url & {:keys [token]}]
  (let [base (if (str/ends-with? base-url "/") base-url (str base-url "/"))
        client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 10))
                   (.followRedirects java.net.http.HttpClient$Redirect/NORMAL)
                   (.build))
        resp (send! client (request base "format" :get nil token))]
    (when-not (= 200 (.statusCode resp))
      (status-error resp (str "reading the format of " base)))
    (let [v (str/trim (String. ^bytes (.body resp) StandardCharsets/UTF_8))]
      (when-not (= hash/format-version v)
        (fail (str base " holds format " v ", not " hash/format-version) {:base base :format v})))
    (->HttpStore client base token)))

(defn url? [s] (boolean (re-find #"^https?://" (str s))))

;; ---------------------------------------------------------------- server

(def ^:private object-route #"^/objects/([0-9a-f]{2})/([0-9a-f]{62})$")

(defn- respond! [^HttpExchange ex status ^bytes body]
  (if (or (nil? body) (= "HEAD" (.getRequestMethod ex)))
    (.sendResponseHeaders ex status -1)
    (do (.sendResponseHeaders ex status (alength body))
        (with-open [out (.getResponseBody ex)] (.write out body)))))

(defn- text [s] (.getBytes (str s "\n") StandardCharsets/UTF_8))

(defn- read-limited
  "The request body, or nil if it is longer than `limit` bytes."
  [^InputStream in limit]
  (let [data (.readNBytes in (int (inc limit)))]
    (when (<= (alength data) limit) data)))

(defn- authorized? [^HttpExchange ex ^String token]
  (let [given (or (.getFirst (.getRequestHeaders ex) "Authorization") "")]
    (and token
         (MessageDigest/isEqual (.getBytes (str "Bearer " token) StandardCharsets/UTF_8)
                                (.getBytes given StandardCharsets/UTF_8)))))

(defn- handle [s token ^HttpExchange ex]
  (try
    (let [method (.getRequestMethod ex)
          path (.getPath (.getRequestURI ex))
          [_ a b] (re-matches object-route path)
          link (when a (edn/link (str "sha256:" a b)))]
      (cond
        (and (= "/format" path) (#{"GET" "HEAD"} method))
        (respond! ex 200 (text hash/format-version))

        (nil? link) (respond! ex 404 (text "not found"))

        (= "HEAD" method)
        (respond! ex (if (storage/has? s link) 200 404) nil)

        (= "GET" method)
        (if-let [{:keys [kind bytes]} (storage/fetch s link)]
          (respond! ex 200 (hash/object-bytes kind bytes))
          (respond! ex 404 (text "not found")))

        (= "PUT" method)
        (cond
          (not (authorized? ex token)) (respond! ex 403 (text "forbidden"))
          :else
          (let [data (read-limited (.getRequestBody ex) max-object-size)
                {:keys [kind bytes] :as object} (when data (hash/parse-object link data))
                missing (when kind (remove #(storage/has? s %) (storage/object-links object)))]
            (cond
              (nil? data) (respond! ex 413 (text "too large"))
              (nil? kind) (respond! ex 400 (text (str "body doesn't hash to " (edn/link-ref link))))
              ;; Children first, as in every store: that is what lets
              ;; storage/copy! skip everything below an object a store has.
              (seq missing) (respond! ex 409 (text (str "links to objects this store doesn't have, such as "
                                                        (edn/link-ref (first missing)))))
              :else (do (storage/put-object! s kind bytes)
                        (respond! ex 204 nil)))))

        :else (respond! ex 405 (text "method not allowed"))))
    (catch Exception e
      (respond! ex 500 (text (str "error: " (.getMessage e)))))
    (finally (.close ex))))

(defn serve!
  "Serve store `s` (any storage/Resolver, and storage/Store for PUTs) over HTTP on `port` (0 for any free port) at
  address `bind`. GET and HEAD on objects/ab/cdef… and on format, as the
  store's directory holds them; PUT stores an object if the request has
  `token` as its bearer token and the body hashes to the name in the path.
  Without a token, the server is read-only. Returns the HttpServer, already
  started; stop it with (.stop server 0)."
  ^HttpServer [s & {:keys [port bind token] :or {port 8080 bind "127.0.0.1"}}]
  (doto (HttpServer/create (InetSocketAddress. ^String bind (int port)) 0)
    (.createContext "/" (reify HttpHandler (handle [_ ex] (handle s token ex))))
    (.setExecutor (Executors/newFixedThreadPool
                   16 (reify ThreadFactory
                        (newThread [_ r] (doto (Thread. r "firmament-http") (.setDaemon true))))))
    (.start)))

(defn server-url
  "The base URL clients use to reach `server`."
  [^HttpServer server]
  (let [addr (.getAddress server)]
    (str "http://" (.getHostString addr) ":" (.getPort addr) "/")))
