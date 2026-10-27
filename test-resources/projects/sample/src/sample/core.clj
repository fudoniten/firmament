;; A comment before the ns form.
(ns sample.core
  "The entry point."
  (:require [clojure.string :as str]
            [sample.util :as util]
            (sample [my-app :as app]))
  (:import (java.util UUID)))

(defn -main [& _] (println (str/upper-case (util/greet))))
