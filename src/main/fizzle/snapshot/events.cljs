(ns fizzle.snapshot.events
  "Re-frame events and subscriptions for snapshot sharing and restoration.

   Share flow:
     ::share-position → encode current game-db → copy URL to clipboard
     → set :snapshot/share-status :copied (clears after 2s)

   Restore flow (called from core.cljs init):
     ::restore-from-hash → decode URL hash → restore game state → merge into app-db

   Scenario URL format: #sc=<base64url-edn-config>&t=<percent-encoded-title>
     - #sc= prefix encodes EDN scenario config directly (fresh random draw on restore)
     - #s= prefix encodes a full game-state snapshot (position share, exact state)
     - &t= is optional; absent when no title
     - title is encoded via js/encodeURIComponent for safe embedding

   Subscription:
     ::share-status → :idle | :copied | :error-too-large | :error-clipboard"
  (:require
    [cljs.reader :as reader]
    [clojure.string :as str]
    [fizzle.events.scenario :as scenario-events]
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


(defn scenario->url
  "Generate a shareable URL for a scenario config.
   Encodes the scenario config as EDN (not a resolved game state) so that
   restoring produces a fresh game with new random draws.

   Returns a URL string of the form:
     <base-url>#sc=<base64-edn-config>[&t=<percent-encoded-title>]

   Returns nil on encoding failure.

   The title param is included only when :scenario/title is a non-blank string.
   Title is encoded via js/encodeURIComponent so all special chars (&, #, etc.)
   are safe in the URL hash."
  [scenario base-url]
  (try
    (let [config  (dissoc scenario :scenario/id)
          encoded (-> (pr-str config)
                      js/encodeURIComponent
                      js/unescape
                      js/btoa)
          url     (str base-url "#sc=" encoded)
          title   (:scenario/title scenario)]
      (if (and (string? title) (not (str/blank? title)))
        (str url "&t=" (js/encodeURIComponent title))
        url))
    (catch :default _ nil)))


(defn decode-scenario-config
  "Decode a base64url-encoded scenario config string back to a Clojure map.
   Returns nil on any decode/parse failure."
  [encoded]
  (try
    (let [edn-str (-> encoded
                      js/atob
                      js/escape
                      js/decodeURIComponent)]
      (reader/read-string edn-str))
    (catch :default _ nil)))


(defn- parse-title-param
  "Extract decoded title from param string like 't=My+Title', or nil."
  [param]
  (when (str/starts-with? param "t=")
    (try
      (js/decodeURIComponent (subs param 2))
      (catch :default _ nil))))


(defn parse-scenario-url
  "Pure function: parse a URL hash string into its component parts.

   Handles two formats:
     #sc=<encoded>              → {:type :scenario-config :encoded <encoded> :title nil}
     #sc=<encoded>&t=<title>    → {:type :scenario-config :encoded <encoded> :title <decoded-title>}
     #s=<encoded>               → {:type :snapshot :snapshot <encoded> :title nil}
     #s=<encoded>&t=<title>     → {:type :snapshot :snapshot <encoded> :title <decoded-title>}

   Returns nil if hash does not start with a recognized prefix."
  [hash-str]
  (when (string? hash-str)
    (cond
      (str/starts-with? hash-str "#sc=")
      (let [after-prefix (subs hash-str 4)
            parts        (str/split after-prefix #"&" 2)
            encoded      (first parts)
            title        (when (= 2 (count parts))
                           (parse-title-param (second parts)))]
        {:type    :scenario-config
         :encoded encoded
         :title   title})

      (str/starts-with? hash-str hash-prefix)
      (let [after-prefix (subs hash-str (count hash-prefix))
            parts        (str/split after-prefix #"&" 2)
            snapshot     (first parts)
            title        (when (= 2 (count parts))
                           (parse-title-param (second parts)))]
        {:type     :snapshot
         :snapshot snapshot
         :title    title})

      :else nil)))


(defn restore-from-hash-handler
  "Pure function: restore game state from a URL hash string.
   Returns app-db map or nil if hash is absent/invalid/malformed.

   Handles two URL formats:
     #sc=<base64-edn-config>[&t=<title>]   — scenario config (fresh random draw)
     #s=<base64url-encoded-snapshot>[&t=<title>]  — position share (exact state)

   When &t= param is present, the restored app-db includes
   :snapshot/restored-title with the decoded title string.

   Returns nil for caller to fall back to normal init."
  [hash-str]
  (when-let [parsed (parse-scenario-url hash-str)]
    (case (:type parsed)
      :scenario-config
      (when-let [config (decode-scenario-config (:encoded parsed))]
        (let [restored (scenario-events/init-from-scenario config)]
          (cond-> restored
            (:title parsed) (assoc :snapshot/restored-title (:title parsed)))))

      :snapshot
      (let [decoded (decoder/decode-snapshot (:snapshot parsed))]
        (when-not (:error decoded)
          (let [restored (restorer/restore-game-state decoded)]
            (cond-> restored
              (:title parsed) (assoc :snapshot/restored-title (:title parsed))))))

      nil)))


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


(defn restore-from-snapshot-handler
  "Pure function: merge restored-app-db into db, auto-saving to scenario library
   when :snapshot/restored-title is present (i.e. URL included a &t= param)."
  [db [_ restored-app-db]]
  (let [merged (merge db restored-app-db)
        title  (:snapshot/restored-title merged)]
    (if (and (string? title) (not (str/blank? title)))
      ;; Auto-save to scenario library when URL contained &t= title param
      (let [game-db (:game/db merged)
            scenario (scenario-events/extract-scenario-from-game game-db title)
            scenario-id (:scenario/id scenario)]
        (assoc-in merged [:scenario/library scenario-id] scenario))
      merged)))


(rf/reg-event-db
  ::restore-from-snapshot
  restore-from-snapshot-handler)


;; ---------------------------------------------------------------------------
;; Scenario share event

(rf/reg-event-fx
  ::copy-scenario-share-link
  (fn [{:keys [db]} [_ scenario]]
    (let [base-url (str (.-origin js/location) (.-pathname js/location))
          url      (scenario->url scenario base-url)]
      (if url
        {:db     (set-share-status db :copied)
         ::copy-to-clipboard url}
        {:db (set-share-status db :error-too-large)}))))


;; ---------------------------------------------------------------------------
;; Re-frame subscriptions

(rf/reg-sub
  ::share-status
  (fn [db _]
    (get-share-status db)))
