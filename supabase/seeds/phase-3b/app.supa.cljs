(ns app.supa
  "Singleton supabase-js client. URL and publishable key are read from
  window.__SUPA_CONFIG__ (populated by shell.html at boot time).

  Caching on window.__SUPA_CLIENT__ so multiple namespaces share one
  instance and one auth/session state.")

(defn client
  "Return the singleton supabase-js client, creating it on first call."
  []
  (when-not (.-__SUPA_CLIENT__ js/window)
    (let [cfg (js->clj (.-__SUPA_CONFIG__ js/window) :keywordize-keys true)]
      (set! (.-__SUPA_CLIENT__ js/window)
            (.createClient js/supabase (:url cfg) (:anonKey cfg)))))
  (.-__SUPA_CLIENT__ js/window))
