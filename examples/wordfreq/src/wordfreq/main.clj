(ns wordfreq.main
  "The command line: wordfreq [--json] [--top N] [FILE...]. With no files,
  counts the sample text bundled as a resource."
  (:require [clojure.java.io :as io]
            [wordfreq.core :as core]
            [wordfreq.report :as report]))

(defn- parse-args [args]
  (loop [args args opts {:top 10 :files []}]
    (let [[a b & more] args]
      (cond
        (nil? a) opts
        (= "--json" a) (recur (rest args) (assoc opts :json? true))
        (= "--all" a) (recur (rest args) (assoc opts :all? true))
        (= "--top" a) (recur more (assoc opts :top (parse-long b)))
        :else (recur (rest args) (update opts :files conj a))))))

(defn run
  "The report for `args`, as a string."
  [args]
  (let [{:keys [top json? all? files]} (parse-args args)
        text (if (seq files)
               (apply str (map slurp files))
               (slurp (io/resource "wordfreq/sample.txt")))
        pairs (core/top top (core/frequencies-of text :all? all?))]
    (if json? (report/as-json pairs) (report/text pairs))))

(defn -main [& args]
  (println (run args)))
