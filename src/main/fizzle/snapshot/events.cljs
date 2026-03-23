(ns fizzle.snapshot.events
  "Re-frame events and subscriptions for snapshot sharing and restoration.

   Share flow:
     ::share-position → encode current game-db → copy URL to clipboard
     → set :snapshot/share-status :copied (clears after 2s)

   Restore flow (called from core.cljs init):
     ::restore-from-hash → decode URL hash → restore game state → merge into app-db

   Subscription:
     ::share-status → :idle | :copied | :error-too-large | :error-clipboard"
  (:require
    [clojure.string :as str]
    [fizzle.sharing.decoder :as decoder]
    [fizzle.sharing.encoder :as encoder]
    [fizzle.sharing.extractor :as extractor]
    [fizzle.sharing.restorer :as restorer]
    [re-frame.core :as rf]))


;; ---------------------------------------------------------------------------
;; Constants

(def ^:private hash-prefix "#s=")


;; ---------------------------------------------------------------------------
;; Pure helper functions (exported for tests)

(defn get-share-status
  "Return the current share status from app-db. Defaults to :idle."
  [app-db]
  (get app-db :snapshot/share-status :idle))


(defn set-share-status
  "Return updated app-db with share status set."
  [app-db status]
  (assoc app-db :snapshot/share-status status))


(defn encode-for-share
  "Encode the current game state to a shareable URL string.
   Returns nil if game/db is absent or encoding fails."
  [app-db base-url]
  (when-let [game-db (:game/db app-db)]
    (try
      (let [state   (extractor/extract game-db)
            encoded (encoder/encode-snapshot state)]
        (str base-url hash-prefix encoded))
      (catch :default _
        nil))))


(defn restore-from-hash-handler
  "Pure function: restore game state from a URL hash string.
   Returns app-db map or nil if hash is absent/invalid/malformed.

   Expected hash format: #s=<base64url-encoded-snapshot>
   Returns nil for caller to fall back to normal init."
  [hash-str]
  (when (and (string? hash-str)
             (str/starts-with? hash-str hash-prefix))
    (let [encoded (subs hash-str (count hash-prefix))
          decoded (decoder/decode-snapshot encoded)]
      (when-not (:error decoded)
        (restorer/restore-game-state decoded)))))


;; ---------------------------------------------------------------------------
;; Clipboard helper

(defn- copy-to-clipboard!
  "Copy text to clipboard. Returns a Promise.
   Uses navigator.clipboard API with textarea fallback."
  [text]
  (if (and js/navigator.clipboard
           (.-writeText js/navigator.clipboard))
    (.writeText js/navigator.clipboard text)
    ;; Fallback for older browsers
    (js/Promise.
      (fn [resolve _reject]
        (let [textarea (.createElement js/document "textarea")]
          (set! (.-value textarea) text)
          (set! (.-style.position textarea) "fixed")
          (set! (.-style.opacity textarea) "0")
          (.appendChild (.-body js/document) textarea)
          (.select textarea)
          (.execCommand js/document "copy")
          (.removeChild (.-body js/document) textarea)
          (resolve nil))))))


;; ---------------------------------------------------------------------------
;; Re-frame events

(rf/reg-event-fx
  ::share-position
  (fn [{:keys [db]} _]
    (let [base-url (str (.-origin js/location) (.-pathname js/location))
          url      (encode-for-share db base-url)]
      (if url
        {:db     (set-share-status db :copied)
         ::copy-to-clipboard url}
        ;; encode-for-share returns nil when state exceeds URL limit
        {:db (set-share-status db :error-too-large)}))))


(rf/reg-event-db
  ::set-share-status
  (fn [db [_ status]]
    (set-share-status db status)))


;; ---------------------------------------------------------------------------
;; Effect: clipboard write

(rf/reg-fx
  ::copy-to-clipboard
  (fn [url]
    (let [p (copy-to-clipboard! url)]
      ;; Success path: clear :copied → :idle after 2 seconds
      (.then p (fn [_]
                 (js/setTimeout
                   #(rf/dispatch [::set-share-status :idle])
                   2000)))
      ;; Failure path: separate .catch so error status is not auto-cleared by the .then above
      (.catch p (fn [_]
                  (rf/dispatch [::set-share-status :error-clipboard]))))))


(rf/reg-event-db
  ::restore-from-snapshot
  (fn [db [_ restored-app-db]]
    ;; Merge restored game state into app-db, preserving setup keys
    (merge db restored-app-db)))


;; ---------------------------------------------------------------------------
;; Re-frame subscriptions

(rf/reg-sub
  ::share-status
  (fn [db _]
    (get-share-status db)))
