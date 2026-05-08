(ns app.launcher
  "Phase 3c launcher.

  Lists every published row in the `apps` table and, on click, closes
  any open WinBox windows and dispatches to that app via the global
  window.__dispatch__ function installed by boot.cljs. Sci context is
  shared across app switches, so the chosen app simply renders into
  #app, replacing the launcher's UI. A page reload returns here."
  (:require
   [reagent.core :as r]
   [reagent.dom :as rdom]
   [app.supa :as supa]
   [app.auth :as auth]
   [app.wm :as wm]))

(defonce !state
  (r/atom {:apps nil :loading? true :error nil :launching nil}))

(defn- refresh-apps! []
  (swap! !state assoc :loading? true :error nil)
  (-> (.. (supa/client) (from "apps"))
      (.select "id,display_name,description,root_ns,background")
      (.eq "published" true)
      (.order "display_name")
      (.then (fn [r]
               (if-let [err (.-error r)]
                 (swap! !state assoc :loading? false :error (.-message err))
                 (swap! !state assoc
                        :loading? false
                        :apps (js->clj (.-data r) :keywordize-keys true)))))
      (.catch (fn [e]
                (swap! !state assoc :loading? false :error (.-message e))))))

(defn- launch! [{:keys [root_ns]}]
  (when-let [dispatch (.-__dispatch__ js/window)]
    (wm/close-all!)
    ;; Show "launching" via Reagent state — DON'T mutate #app DOM
    ;; directly. React's reconciler can swap the launcher tree for the
    ;; chosen app's tree on its own, but only if the DOM stays the
    ;; shape it last rendered.
    (swap! !state assoc :launching root_ns :error nil)
    (-> (dispatch root_ns)
        (.catch (fn [e]
                  (js/console.error e)
                  (swap! !state assoc :launching nil
                         :error (str "Failed to launch " root_ns
                                     ": " (.-message e))))))))

(defn- app-card [app]
  [:div {:on-click #(launch! app)
         :style {:padding "0.9rem 1rem"
                 :border "1px solid #e5e7eb"
                 :border-left (str "4px solid " (or (:background app) "#0f766e"))
                 :border-radius 6
                 :margin-bottom "0.6rem"
                 :background "white"
                 :cursor "pointer"
                 :transition "background 120ms"}}
   [:div {:style {:display "flex"
                  :justify-content "space-between"
                  :align-items "baseline"
                  :gap "1rem"}}
    [:div {:style {:font-weight 600 :font-size "1rem"}}
     (:display_name app)]
    [:div {:style {:font-family "ui-monospace,monospace"
                   :font-size "0.75rem"
                   :color "#aaa"}}
     (:id app)]]
   (when-let [d (:description app)]
     [:div {:style {:color "#666" :font-size "0.85rem" :margin-top "0.25rem"}} d])
   [:div {:style {:font-family "ui-monospace,monospace"
                  :font-size "0.75rem"
                  :color "#888"
                  :margin-top "0.4rem"}}
    "→ " (:root_ns app)]])

(defn- root []
  (let [{:keys [apps loading? error launching]} @!state
        signed-in? (some? @auth/!user)]
    [:div
     [:h2 "Scittle on Supabase — Launcher"]
     [:p {:style {:color "#666" :font-size "0.9rem"}}
      "Each row below is an app stored in "
      [:code "ns_modules"] " — pick one to evaluate it into the running "
      "sci context."]
     [:div {:style {:font-size "0.8rem"
                    :color (if signed-in? "#15803d" "#92400e")
                    :margin-bottom "0.8rem"}}
      (if signed-in?
        (str "✓ signed in (anon, uid="
             (some-> @auth/!user .-id (subs 0 8)) "…)")
        "○ not signed in — sign-in happens automatically on first save")]
     [:div {:style {:margin-bottom "0.6rem"}}
      [:button {:on-click #(refresh-apps!)
                :style {:padding "0.3rem 0.7rem" :font-size "0.85rem"}}
       "Refresh"]]
     (cond
       launching [:div {:style {:color "#0f766e"
                                :font-style "italic"}}
                  "Launching " launching " …"]
       loading?  [:div "Loading apps…"]
       error     [:div {:style {:color "#b00020"}} error]
       (empty? apps) [:div {:style {:color "#888"}}
                      "No apps yet — insert a row in the apps table."]
       :else [:div
              (for [app apps]
                ^{:key (:id app)} [app-card app])])]))

(defn ^:export start! []
  (auth/install-auth-listener!)
  (refresh-apps!)
  (rdom/render [root] (.getElementById js/document "app")))
