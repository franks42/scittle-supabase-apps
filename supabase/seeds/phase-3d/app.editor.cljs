(ns app.editor
  "Windowed editor for ns_modules rows.

  Phase 3b. Open with (open! \"app.utils\"). The editor:
   - Fetches the current source from ns_modules_current.
   - Lets the user edit in CodeMirror 6.
   - On Save:
       * ensures an auth session (anonymous sign-in for now),
       * fetches ns_impact (the namespaces this change will affect),
       * INSERTs a new (name, version+1) row,
       * re-evaluates the new source via scittle.core.eval_string into
         the running sci context.
   - Status bar shows ns name, current version, char count, dirty/clean,
     and the most recent save timestamp."
  (:require
   [clojure.string :as str]
   [reagent.core :as r]
   [app.supa :as supa]
   [app.auth :as auth]
   [app.parser :as parser]
   [app.wm :as wm]
   [app.cm6 :as cm6]))

;; ---- helpers ---------------------------------------------------------------

(defn- fetch-current
  "Promise -> {:name :version :source :deps} or rejects with Error."
  [ns-name]
  (-> (.. (supa/client) (from "ns_modules_current"))
      (.select "name,version,source,deps")
      (.eq "name" ns-name)
      (.maybeSingle)
      (.then (fn [r]
               (when-let [err (.-error r)]
                 (throw err))
               (when-let [data (.-data r)]
                 (js->clj data :keywordize-keys true))))))

(defn- fetch-impact
  "Promise -> vector of namespace names that depend (transitively) on
  ns-name and would be affected if it changes."
  [ns-name]
  (-> (.. (supa/client) (rpc "ns_impact" #js {:target ns-name}))
      (.then (fn [r]
               (when-let [err (.-error r)]
                 (throw err))
               (mapv :name (js->clj (.-data r) :keywordize-keys true))))))

(defn- insert-version!
  "INSERT a new (name, version+1) row. Promise resolves with the new
  version number. Caller must have ensured an auth session first."
  [ns-name new-source deps prior-version]
  (let [next-v (inc prior-version)]
    (-> (.. (supa/client) (from "ns_modules"))
        (.insert (clj->js {:name ns-name
                           :version next-v
                           :source new-source
                           :deps (or deps [])}))
        (.then (fn [r]
                 (when-let [err (.-error r)]
                   (throw err))
                 next-v)))))

(defn- re-eval!
  "Re-evaluate the saved source into the running sci context.
  scittle is a global registered by scittle.js."
  [source]
  (.eval_string js/scittle.core source))

;; ---- save flow as a Promise chain ------------------------------------------

(defn- save!
  "Run the save flow. Deps are derived from draft-source via app.parser
  — author-supplied deps on the loaded row are not used. Returns a
  Promise resolving to a map:
     {:version  new-version-number
      :impact   [affected-ns ...]
      :deps     [parsed-ns ...]}
   Throws on failure."
  [ns-name draft-source prior-version]
  (let [deps (parser/parse-deps draft-source)]
    (-> (auth/ensure-signed-in!)
        (.then (fn [_]
                 (-> (fetch-impact ns-name)
                     (.then (fn [impact]
                              (-> (insert-version! ns-name draft-source deps prior-version)
                                  (.then (fn [v]
                                           (re-eval! draft-source)
                                           {:version v :impact impact :deps deps})))))))))))

;; ---- Reagent UI ------------------------------------------------------------

(defn- status-bar
  [{:keys [ns-name version dirty? char-count last-saved-at error user-email]}]
  [:div {:style {:padding "0.4rem 0.6rem"
                 :border-top "1px solid #ddd"
                 :background "#f5f5f5"
                 :font-family "ui-monospace,monospace"
                 :font-size "0.8rem"
                 :color "#444"
                 :display "flex"
                 :gap "1rem"
                 :flex-wrap "wrap"}}
   [:span [:strong ns-name] (when version (str " v" version))]
   [:span (str char-count " chars")]
   [:span {:style {:color (if dirty? "#b45309" "#15803d")}}
    (if dirty? "● dirty" "✓ clean")]
   (when last-saved-at
     [:span {:style {:color "#555"}} (str "saved " last-saved-at)])
   (when user-email
     [:span {:style {:color "#555"}} user-email])
   (when error
     [:span {:style {:color "#b00020"}} error])])

(defn- editor-window
  "Body of the editor WinBox. Holds the draft + save flow."
  [ns-name initial-row]
  (let [!draft (r/atom (:source initial-row))
        !dirty? (r/atom false)
        !version (r/atom (:version initial-row))
        !error (r/atom nil)
        !saved-at (r/atom nil)
        !saving? (r/atom false)
        !impact (r/atom nil)
        on-change (fn [v]
                    (reset! !draft v)
                    (reset! !dirty? (not= v (:source initial-row))))
        on-save (fn []
                  (when-not @!saving?
                    (reset! !saving? true)
                    (reset! !error nil)
                    (-> (save! ns-name @!draft @!version)
                        (.then (fn [r]
                                 (reset! !version (:version r))
                                 (reset! !impact (:impact r))
                                 (reset! !dirty? false)
                                 (reset! !saved-at (.toLocaleTimeString (js/Date.)))
                                 (reset! !saving? false)))
                        (.catch (fn [e]
                                  (js/console.error e)
                                  (reset! !error (.-message e))
                                  (reset! !saving? false))))))]
    (fn []
      [:div {:style {:height "100%"
                     :display "flex"
                     :flex-direction "column"}}
       [:div {:style {:padding "0.4rem 0.6rem"
                      :background "#fafafa"
                      :border-bottom "1px solid #ddd"
                      :display "flex"
                      :gap "0.5rem"
                      :align-items "center"}}
        [:button {:on-click on-save
                  :disabled (or @!saving? (not @!dirty?))
                  :style {:background (if @!dirty? "#0f766e" "#cbd5e1")
                          :color "white"
                          :border "none"
                          :padding "0.3rem 0.8rem"
                          :border-radius 3
                          :cursor (if (or @!saving? (not @!dirty?)) "not-allowed" "pointer")
                          :font-size "0.85rem"}}
         (cond @!saving? "Saving…"
               @!dirty?  (str "Save " ns-name)
               :else     "Saved")]
        (when (seq @!impact)
          [:span {:style {:font-size "0.8rem" :color "#92400e"}}
           "⚠ Last save affected: "
           (str/join ", " @!impact)])]
       ;; Live-derived requires from the current draft. Updates on
       ;; every keystroke; this is what gets persisted as the row's
       ;; deps on Save (overriding any author-supplied deps).
       (let [parsed (parser/parse-deps @!draft)]
         [:div {:style {:padding "0.3rem 0.6rem"
                        :background "#f0f9ff"
                        :border-bottom "1px solid #ddd"
                        :font-family "ui-monospace,monospace"
                        :font-size "0.75rem"
                        :color "#0369a1"}}
          (if (seq parsed)
            (str "Detected requires: " (str/join ", " parsed))
            "Detected requires: (none)")])
       [:div {:style {:flex "1 1 auto" :min-height 0}}
        [cm6/editor {:value @!draft
                     :on-change on-change
                     :language :clojure}]]
       [status-bar
        {:ns-name ns-name
         :version @!version
         :dirty? @!dirty?
         :char-count (count @!draft)
         :last-saved-at @!saved-at
         :user-email (some-> @auth/!user .-email)
         :error @!error}]])))

(defn open!
  "Open an editor window on the given namespace. Fetches the current
  source asynchronously; opens an empty window with a 'loading' note
  first, replaces it with the editor once the row arrives."
  [ns-name]
  (-> (fetch-current ns-name)
      (.then (fn [row]
               (if row
                 (wm/open-window!
                  {:title (str "Editor — " ns-name)
                   :width 760
                   :height 520
                   :background "#0f766e"
                   :content [editor-window ns-name row]})
                 (js/alert (str "No row for namespace: " ns-name)))))
      (.catch (fn [e]
                (js/console.error e)
                (js/alert (str "Could not load " ns-name ": " (.-message e)))))))
