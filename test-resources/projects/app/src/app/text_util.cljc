(ns app.text-util
  (:require [clojure.string :as str]))

(defn shout [s] #?(:clj (str/upper-case s) :cljs s))
