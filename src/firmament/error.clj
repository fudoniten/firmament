(ns firmament.error
  "How Firmament reports errors: ex-info whose data carries
  :firmament/error, a keyword naming the layer that failed (:store, :tree,
  :encode, ...).")

(defn fail
  "Throw ex-info with message `msg` and `data`, tagged with
  {:firmament/error kind}."
  ([kind msg] (fail kind msg {}))
  ([kind msg data]
   (throw (ex-info msg (assoc data :firmament/error kind)))))
