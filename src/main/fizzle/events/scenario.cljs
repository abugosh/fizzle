(ns fizzle.events.scenario
  "Re-frame event handlers for scenario CRUD.
   Scenarios are saved as a map {id → scenario} in localStorage and in
   app-db under :scenario/library."
  (:require
    [fizzle.db.storage :as storage]
    [re-frame.core :as rf]))


;; === Persistence interceptor ===

(def ^:private save-scenarios-interceptor
  (rf/after
    (fn [db]
      (storage/save-scenarios! (:scenario/library db)))))


;; === Pure handler functions (exported for direct testing) ===

(defn load-all-handler
  "Populate :scenario/library from localStorage. Idempotent — always overwrites
   from storage so that a refresh picks up any persisted data."
  [db _]
  (assoc db :scenario/library (storage/load-scenarios)))


(defn save-handler
  "Upsert scenario into :scenario/library. Uses :scenario/id as the key."
  [db [_ scenario]]
  (let [id (:scenario/id scenario)]
    (assoc-in db [:scenario/library id] scenario)))


(defn delete-handler
  "Remove scenario with given id from :scenario/library."
  [db [_ id]]
  (update db :scenario/library dissoc id))


(defn update-field-handler
  "Update a nested field on :scenario/editing.
   path is a vector of keys relative to :scenario/editing."
  [db [_ path value]]
  (assoc-in db (into [:scenario/editing] path) value))


(defn set-editing-handler
  "Set :scenario/editing to scenario (a full scenario map, or nil to clear)."
  [db [_ scenario]]
  (assoc db :scenario/editing scenario))


;; === re-frame event registrations ===

(rf/reg-event-db
  ::load-all
  load-all-handler)


(rf/reg-event-db
  ::save
  [save-scenarios-interceptor]
  save-handler)


(rf/reg-event-db
  ::delete
  [save-scenarios-interceptor]
  delete-handler)


(rf/reg-event-db
  ::update-field
  update-field-handler)


(rf/reg-event-db
  ::set-editing
  set-editing-handler)
