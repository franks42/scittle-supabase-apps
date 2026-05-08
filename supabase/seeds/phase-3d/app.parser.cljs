(ns app.parser
  "Extract namespace deps from cljs source, so authors don't have to
  hand-maintain the `deps` column on ns_modules.

  Phase 3d uses clojure.edn/read-string to parse the leading (ns ...)
  form and walks every (:require ...) clause. Handles the common
  shapes:

    [foo.bar]
    [foo.bar :as f]
    [foo.bar :refer [...]]
    foo.bar                    ; bare symbol
    :reload / :reload-all      ; flag keywords — skipped

  Limitations:
   - Source must START with the (ns ...) form (no top-level comments
     before it). edn/read-string returns the FIRST form.
   - edn doesn't recognize cljs reader macros like #js or ^:meta. If
     they appear inside the (ns ...) form (uncommon), edn rejects the
     whole form and parse-deps returns []. Refs/requires elsewhere in
     the file are not parsed (they wouldn't be valid Clojure anyway —
     ns is a top-level form)."
  (:require
   [clojure.edn :as edn]))

(defn- ns-form? [form]
  (and (list? form) (= 'ns (first form))))

(defn- require-clause? [form]
  (and (sequential? form) (= :require (first form))))

(defn- ns-of-entry
  "Pull the namespace symbol out of a single :require entry. Returns
  nil for flag keywords, malformed entries, etc."
  [entry]
  (cond
    (symbol? entry) (str entry)
    (and (vector? entry) (symbol? (first entry))) (str (first entry))
    :else nil))

(defn parse-deps
  "Return a vector of namespace name strings declared in the leading
  (ns ...) form of `source`. Empty vector if no (ns ...) form is
  found, no :require clauses are present, or edn parsing fails."
  [source]
  (let [form (try (edn/read-string source) (catch :default _ nil))]
    (if-not (ns-form? form)
      []
      (->> (rest form)
           (filter require-clause?)
           (mapcat rest)
           (keep ns-of-entry)
           distinct
           vec))))
