(ns boot
  "Bootstrap in Scittle ClojureScript.

  This file is fetched and eval'd by the inline x-scittle stub in
  shell.html. Everything else — supabase-js, React, ReactDOM, WinBox,
  WinBox CSS, CodeMirror 6 — is loaded from this file using js/fetch
  and indirect eval (or dynamic ESM imports for CM6, via the
  window.__esm_import__ helper installed by the shell HTML).

  Then the standard flow continues: read the plugin manifest, call
  ns_closure on the root namespace, fetch missing Scittle plugin
  bundles, topo-sort and eval the cljs closure, call (root/start!)."
  (:require
   [clojure.string :as str]))

(declare boot!)

;; ---- config + URLs ---------------------------------------------------------

(def cfg (js->clj (.-__SUPA_CONFIG__ js/window) :keywordize-keys true))
(def supabase-url (:url cfg))
(def anon-key (:anonKey cfg))
(def root-ns (:rootNamespace cfg))
(def storage-base (str supabase-url "/storage/v1/object/public"))
(def libs-base (str storage-base "/libs"))
(def manifest-url (str libs-base "/plugin-manifest.json"))

(def loaded-plugins (atom #{}))
(def app-el (.getElementById js/document "app"))

;; Indirect eval so loaded JS bundles register on the global scope.
(def global-eval (.-eval js/window))

;; The supabase client is created lazily after supabase-js has been
;; loaded by load-eager-deps!. Stored on the JS global so other cljs
;; namespaces can reach it through app.supa without a direct require
;; on this ns.
(defn sb
  "Return the singleton supabase-js client, throwing if it hasn't been
  initialized yet."
  []
  (or (.-__SUPA_CLIENT__ js/window)
      (throw (js/Error. "supabase client not initialized — load-eager-deps! must run first"))))

;; Install the window.CM6 Promise so any cljs component (e.g. app.cm6)
;; can await CM6 readiness regardless of when it's mounted relative to
;; load-cm6!. load-cm6! resolves __CM6_RESOLVE__ with the populated
;; globalThis.CM.
(when-not (.-CM6 js/window)
  (let [!resolve (atom nil)
        p (js/Promise. (fn [resolve _] (reset! !resolve resolve)))]
    (set! (.-CM6 js/window) p)
    (set! (.-__CM6_RESOLVE__ js/window) @!resolve)))

;; ---- retry -----------------------------------------------------------------

(def ^:private max-retries 3)
(def ^:private retry-base-ms 250)

(defn- sleep [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(defn- transient-error?
  "True for HTTP 5xx, network errors, and other likely-transient failures.
  Does not retry 4xx (auth, missing rows, malformed request) — those are
  the caller's problem and won't fix themselves."
  [err]
  (let [msg (or (.-message err) (str err))
        m (re-matches #".*HTTP (\d+).*" msg)]
    (if m
      (>= (js/parseInt (second m) 10) 500)
      true)))

(defn- with-retry
  "Wrap a Promise-returning thunk in exponential-backoff retry. Up to
  max-retries attempts total. Only retries transient-error?. Logs each
  retry to the console so a hung boot is visible during development."
  [label thunk]
  (letfn [(attempt [n]
            (-> (thunk)
                (.catch (fn [err]
                          (if (and (< n max-retries) (transient-error? err))
                            (let [delay (* retry-base-ms (js/Math.pow 2 (dec n)))]
                              (js/console.warn
                               (str "retry " n "/" (dec max-retries) " for "
                                    label " after " delay "ms: " (.-message err)))
                              (-> (sleep delay)
                                  (.then (fn [_] (attempt (inc n))))))
                            (throw err))))))]
    (attempt 1)))

;; ---- async helpers ---------------------------------------------------------

(defn fetch-text [url]
  (with-retry
    (str "GET " url)
    (fn []
      (-> (js/fetch url)
          (.then (fn [r]
                   (if (.-ok r)
                     (.text r)
                     (throw (js/Error.
                             (str "fetch " url " -> HTTP " (.-status r)))))))))))

(defn fetch-json [url]
  (-> (fetch-text url)
      (.then (fn [s] (js->clj (.parse js/JSON s) :keywordize-keys true)))))

(defn rpc!
  "Call a PostgREST RPC and convert result.error into a thrown Error so
  with-retry can decide whether to retry. supabase-js resolves the
  Promise even on HTTP error and tucks the error into result.error,
  which would otherwise bypass the retry layer."
  [name params]
  (with-retry
    (str "RPC " name)
    (fn []
      (-> (.rpc (sb) name (clj->js params))
          (.then (fn [result]
                   (when-let [err (.-error result)]
                     (throw (js/Error.
                             (str "rpc " name " -> HTTP "
                                  (or (.-status err) "?") ": "
                                  (or (.-message err) "(no message)")))))
                   result))))))

;; ---- eager-load helpers ----------------------------------------------------

(defn fetch-and-eval-bundle!
  "Fetch a classic-script JS bundle and indirect-eval it into the global
  scope. The //# sourceURL line gives DevTools clean attribution."
  [url label]
  (-> (fetch-text url)
      (.then (fn [src]
               (global-eval (str src "\n//# sourceURL=" label))))))

(defn- inject-stylesheet!
  "Inject a <link rel='stylesheet'> into <head>. Returns immediately;
  CSS apply is async but not blocking for our purposes."
  [url]
  (let [link (.createElement js/document "link")]
    (set! (.-rel link) "stylesheet")
    (set! (.-href link) url)
    (.appendChild (.-head js/document) link)
    nil))

(defn- esm-import
  "Dynamic ESM import. sci can't emit `import(url)` directly, so the
  shell HTML installs window.__esm_import__ as a one-line wrapper:
    window.__esm_import__ = (url) => import(url);"
  [url]
  ((.-__esm_import__ js/window) url))

(defn- load-cm6!
  "Resolve CodeMirror 6 modules from esm.sh and stash them on
  globalThis.CM. Resolves the window.CM6 Promise that
  app.cm6 awaits before mounting an editor."
  []
  (let [v "6.6.0"
        url-with-deps (fn [pkg]
                        (str "https://esm.sh/" pkg
                             "?deps=@codemirror/state@" v))]
    (-> (js/Promise.all
         #js [(esm-import (str "https://esm.sh/@codemirror/state@" v))
              (esm-import (url-with-deps "@codemirror/view"))
              (esm-import (url-with-deps "codemirror"))
              (esm-import (url-with-deps "@codemirror/commands"))
              (esm-import (url-with-deps "@nextjournal/lang-clojure"))])
        (.then (fn [mods]
                 (let [s (aget mods 0)
                       vw (aget mods 1)
                       cm (aget mods 2)
                       cmds (aget mods 3)
                       clj (aget mods 4)
                       cm-obj #js {:EditorState   (.-EditorState s)
                                   :Compartment   (.-Compartment s)
                                   :EditorView    (.-EditorView vw)
                                   :keymap        (.-keymap vw)
                                   :basicSetup    (.-basicSetup cm)
                                   :defaultKeymap (.-defaultKeymap cmds)
                                   :history       (.-history cmds)
                                   :historyKeymap (.-historyKeymap cmds)
                                   :clojure       (.-clojure clj)}]
                   (set! (.-CM js/globalThis) cm-obj)
                   (when-let [resolve-fn (.-__CM6_RESOLVE__ js/window)]
                     (resolve-fn cm-obj))
                   cm-obj))))))

(defn- load-eager-deps!
  "Pull every remaining browser dependency from libs/. Parallel where
  the runtime allows; React must come before ReactDOM because the UMD
  wrapper for react-dom captures globalThis.React at evaluate time."
  []
  (let [sb-bundle  (str libs-base "/supabase-js-2.45.4.js")
        react-url  (str libs-base "/react-18.3.1.production.min.js")
        react-dom  (str libs-base "/react-dom-18.3.1.production.min.js")
        winbox-js  (str libs-base "/winbox-0.2.82.bundle.min.js")
        winbox-css (str libs-base "/winbox-0.2.82.min.css")]
    (inject-stylesheet! winbox-css)
    (-> (js/Promise.all
         #js [(fetch-and-eval-bundle! sb-bundle "supabase-js.js")
              (-> (fetch-and-eval-bundle! react-url "react.js")
                  (.then (fn [_]
                           (fetch-and-eval-bundle! react-dom "react-dom.js"))))
              (fetch-and-eval-bundle! winbox-js "winbox.js")
              (load-cm6!)])
        (.then (fn [_]
                 (set! (.-__SUPA_CLIENT__ js/window)
                       (.createClient js/supabase supabase-url anon-key))
                 nil)))))

;; ---- plugin resolution -----------------------------------------------------

(defn external-deps
  "Deps referenced by closure rows that are not themselves rows in the
  closure — i.e., deps that are plugin namespaces."
  [rows]
  (let [internal (set (map :name rows))]
    (->> rows
         (mapcat :deps)
         (remove internal)
         distinct)))

(defn plugin-for
  "Return the plugin entry whose ns_prefixes match ns-name, or nil."
  [ns-name manifest]
  (some (fn [plugin]
          (when (some (fn [prefix]
                        (or (= ns-name (str/replace prefix #"\.$" ""))
                            (str/starts-with? ns-name prefix)))
                      (:ns_prefixes plugin))
            plugin))
        (:plugins manifest)))

(defn ensure-plugins!
  "Sequentially fetch+eval every plugin bundle the closure needs that
  isn't already loaded. Returns a Promise that resolves when done."
  [rows manifest]
  (let [needed (->> (external-deps rows)
                    (keep #(plugin-for % manifest))
                    (remove #(@loaded-plugins (:id %)))
                    distinct
                    vec)]
    (reduce (fn [p plugin]
              (.then p (fn [_]
                         (-> (fetch-and-eval-bundle!
                              (str libs-base "/" (:bundle plugin))
                              (str (:id plugin) ".js"))
                             (.then (fn [_]
                                      (swap! loaded-plugins conj (:id plugin))))))))
            (js/Promise.resolve nil)
            needed)))

;; ---- topo sort + eval ------------------------------------------------------

(defn topo-sort
  "Topo-sort a closure starting from root-name. by-name maps namespace
  names to row maps with :deps."
  [root-name by-name]
  (let [visited (volatile! #{})
        order (volatile! [])]
    (letfn [(visit [name]
              (when-not (@visited name)
                (vswap! visited conj name)
                (when-let [row (get by-name name)]
                  (run! visit (:deps row))
                  (vswap! order conj name))))]
      (visit root-name))
    @order))

(defn eval-closure!
  "Eval rows in topo order, then call (root-ns/start!)."
  [rows]
  (let [by-name (into {} (map (juxt :name identity)) rows)
        order (topo-sort root-ns by-name)]
    (doseq [n order]
      (.eval_string js/scittle.core (:source (get by-name n))))
    (.eval_string js/scittle.core (str "(" root-ns "/start!)"))))

;; ---- error UI --------------------------------------------------------------

(defn render-error! [msg cause]
  (set! (.-innerHTML app-el)
        (str "<div class=\"err\"><strong>Boot failed.</strong><br>"
             msg
             (when cause (str "<br>" cause))
             "<br><br><button id=\"retry\">Retry</button></div>"))
  (.addEventListener (.getElementById js/document "retry")
                     "click"
                     (fn [_]
                       (set! (.-textContent app-el) "Booting…")
                       (boot!))))

;; ---- main ------------------------------------------------------------------

(defn handle-rpc-result
  "rpc! has already converted result.error into a thrown Error, so here
  we only need to unwrap the rows and ensure plugins + eval the closure."
  [manifest result]
  (let [rows (js->clj (.-data result) :keywordize-keys true)]
    (when (zero? (count rows))
      (throw (js/Error. (str "ns_closure(" root-ns ") returned no rows"))))
    (-> (ensure-plugins! rows manifest)
        (.then (fn [_] (eval-closure! rows))))))

(defn boot! []
  (-> (load-eager-deps!)
      (.then (fn [_] (fetch-json manifest-url)))
      (.then (fn [manifest]
               (-> (rpc! "ns_closure" {:root root-ns})
                   (.then #(handle-rpc-result manifest %)))))
      (.catch (fn [e]
                (js/console.error e)
                (render-error!
                 (str "Could not load " root-ns ".")
                 (.-message e))))))

(boot!)
