(ns boot
  "Phase 2 bootstrap in Scittle ClojureScript.

  Runs after scittle.js and supabase-js have loaded via <script> tags.
  Reads window.__SUPA_CONFIG__ (set by the Edge Function shell), pulls
  the requested root namespace's closure from ns_modules, fetches any
  Scittle plugin bundles the closure needs (per the manifest in
  libs/), evaluates everything in topo order, and calls (root/start!).

  No (:require ...) for Reagent/Promesa/etc. at this level — those are
  exactly the plugins we are loading on demand. Uses JS interop and
  raw js/Promise."
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

(def sb (.createClient js/supabase supabase-url anon-key))
(def loaded-plugins (atom #{}))
(def app-el (.getElementById js/document "app"))

;; Indirect eval so loaded plugin bundles register on the global scope.
(def global-eval (.-eval js/window))

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
      (-> (.rpc sb name (clj->js params))
          (.then (fn [result]
                   (when-let [err (.-error result)]
                     (throw (js/Error.
                             (str "rpc " name " -> HTTP "
                                  (or (.-status err) "?") ": "
                                  (or (.-message err) "(no message)")))))
                   result))))))

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

(defn fetch-and-eval-bundle!
  "Fetch a JS bundle and eval it into the global scope. The
  //# sourceURL line gives DevTools clean attribution."
  [url label]
  (-> (fetch-text url)
      (.then (fn [src]
               (global-eval (str src "\n//# sourceURL=" label))))))

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
  (-> (fetch-json manifest-url)
      (.then (fn [manifest]
               (-> (rpc! "ns_closure" {:root root-ns})
                   (.then #(handle-rpc-result manifest %)))))
      (.catch (fn [e]
                (js/console.error e)
                (render-error!
                 (str "Could not load " root-ns ".")
                 (.-message e))))))

(boot!)
