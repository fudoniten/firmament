(ns app.core
  "Loaded and run from the store by the loader and runner tests."
  (:require [app.platform :as platform]
            [app.text-util :as tu]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [greeting.core :as greeting]))

(defn config [] (edn/read-string (slurp (io/resource "app/config.edn"))))

(defn banner [] (slurp (io/resource "greeting/banner.txt")))

(defn hello [] (tu/shout (greeting/greet (:name (config)))))

(defn platform [] platform/which)

(defn -main
  "Prints what it was given, after the greeting: command-line strings, or a
  parameters value."
  [& args]
  (prn (into [(hello)] args)))
