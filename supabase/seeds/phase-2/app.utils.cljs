(ns app.utils)

(defn shout [s]
  (.toUpperCase s))

(defn add [a b]
  (+ a b))

(defn now-string []
  (subs (.toISOString (js/Date.)) 11 19))
