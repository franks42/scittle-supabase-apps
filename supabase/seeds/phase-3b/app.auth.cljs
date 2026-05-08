(ns app.auth
  "Tiny auth helper. Phase 3b uses anonymous sign-in to satisfy
  RLS — `auth.uid()` must be non-null for inserts into ns_modules.
  Anonymous sign-in is a per-project toggle in the Supabase dashboard;
  if it's not enabled, ensure-signed-in! rejects with a message that
  points to the toggle.

  For a real deployment, swap signInAnonymously for email magic-link or
  OAuth. The rest of the editor flow doesn't change."
  (:require
   [reagent.core :as r]
   [app.supa :as supa]))

(defn- client [] (supa/client))

(def !user
  "Observable of the current user (or nil). Reagent r/atom so any
  component dereffing it re-renders when sign-in/sign-out happens —
  set both by ensure-signed-in! and by Supabase's onAuthStateChange
  listener (install-auth-listener!)."
  (r/atom nil))

(defn current-session
  "Returns a Promise resolving to the current session, or nil if none."
  []
  (-> (.-auth (client))
      (.getSession)
      (.then (fn [r]
               (when-let [err (.-error r)]
                 (throw err))
               (.. r -data -session)))))

(defn- sign-in-anonymously []
  (-> (.-auth (client))
      (.signInAnonymously)
      (.then (fn [r]
               (when-let [err (.-error r)]
                 (throw err))
               (.. r -data -session)))))

(defn ensure-signed-in!
  "Returns a Promise resolving to a session. If a session already exists
  it is returned as-is; otherwise an anonymous sign-in is attempted.
  On failure, the rejection's .message includes guidance to enable
  anonymous sign-in in the Supabase dashboard."
  []
  (-> (current-session)
      (.then (fn [session]
               (if session
                 (do (reset! !user (.-user session)) session)
                 (-> (sign-in-anonymously)
                     (.then (fn [s]
                              (reset! !user (.-user s))
                              s))))))
      (.catch (fn [e]
                (let [msg (.-message e)]
                  (throw (js/Error.
                          (if (re-find #"(?i)anonymous" (str msg))
                            (str "Enable Anonymous sign-in in "
                                 "Dashboard → Authentication → Providers → "
                                 "Anonymous, then click Save again. "
                                 "(original: " msg ")")
                            (str "Sign-in failed: " msg)))))))))

(defn install-auth-listener!
  "Wire onAuthStateChange so !user stays in sync if the session changes."
  []
  (-> (.-auth (client))
      (.onAuthStateChange
       (fn [_event session]
         (reset! !user (when session (.-user session))))))
  nil)
