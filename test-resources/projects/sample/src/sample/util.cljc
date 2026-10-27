(ns sample.util
  (:require #?(:clj [clojure.java.io :as io]
               :cljs [goog.string :as gstr])
            #?@(:cljs [["react" :as react]])
            [clojure.set :as set]))

(defn greet [] "hello")
