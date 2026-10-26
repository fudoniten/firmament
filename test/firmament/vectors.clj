(ns firmament.vectors
  "Loading the golden vectors in test-resources/vectors/."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [firmament.edn :as fedn])
  (:import (java.util HexFormat)))

(defn load-vectors
  "The vector file `name` (e.g. \"encoding.edn\") as data."
  [name]
  (let [r (or (io/resource (str "vectors/" name))
              (throw (ex-info (str "vector file not on classpath: " name) {})))]
    (edn/read-string {:readers fedn/readers} (slurp r))))

(defn unhex ^bytes [^String s] (.parseHex (HexFormat/of) s))

(defn hex [^bytes b] (.formatHex (HexFormat/of) b))
