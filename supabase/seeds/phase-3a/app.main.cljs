(ns app.main
  "Phase 3a demo. Replaces the click-counter with a button that opens a
  CodeMirror 6 editor in a WinBox window. Demonstrates app.wm and
  app.cm6 working together end-to-end."
  (:require
   [reagent.core :as r]
   [reagent.dom :as rdom]
   [app.utils :as u]
   [app.wm :as wm]
   [app.cm6 :as cm6]))

(defonce !state
  (r/atom {:stamp (u/now-string)
           :sample-source "(ns app.example
  (:require [reagent.core :as r]))

(defonce !state (r/atom {:n 0}))

(defn root []
  [:div
   [:p (str \"n = \" (:n @!state))]
   [:button {:on-click #(swap! !state update :n inc)}
    \"increment\"]])
"}))

(defn editor-panel
  "Reagent component rendered inside a WinBox. Holds its own draft atom
  so edits survive when the window is dragged or resized."
  [initial-source]
  (let [!draft (r/atom initial-source)]
    (fn []
      [:div {:style {:height "100%"
                     :display "flex"
                     :flex-direction "column"}}
       [:div {:style {:flex "1 1 auto" :min-height 0}}
        [cm6/editor {:value @!draft
                     :on-change #(reset! !draft %)
                     :language :clojure}]]
       [:div {:style {:padding "0.5rem"
                      :border-top "1px solid #ddd"
                      :background "#f8f8f8"
                      :font-size "0.85rem"
                      :color "#555"
                      :font-family "ui-monospace,monospace"}}
        (str (count @!draft) " characters")]])))

(defn open-editor! []
  (wm/open-window! {:title "Sample editor — Phase 3a"
                    :content [editor-panel (:sample-source @!state)]
                    :width 720
                    :height 460
                    :background "#0f766e"}))

(defn root []
  [:div
   [:h2 "Scittle on Supabase — Phase 3a"]
   [:p (str "Loaded from ns_modules at " (:stamp @!state))]
   [:p
    "Foundation primitives (window manager + CodeMirror 6) wired up. "
    "Click below to open a Clojure editor in a WinBox window."]
   [:p
    [:button
     {:on-click #(open-editor!)
      :style {:background "#0f766e"
              :color "white"
              :border "none"
              :padding "0.6rem 1rem"
              :border-radius 4
              :cursor "pointer"
              :font-size "0.95rem"}}
     "Open Clojure editor"]]
   [:p {:style {:font-size "0.85rem" :color "#666"}}
    [:em
     "Dependencies: app.utils for the timestamp, app.wm for the window, "
     "app.cm6 for the editor."]]])

(defn ^:export start! []
  (rdom/render [root] (.getElementById js/document "app")))
