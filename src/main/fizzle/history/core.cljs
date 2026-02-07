(ns fizzle.history.core)


(defn init-history
  []
  {:history/main []
   :history/forks {}
   :history/current-branch nil
   :history/position -1})


(defn make-entry
  [game-db event-type description turn]
  {:entry/snapshot game-db
   :entry/event-type event-type
   :entry/description description
   :entry/turn turn})


(defn get-fork
  [db fork-id]
  (get-in db [:history/forks fork-id]))


(defn- effective-entries-for-branch
  [db fork-id]
  (if (nil? fork-id)
    (:history/main db)
    (let [fork (get-fork db fork-id)
          parent-entries (effective-entries-for-branch db (:fork/parent fork))]
      (into (vec (take (inc (:fork/branch-point fork)) parent-entries))
            (:fork/entries fork)))))


(defn effective-entries
  [db]
  (effective-entries-for-branch db (:history/current-branch db)))


(defn entry-count
  [db]
  (count (effective-entries db)))


(defn at-tip?
  [db]
  (let [pos (:history/position db)
        cnt (entry-count db)]
    (or (and (= pos -1) (zero? cnt))
        (= pos (dec cnt)))))


(defn append-entry
  [db entry]
  (if (or (= -1 (:history/position db)) (at-tip? db))
    (let [branch (:history/current-branch db)]
      (if (nil? branch)
        (-> db
            (update :history/main conj entry)
            (update :history/position inc))
        (-> db
            (update-in [:history/forks branch :fork/entries] conj entry)
            (update :history/position inc))))
    db))


(defn step-to
  [db position]
  (let [entries (effective-entries db)
        cnt (count entries)]
    (if (and (>= position 0) (< position cnt))
      (-> db
          (assoc :history/position position)
          (assoc :game/db (:entry/snapshot (nth entries position))))
      db)))


(defn can-step-back?
  [db]
  (> (:history/position db) 0))


(defn can-step-forward?
  [db]
  (< (:history/position db) (dec (entry-count db))))


(defn auto-fork
  [db entry]
  (let [fork-id (random-uuid)
        fork-name (str "Fork " (inc (count (:history/forks db))))
        position (:history/position db)
        parent (:history/current-branch db)
        fork {:fork/id fork-id
              :fork/name fork-name
              :fork/branch-point position
              :fork/parent parent
              :fork/entries [entry]}]
    (-> db
        (assoc-in [:history/forks fork-id] fork)
        (assoc :history/current-branch fork-id)
        (assoc :history/position (inc position)))))


(defn create-named-fork
  [db name]
  (let [fork-id (random-uuid)
        position (:history/position db)
        parent (:history/current-branch db)
        fork {:fork/id fork-id
              :fork/name name
              :fork/branch-point position
              :fork/parent parent
              :fork/entries []}]
    (assoc-in db [:history/forks fork-id] fork)))


(defn switch-branch
  [db fork-id]
  (if (or (nil? fork-id) (get-fork db fork-id))
    (let [db' (assoc db :history/current-branch fork-id)
          entries (effective-entries db')
          tip (dec (count entries))]
      (-> db'
          (assoc :history/position tip)
          (assoc :game/db (:entry/snapshot (nth entries tip)))))
    db))


(defn list-forks
  [db]
  (vec (vals (:history/forks db))))


(defn current-entry
  [db]
  (let [pos (:history/position db)]
    (when (>= pos 0)
      (nth (effective-entries db) pos))))
