(ns app.main
  "Phase 3b demo. Lists the namespaces in ns_modules; clicking one
  opens an editor window for it. The editor saves new versions back
  into the database and re-evaluates the new source into the running
  sci context — so editing app.utils and re-clicking 'Open Clojure
  editor' on a future revision will see the updated definitions."
  (:require
   [reagent.core :as r]
   [reagent.dom :as rdom]
   [clojure.string :as str]
   [app.utils :as u]
   [app.supa :as supa]
   [app.auth :as auth]
   [app.editor :as ed]))

(defonce !state
  (r/atom {:stamp (u/now-string)
           :namespaces nil
           :loading? true
           :error nil}))

(defn- refresh-namespaces! []
  (swap! !state assoc :loading? true :error nil)
  (-> (.. (supa/client) (from "ns_modules_current"))
      (.select "name,version,description,updated_at")
      (.order "name")
      (.then (fn [r]
               (if-let [err (.-error r)]
                 (swap! !state assoc :loading? false :error (.-message err))
                 (let [rows (js->clj (.-data r) :keywordize-keys true)]
                   (swap! !state assoc :loading? false :namespaces rows)))))
      (.catch (fn [e]
                (swap! !state assoc :loading? false :error (.-message e))))))

(defn- ns-row [{:keys [name version description]}]
  [:li {:key name
        :style {:padding "0.5rem 0.6rem"
                :border "1px solid #e5e7eb"
                :border-radius 4
                :margin-bottom "0.4rem"
                :display "flex"
                :align-items "center"
                :justify-content "space-between"
                :background "white"}}
   [:div
    [:div {:style {:font-family "ui-monospace,monospace"
                   :font-weight 600}}
     name " " [:span {:style {:color "#888" :font-weight 400}} (str "v" version)]]
    (when description
      [:div {:style {:font-size "0.8rem" :color "#666"}}
       description])]
   [:button {:on-click #(ed/open! name)
             :style {:background "#0f766e"
                     :color "white"
                     :border "none"
                     :padding "0.35rem 0.8rem"
                     :border-radius 3
                     :cursor "pointer"
                     :font-size "0.85rem"}}
    "Open editor"]])

(defn root []
  (let [{:keys [namespaces loading? error stamp]} @!state
        signed-in? (some? @auth/!user)]
    [:div
     [:h2 "Scittle on Supabase — Phase 3b"]
     [:p {:style {:font-size "0.85rem" :color "#666"}}
      "Loaded from ns_modules at " stamp ". "
      "Click an entry to open it in an editor; saves write a new "
      "version row and re-evaluate the source into the running sci "
      "context."]
     [:div {:style {:font-size "0.8rem"
                    :color (if signed-in? "#15803d" "#92400e")
                    :margin-bottom "0.8rem"}}
      (if signed-in?
        (str "✓ signed in (anon, uid=" (some-> @auth/!user .-id (subs 0 8)) "…)")
        "○ not signed in — sign-in happens automatically on first save")]
     [:div {:style {:margin-bottom "0.6rem"}}
      [:button {:on-click #(refresh-namespaces!)
                :style {:padding "0.3rem 0.7rem"
                        :font-size "0.85rem"}}
       "Refresh list"]]
     (cond
       loading?  [:div "Loading namespaces…"]
       error     [:div {:style {:color "#b00020"}} "Error: " error]
       (empty? namespaces)
       [:div {:style {:color "#888"}} "(no namespaces yet)"]
       :else
       [:ul {:style {:list-style "none" :padding 0 :margin 0}}
        (for [row namespaces]
          ^{:key (:name row)} [ns-row row])])
     [:p {:style {:font-size "0.8rem" :color "#888" :margin-top "1rem"}}
      [:em
       "Available: " (str/join ", " (map :name namespaces))]]]))

(defn ^:export start! []
  (auth/install-auth-listener!)
  (refresh-namespaces!)
  (rdom/render [root] (.getElementById js/document "app")))
