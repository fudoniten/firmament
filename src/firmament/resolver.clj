(ns firmament.resolver
  "Fetching objects by hash, from any source. The local store is the first
  resolver; remote ones come later."
  (:require [firmament.edn :as edn])
  (:import (java.io ByteArrayOutputStream OutputStream)))

(set! *warn-on-reflection* true)

(defprotocol Resolver
  (fetch [r ref]
    "The object named `ref` (a \"sha256:<hex>\" string) as
    {:kind :blob|:edn, :bytes payload}, or nil when this resolver doesn't
    have it. The payload is the raw bytes of a blob, or the canonical EDN of
    a structured object. A resolver only returns objects whose hash it has
    checked.")
  (has? [r ref]
    "Whether this resolver has the object named `ref`."))

(defn- fail [msg data]
  (throw (ex-info msg (assoc data ::error :resolve))))

(defn fetch!
  "Like fetch, but throws when the object is missing."
  [r ref]
  (or (fetch r ref) (fail (str "object not found: " ref) {:ref ref})))

(defn fetch-value
  "The decoded value of the structured object named `ref`."
  [r ref]
  (let [{:keys [kind bytes]} (fetch! r ref)]
    (when-not (= :edn kind)
      (fail (str "not a structured object: " ref) {:ref ref :kind kind}))
    (edn/decode bytes)))

(defn- write-node!
  "Write the file at `ref` to `out`; returns the number of bytes written."
  ^long [r ref ^OutputStream out]
  (let [{:keys [kind ^bytes bytes]} (fetch! r ref)]
    (if (= :blob kind)
      (do (.write out bytes) (alength bytes))
      (let [{:keys [type size chunks]} (edn/decode bytes)]
        (when-not (= :chunked type)
          (fail (str "not a file: " ref) {:ref ref :type type}))
        (let [written (reduce (fn [^long total {:keys [link] :as chunk}]
                                (let [n (write-node! r (edn/link-ref link) out)]
                                  (when-not (= n (:size chunk))
                                    (fail (str "chunk size mismatch in " ref)
                                          {:ref ref :chunk link :expected (:size chunk) :actual n}))
                                  (+ total n)))
                              0 chunks)]
          (when-not (= written size)
            (fail (str "file size mismatch: " ref) {:ref ref :expected size :actual written}))
          written)))))

(defn write-file!
  "Write the contents of the file at `link` (a blob or a :chunked node) to
  `out`, one chunk at a time, checking every size along the way. Returns the
  number of bytes written."
  ^long [r link ^OutputStream out]
  (write-node! r (edn/link-ref link) out))

(defn read-file
  "The contents of the file at `link`, as a byte array."
  ^bytes [r link]
  (let [out (ByteArrayOutputStream.)]
    (write-file! r link out)
    (.toByteArray out)))
