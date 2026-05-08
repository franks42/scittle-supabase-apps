(ns app.main
  (:require
   [reagent.core :as r]
   [reagent.dom :as rdom]
   [app.utils :as u]))

(defonce !state (r/atom {:clicks 0
                         :stamp (u/now-string)}))

(defn root []
  (let [{:keys [clicks stamp]} @!state]
    [:div
     [:h2 "Scittle on Supabase — Phase 2"]
     [:p (str "Loaded from ns_modules at " stamp)]
     [:ul
      [:li (str "(u/shout \"hello, world\")  → " (u/shout "hello, world"))]
      [:li (str "(u/add 2 3)               → " (u/add 2 3))]]
     [:button {:on-click #(swap! !state update :clicks inc)}
      (str "clicked " clicks " ×")]]))

(defn ^:export start! []
  (rdom/render [root] (.getElementById js/document "app")))
