(ns app.cm6
  "Reagent wrapper around CodeMirror 6.

  CM6 modules are loaded as ES modules in shell.html and exposed both
  as a globalThis.CM object and as a window.CM6 Promise. The promise
  is what callers await to handle the case where the editor is mounted
  before the modules finish loading.

  Usage:
    [app.cm6/editor {:value @!source
                     :on-change #(reset! !source %)
                     :language :clojure}]"
  (:require
   [reagent.core :as r]))

;; Mutable registry of EditorView instances by editor-id.
(defonce !editors (atom {}))

(defn ready?
  "True if CM6 modules are already on globalThis.CM. The window.CM6
  Promise is the more robust check; this is just for fast-path mounts."
  []
  (some? (.-CM js/globalThis)))

(defn cm []
  (.-CM js/globalThis))

(defn- build-extensions
  "Build the CM6 extension array based on options."
  [{:keys [language read-only on-change]}]
  (let [c (cm)
        exts #js [(.-basicSetup c)
                  (.of (.-keymap c) (.-defaultKeymap c))
                  (.history c)
                  (.of (.-keymap c) (.-historyKeymap c))]]
    (when (= language :clojure)
      (.push exts ((.-clojure c))))
    (when read-only
      (.push exts (.of (.. c -EditorState -readOnly) true)))
    (when on-change
      (.push exts
             (.of (.. c -EditorView -updateListener)
                  (fn [update]
                    (when (.-docChanged update)
                      (on-change (.toString (.-doc (.-state update)))))))))
    exts))

(defn- create-view!
  "Create a CM6 EditorView mounted into container with the given options."
  [container opts]
  (let [c (cm)
        state (.create (.-EditorState c)
                       #js {:doc (or (:value opts) "")
                            :extensions (build-extensions opts)})]
    (new (.-EditorView c)
         #js {:state state
              :parent container})))

(defn- update-value!
  "Replace the editor's content if it differs from value."
  [view value]
  (when (and view value)
    (let [current (.toString (.-doc (.-state view)))]
      (when (not= current value)
        (.dispatch view
                   #js {:changes #js {:from 0
                                      :to (count current)
                                      :insert value}})))))

(defn editor
  "Reagent component for a CodeMirror 6 editor.

  Props:
   - :value      string content
   - :on-change  fn called with new value on each user-initiated change
   - :read-only  boolean
   - :language   :clojure or nil
   - :id         optional explicit editor id; otherwise random-uuid
   - :class      additional CSS class for the container
   - :style      inline style map for the container

  The component awaits window.CM6 if CM6 is not yet loaded when the
  component mounts, then creates the EditorView."
  [_initial-props]
  (let [editor-id (str (random-uuid))
        !view (atom nil)
        !container (atom nil)
        !last-value (atom nil)
        mount! (fn [opts]
                 (when-let [container @!container]
                   (let [v (create-view! container opts)]
                     (reset! !view v)
                     (reset! !last-value (:value opts))
                     (swap! !editors assoc editor-id v))))]
    (r/create-class
     {:display-name "app.cm6/editor"

      :component-did-mount
      (fn [this]
        (let [{:keys [value language read-only on-change]} (r/props this)
              opts {:value value
                    :language language
                    :read-only read-only
                    :on-change on-change}]
          (if (ready?)
            (mount! opts)
            (-> js/window.CM6
                (.then (fn [_] (mount! opts)))
                (.catch (fn [e]
                          (js/console.error "[app.cm6] CM6 load failed:" e)))))))

      :component-will-unmount
      (fn [_this]
        (when-let [v @!view]
          (.destroy v)
          (swap! !editors dissoc editor-id)))

      :reagent-render
      (fn [{:keys [value class style]}]
        (when (and @!view (not= @!last-value value))
          (reset! !last-value value)
          (update-value! @!view value))
        [:div.cm-container
         {:ref #(reset! !container %)
          :class class
          :style (merge {:height "100%" :overflow "auto"} style)}])})))

(defn get-value
  "Current value of the editor with this id, or nil if unmounted."
  [editor-id]
  (when-let [v (get @!editors editor-id)]
    (.toString (.-doc (.-state v)))))

(defn focus!
  "Focus the editor with this id."
  [editor-id]
  (when-let [v (get @!editors editor-id)]
    (.focus v)))
