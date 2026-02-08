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


(defn rename-fork
  [db fork-id new-name]
  (if (get-fork db fork-id)
    (assoc-in db [:history/forks fork-id :fork/name] new-name)
    db))


(defn- find-descendants
  "Returns set of all fork-ids that are descendants of fork-id."
  [forks fork-id]
  (let [children (into #{}
                       (comp (filter #(= fork-id (:fork/parent (val %))))
                             (map key))
                       forks)]
    (reduce (fn [acc child-id]
              (into acc (find-descendants forks child-id)))
            children
            children)))


(defn delete-fork
  [db fork-id]
  (if (or (nil? fork-id) (nil? (get-fork db fork-id)))
    db
    (let [forks (:history/forks db)
          to-delete (conj (find-descendants forks fork-id) fork-id)
          current (:history/current-branch db)
          need-switch? (contains? to-delete current)
          db' (update db :history/forks #(apply dissoc % to-delete))]
      (if need-switch?
        (let [main (:history/main db')
              tip (dec (count main))]
          (-> db'
              (assoc :history/current-branch nil)
              (assoc :history/position (if (neg? tip) -1 tip))
              (cond-> (>= tip 0)
                (assoc :game/db (:entry/snapshot (nth main tip))))))
        db'))))


(defn list-forks
  [db]
  (vec (vals (:history/forks db))))


(defn fork-tree
  [forks]
  (let [by-parent (group-by :fork/parent (vals forks))
        build-children (fn build-children
                         [parent-id]
                         (->> (get by-parent parent-id [])
                              (sort-by :fork/name)
                              (mapv #(assoc % :children (build-children (:fork/id %))))))]
    (build-children nil)))


(defn entries-by-turn
  [entries]
  (->> entries
       (group-by :entry/turn)
       (sort-by first)
       (mapv (fn [[turn es]]
               {:turn turn :entries (vec es)}))))


(defn current-entry
  [db]
  (let [pos (:history/position db)]
    (when (>= pos 0)
      (nth (effective-entries db) pos))))
