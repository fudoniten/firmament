(ns wordfreq.core
  "Counting words: pure functions, no I/O."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def stopwords
  "Words too common to be interesting, from the stopwords.txt resource."
  (delay (->> (slurp (io/resource "wordfreq/stopwords.txt"))
              str/split-lines
              (map str/trim)
              (remove #(or (str/blank? %) (str/starts-with? % "#")))
              set)))

(defn words
  "The words in `text`, lower-cased: runs of letters, digits and
  apostrophes."
  [text]
  (map str/lower-case (re-seq #"[\p{L}\p{N}']+" text)))

(defn frequencies-of
  "A map of word to count for `text`, without stopwords unless `:all?`."
  [text & {:keys [all?]}]
  (frequencies (cond->> (words text)
                 (not all?) (remove @stopwords))))

(defn top
  "The `n` most frequent [word count] pairs in `freqs`, most frequent first,
  ties broken alphabetically."
  [n freqs]
  (take n (sort-by (fn [[w c]] [(- c) w]) freqs)))
