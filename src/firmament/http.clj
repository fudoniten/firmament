(ns firmament.http
  "Stores over HTTP (docs/spec/http.md): a client that reads (and, with a
  token, writes) a remote store, and a server for a local store. The URL
  layout is the local store's own directory layout, so any static file
  server pointed at a store directory is a read-only remote too."
  (:require [clojure.string :as str]
            [firmament.hash :as hash]
            [firmament.storage :as storage]
            [firmament.store :as store])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io InputStream)
           (java.net InetSocketAddress URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.security MessageDigest)
           (java.time Duration)
           (java.util.concurrent Executors ThreadFactory)))

(set! *warn-on-reflection* true)

(defn- fail [msg data]
  (throw (ex-info msg (assoc data ::error :http))))

(def max-object-size
  "The largest object a server accepts in a PUT. Files over 256 KiB are
  chunked, so real objects are far smaller."
  (* 16 1024 1024))

(defn object-path
  "The path of object `ref` below a store's base URL:
  objects/ab/cdef… for sha256:abcdef…."
  [ref]
  (let [hex (subs ref (count "sha256:"))]
    (str "objects/" (subs hex 0 2) "/" (subs hex 2))))

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
  (fetch [_ ref]
    (let [resp (send! client (request base (object-path ref) :get nil token))]
      (case (.statusCode resp)
        200 (or (hash/parse-object ref (.body resp))
                (fail (str "the object served for " ref " doesn't match its hash")
                      {:ref ref :uri (str (.uri resp))}))
        404 nil
        (status-error resp (str "fetching " ref)))))
  (has? [_ ref]
    (let [resp (send! client (request base (object-path ref) :head nil token))]
      (case (.statusCode resp)
        200 true
        404 false
        (status-error resp (str "checking for " ref)))))

  storage/Store
  (put-object! [_ kind payload]
    (when-not token (fail (str "writing to " base " needs a token") {:base base}))
    (let [ref (hash/object-hash kind payload)
          resp (send! client (request base (object-path ref) :put (hash/object-bytes kind payload) token))]
      (when-not (#{200 201 204} (.statusCode resp))
        (status-error resp (str "storing " ref)))
      ref)))

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
      (when-not (= store/format-version v)
        (fail (str base " holds format " v ", not " store/format-version) {:base base :format v})))
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
          ref (when a (str "sha256:" a b))]
      (cond
        (and (= "/format" path) (#{"GET" "HEAD"} method))
        (respond! ex 200 (text store/format-version))

        (nil? ref) (respond! ex 404 (text "not found"))

        (#{"GET" "HEAD"} method)
        (let [f (store/object-file s ref)]
          (if (storage/has? s ref)
            (respond! ex 200 (Files/readAllBytes f))
            (respond! ex 404 (text "not found"))))

        (= "PUT" method)
        (cond
          (not (authorized? ex token)) (respond! ex 403 (text "forbidden"))
          :else
          (let [data (read-limited (.getRequestBody ex) max-object-size)
                {:keys [kind bytes]} (when data (hash/parse-object ref data))]
            (cond
              (nil? data) (respond! ex 413 (text "too large"))
              (nil? kind) (respond! ex 400 (text (str "body doesn't hash to " ref)))
              :else (do (storage/put-object! s kind bytes)
                        (respond! ex 204 nil)))))

        :else (respond! ex 405 (text "method not allowed"))))
    (catch Exception e
      (respond! ex 500 (text (str "error: " (.getMessage e)))))
    (finally (.close ex))))

(defn serve!
  "Serve local store `s` over HTTP on `port` (0 for any free port) at
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
