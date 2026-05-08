(ns app.wm
  "Window manager built on WinBox.

  Plain atom-based registry — no state machine yet. When the registry
  grows guards or lifecycle states beyond `open`/`closed`, swap in
  clj-statecharts.

  Usage:
    (app.wm/open-window! {:title \"Editor\"
                          :content [my-reagent-component {...}]
                          :width 720 :height 480})
    => returns a window-id

    (app.wm/close-window! window-id)
    (app.wm/list-windows)"
  (:require
   [reagent.dom :as rdom]))

(defonce !windows (atom {}))

(defn list-windows
  "Snapshot of currently open windows: vector of {:id :title}."
  []
  (mapv (fn [[id w]] {:id id :title (:title w)}) @!windows))

(defn close-window!
  "Close the window with this id. Idempotent. Unmounts the Reagent tree
  and closes the WinBox instance."
  [id]
  (when-let [w (get @!windows id)]
    (try
      (rdom/unmount-component-at-node (.-body (:winbox w)))
      (catch :default _))
    (try
      (.close (:winbox w))
      (catch :default _))
    (swap! !windows dissoc id)
    nil))

(defn close-all!
  "Close every open window. Useful when the launcher tears down before
  switching apps."
  []
  (doseq [id (keys @!windows)]
    (close-window! id))
  nil)

(defn open-window!
  "Open a new floating window. Options:
   - :title       header text (default \"Window\")
   - :content     Reagent component vector to render into the body
                  (e.g., [some-ns/some-component {...}])
   - :width       integer pixels (default 640)
   - :height      integer pixels (default 480)
   - :x           number, percent string, or \"center\" (default \"center\")
   - :y           same shape as :x (default \"center\")
   - :background  CSS color for the title bar (default \"#1e88e5\")
   - :on-close    optional fn called after the window closes

  Returns the window id."
  [{:keys [title content width height x y background on-close]}]
  (let [id (str "wm-" (random-uuid))
        wb-opts {:title (or title "Window")
                 :width (or width 640)
                 :height (or height 480)
                 :x (or x "center")
                 :y (or y "center")
                 :background (or background "#1e88e5")
                 :class ["no-full"]
                 :onclose (fn []
                            (swap! !windows dissoc id)
                            (when on-close (on-close))
                            js/undefined)}
        wb (js/WinBox. (clj->js wb-opts))]
    (when content
      (rdom/render content (.-body wb)))
    (swap! !windows assoc id {:id id :title (or title "Window") :winbox wb})
    id))
