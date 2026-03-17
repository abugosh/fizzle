(ns fizzle.events.calculator
  "Re-frame event handlers for calculator query state management.
   Operates exclusively on app-db — no Datascript access, no probability computation.
   All mutation operations are no-ops when referenced IDs are not found."
  (:require
    [fizzle.db.storage :as storage]
    [re-frame.core :as rf]))


;; === Default state ===

(def ^:private default-state
  {:calculator/visible? false
   :calculator/next-id  1
   :calculator/queries  []})


;; === Persistence interceptor ===

(def ^:private save-queries-interceptor
  (rf/after
    (fn [db]
      (storage/save-calculator-queries! (:calculator/queries db)))))


;; === Pure handler functions (exported for direct testing) ===

(defn max-id-in-queries
  "Find the maximum ID used across all queries, steps, and target groups.
   Returns 0 if no IDs found."
  [queries]
  (reduce max 0
          (concat
            (map :query/id queries)
            (mapcat (fn [q] (map :step/id (:query/steps q))) queries)
            (mapcat (fn [q]
                      (mapcat (fn [s] (map :target/id (:step/targets s)))
                              (:query/steps q)))
                    queries))))


(defn init-calculator-handler
  "Idempotent: only initializes when :calculator/queries is absent.
   Attempts to restore queries from localStorage; falls back to empty default state."
  [db _]
  (if (contains? db :calculator/queries)
    db
    (let [loaded (storage/load-calculator-queries)]
      (if (seq loaded)
        (merge db {:calculator/visible? false
                   :calculator/next-id  (inc (max-id-in-queries loaded))
                   :calculator/queries  loaded})
        (merge db default-state)))))


(defn toggle-calculator-handler
  "Toggle :calculator/visible? boolean."
  [db _]
  (update db :calculator/visible? not))


(defn add-query-handler
  "Append a new query using next-id, label 'Query N', collapsed?=false, empty steps.
   N is the 1-based index of the new query (count before adding + 1).
   Increments :calculator/next-id."
  [db _]
  (let [id      (:calculator/next-id db)
        n       (inc (count (:calculator/queries db)))
        query   {:query/id        id
                 :query/label     (str "Query " n)
                 :query/collapsed? false
                 :query/steps     []}]
    (-> db
        (update :calculator/queries conj query)
        (update :calculator/next-id inc))))


