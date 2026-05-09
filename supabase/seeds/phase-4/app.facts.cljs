(ns app.facts
  "Phase 4 demo: shows the two server-side HTTP patterns side-by-side.

  Scheduled ingestion: pg_cron runs enqueue_cat_fact_fetch every 5
  minutes; harvest_cat_facts runs every minute and inserts the
  resolved responses into external_cat_facts. The list below is a
  read of that table.

  Live proxy: the 'Fetch fresh' button invokes the cat-fact-proxy
  Edge Function, which fetches catfact.ninja and returns the JSON.
  The result is prepended to the displayed list as a 'live' row
  (it does not write back to the database — that's deliberate, the
  scheduled ingester owns the table)."
  (:require
   [reagent.core :as r]
   [reagent.dom :as rdom]
   [app.supa :as supa]
   [app.auth :as auth]))

(defonce !state
  (r/atom {:rows nil
           :loading? true
           :error nil
           :live []      ; results from the live proxy, newest first
           :proxying? false
           :proxy-error nil}))

(defn- refresh-rows! []
  (swap! !state assoc :loading? true :error nil)
  (-> (.. (supa/client) (from "external_cat_facts"))
      (.select "id,fact,length,fetched_at")
      (.order "fetched_at" #js {:ascending false})
      (.limit 25)
      (.then (fn [r]
               (if-let [err (.-error r)]
                 (swap! !state assoc :loading? false :error (.-message err))
                 (swap! !state assoc
                        :loading? false
                        :rows (js->clj (.-data r) :keywordize-keys true)))))
      (.catch (fn [e]
                (swap! !state assoc :loading? false :error (.-message e))))))

(defn- fetch-fresh! []
  (when-not (:proxying? @!state)
    (swap! !state assoc :proxying? true :proxy-error nil)
    (-> (auth/ensure-signed-in!)
        (.then (fn [_]
                 (.invoke (.-functions (supa/client))
                          "cat-fact-proxy"
                          #js {:method "GET"})))
        (.then (fn [r]
                 (when-let [err (.-error r)]
                   (throw err))
                 (let [data (js->clj (.-data r) :keywordize-keys true)
                       row {:id (str "live-" (.now js/Date))
                            :fact (:fact data)
                            :length (:length data)
                            :fetched_at (.toISOString (js/Date.))
                            :live? true}]
                   (swap! !state update :live (fnil conj []) row)
                   (swap! !state assoc :proxying? false))))
        (.catch (fn [e]
                  (js/console.error e)
                  (swap! !state assoc
                         :proxying? false
                         :proxy-error (.-message e)))))))

(defn- fact-row [{:keys [fact length fetched_at live?]}]
  [:li {:style {:padding "0.6rem 0.8rem"
                :border "1px solid #e5e7eb"
                :border-left (str "4px solid "
                                  (if live? "#0ea5e9" "#0f766e"))
                :border-radius 4
                :margin-bottom "0.4rem"
                :background "white"}}
   [:div {:style {:font-size "0.95rem"}} fact]
   [:div {:style {:display "flex"
                  :gap "1rem"
                  :margin-top "0.3rem"
                  :font-size "0.75rem"
                  :font-family "ui-monospace,monospace"
                  :color "#888"}}
    [:span (if live? "live (proxy)" "ingested (cron)")]
    (when length [:span (str length " chars")])
    [:span fetched_at]]])

(defn- root []
  (let [{:keys [rows loading? error live proxying? proxy-error]} @!state
        signed-in? (some? @auth/!user)
        merged (->> (concat live (or rows []))
                    (sort-by :fetched_at)
                    reverse)]
    [:div
     [:h2 "Cat Facts — Phase 4 demo"]
     [:p {:style {:color "#666" :font-size "0.9rem"}}
      "Both server-side HTTP patterns at once. Teal rows came from the "
      "5-minute pg_cron + pg_net ingester writing into "
      [:code "external_cat_facts"] ". Sky-blue rows came from clicking "
      [:em "Fetch fresh"] " below — that goes to the "
      [:code "cat-fact-proxy"] " Edge Function which fetches catfact.ninja "
      "and returns the result without persisting."]
     [:div {:style {:font-size "0.8rem"
                    :color (if signed-in? "#15803d" "#92400e")
                    :margin-bottom "0.8rem"}}
      (if signed-in?
        (str "✓ signed in (anon, uid="
             (some-> @auth/!user .-id (subs 0 8)) "…)")
        "○ not signed in — sign-in happens automatically on Fetch fresh")]
     [:div {:style {:display "flex"
                    :gap "0.5rem"
                    :margin-bottom "0.8rem"
                    :align-items "center"}}
      [:button {:on-click #(refresh-rows!)
                :style {:padding "0.3rem 0.7rem" :font-size "0.85rem"}}
       "Refresh ingested"]
      [:button {:on-click #(fetch-fresh!)
                :disabled proxying?
                :style {:padding "0.3rem 0.7rem"
                        :font-size "0.85rem"
                        :background (if proxying? "#cbd5e1" "#0ea5e9")
                        :color "white"
                        :border "none"
                        :border-radius 3
                        :cursor (if proxying? "not-allowed" "pointer")}}
       (if proxying? "Fetching…" "Fetch fresh (proxy)")]
      (when proxy-error
        [:span {:style {:color "#b00020" :font-size "0.8rem"}}
         "Proxy error: " proxy-error])]
     (cond
       loading? [:div "Loading…"]
       error    [:div {:style {:color "#b00020"}} "Error: " error]
       (empty? merged) [:div {:style {:color "#888"}}
                        "No facts yet — wait a few minutes for cron, "
                        "or click Fetch fresh."]
       :else [:ul {:style {:list-style "none" :padding 0 :margin 0}}
              (for [row merged]
                ^{:key (:id row)} [fact-row row])])]))

(defn ^:export start! []
  (auth/install-auth-listener!)
  (refresh-rows!)
  (rdom/render [root] (.getElementById js/document "app")))
