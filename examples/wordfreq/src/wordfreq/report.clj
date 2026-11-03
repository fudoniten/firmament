(ns wordfreq.report
  "Formatting results as a text table or JSON."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(defn text
  "A text table of [word count] pairs, counts right-aligned."
  [pairs]
  (let [width (reduce max 1 (map (comp count str second) pairs))]
    (str/join "\n" (for [[w c] pairs]
                     (str (format (str "%" width "d") c) "  " w)))))

(defn as-json
  "The [word count] pairs as a JSON array of {\"word\": w, \"count\": c}."
  [pairs]
  (json/write-str (for [[w c] pairs] {:word w :count c})))