(defn remove-query-handler
  "Remove query with matching id. No-op if id not found."
  [db [_ query-id]]
  (update db :calculator/queries
          (fn [queries]
            (into [] (remove #(= (:query/id %) query-id) queries)))))


(defn set-query-label-handler
  "Update label for query with matching id. No-op if id not found."
  [db [_ query-id label]]
  (update db :calculator/queries
          (fn [queries]
            (mapv (fn [q]
                    (if (= (:query/id q) query-id)
                      (assoc q :query/label label)
                      q))
                  queries))))


(defn toggle-query-collapsed-handler
  "Toggle :query/collapsed? for query with matching id. No-op if id not found."
  [db [_ query-id]]
  (update db :calculator/queries
          (fn [queries]
            (mapv (fn [q]
                    (if (= (:query/id q) query-id)
                      (update q :query/collapsed? not)
                      q))
                  queries))))


(defn add-step-handler
  "Append a new step to query with matching id.
   Step has next-id, draw-count=7, empty targets.
   No-op if query id not found."
  [db [_ query-id]]
  (let [query-exists? (some #(= (:query/id %) query-id) (:calculator/queries db))]
    (if-not query-exists?
      db
      (let [id   (:calculator/next-id db)
            step {:step/id         id
                  :step/draw-count 7
                  :step/targets    []}]
        (-> db
            (update :calculator/queries
                    (fn [queries]
                      (mapv (fn [q]
                              (if (= (:query/id q) query-id)
                                (update q :query/steps conj step)
                                q))
                            queries)))
            (update :calculator/next-id inc))))))


(defn remove-step-handler
  "Remove step with matching id from query with matching id. No-op if ids not found."
  [db [_ query-id step-id]]
  (update db :calculator/queries
          (fn [queries]
            (mapv (fn [q]
                    (if (= (:query/id q) query-id)
                      (update q :query/steps
                              (fn [steps]
                                (into [] (remove #(= (:step/id %) step-id) steps))))
                      q))
                  queries))))


(defn set-step-draw-count-handler
  "Set draw-count for step with matching id in query with matching id.
   Clamps to minimum 0. No-op if ids not found."
  [db [_ query-id step-id count]]
  (let [clamped (max 0 count)]
    (update db :calculator/queries
            (fn [queries]
              (mapv (fn [q]
                      (if (= (:query/id q) query-id)
                        (update q :query/steps
                                (fn [steps]
                                  (mapv (fn [s]
                                          (if (= (:step/id s) step-id)
                                            (assoc s :step/draw-count clamped)
                                            s))
                                        steps)))
                        q))
                    queries)))))


(defn add-target-group-handler
  "Append a new target group to step with matching id in query with matching id.
   Target has next-id, empty cards #{}, min-count=1.
   No-op if ids not found."
  [db [_ query-id step-id]]
  (let [step-exists? (some (fn [q]
                             (and (= (:query/id q) query-id)
                                  (some #(= (:step/id %) step-id) (:query/steps q))))
                           (:calculator/queries db))]
    (if-not step-exists?
      db
      (let [id     (:calculator/next-id db)
            target {:target/id        id
                    :target/cards     #{}
                    :target/min-count 1}]
        (-> db
            (update :calculator/queries
                    (fn [queries]
                      (mapv (fn [q]
                              (if (= (:query/id q) query-id)
                                (update q :query/steps
                                        (fn [steps]
                                          (mapv (fn [s]
                                                  (if (= (:step/id s) step-id)
                                                    (update s :step/targets conj target)
                                                    s))
                                                steps)))
                                q))
                            queries)))
            (update :calculator/next-id inc))))))


(defn remove-target-group-handler
  "Remove target group with matching id from step/query path. No-op if ids not found."
  [db [_ query-id step-id target-id]]
  (update db :calculator/queries
          (fn [queries]
            (mapv (fn [q]
                    (if (= (:query/id q) query-id)
                      (update q :query/steps
                              (fn [steps]
                                (mapv (fn [s]
                                        (if (= (:step/id s) step-id)
                                          (update s :step/targets
                                                  (fn [targets]
                                                    (into [] (remove #(= (:target/id %) target-id) targets))))
                                          s))
                                      steps)))
                      q))
                  queries))))


(defn toggle-target-card-handler
  "Add card-id to :target/cards if absent; remove if present.
   No-op if query-id, step-id, or target-id not found."
  [db [_ query-id step-id target-id card-id]]
  (update db :calculator/queries
          (fn [queries]
            (mapv (fn [q]
                    (if (= (:query/id q) query-id)
                      (update q :query/steps
                              (fn [steps]
                                (mapv (fn [s]
                                        (if (= (:step/id s) step-id)
                                          (update s :step/targets
                                                  (fn [targets]
                                                    (mapv (fn [t]
                                                            (if (= (:target/id t) target-id)
                                                              (update t :target/cards
                                                                      (fn [cards]
                                                                        (if (contains? cards card-id)
                                                                          (disj cards card-id)
                                                                          (conj cards card-id))))
                                                              t))
                                                          targets)))
                                          s))
                                      steps)))
                      q))
                  queries))))


(defn set-target-min-count-handler
  "Set min-count for target group. Clamps to minimum 0. No-op if ids not found."
  [db [_ query-id step-id target-id min-count]]
  (let [clamped (max 0 min-count)]
    (update db :calculator/queries
            (fn [queries]
              (mapv (fn [q]
                      (if (= (:query/id q) query-id)
                        (update q :query/steps
                                (fn [steps]
                                  (mapv (fn [s]
                                          (if (= (:step/id s) step-id)
                                            (update s :step/targets
                                                    (fn [targets]
                                                      (mapv (fn [t]
                                                              (if (= (:target/id t) target-id)
                                                                (assoc t :target/min-count clamped)
                                                                t))
                                                            targets)))
                                            s))
                                        steps)))
                        q))
                    queries)))))


;; === re-frame event registrations ===

(rf/reg-event-db
  ::init-calculator
  init-calculator-handler)


(rf/reg-event-db
  ::toggle-calculator
  toggle-calculator-handler)


(rf/reg-event-db
  ::add-query
  [save-queries-interceptor]
  add-query-handler)


(rf/reg-event-db
  ::remove-query
  [save-queries-interceptor]
  remove-query-handler)


(rf/reg-event-db
  ::set-query-label
  [save-queries-interceptor]
  set-query-label-handler)


(rf/reg-event-db
  ::toggle-query-collapsed
  [save-queries-interceptor]
  toggle-query-collapsed-handler)


(rf/reg-event-db
  ::add-step
  [save-queries-interceptor]
  add-step-handler)


(rf/reg-event-db
  ::remove-step
  [save-queries-interceptor]
  remove-step-handler)


(rf/reg-event-db
  ::set-step-draw-count
  [save-queries-interceptor]
  set-step-draw-count-handler)


(rf/reg-event-db
  ::add-target-group
  [save-queries-interceptor]
  add-target-group-handler)


(rf/reg-event-db
  ::remove-target-group
  [save-queries-interceptor]
  remove-target-group-handler)


(rf/reg-event-db
  ::toggle-target-card
  [save-queries-interceptor]
  toggle-target-card-handler)


(rf/reg-event-db
  ::set-target-min-count
  [save-queries-interceptor]
  set-target-min-count-handler)
