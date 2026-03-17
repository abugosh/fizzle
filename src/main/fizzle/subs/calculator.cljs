(ns fizzle.subs.calculator
  "Re-frame subscription chain for the live hypergeometric calculator.

   Reads library state from Datascript via game-db, combines with calculator
   query definitions from app-db, and calls probability functions to produce
   reactive results.

   All subscriptions are read-only — no Datascript writes, no dispatches."
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.probability :as probability]
    [fizzle.subs.game :as game-subs]
    [re-frame.core :as rf]))


;; === Layer 2: simple app-db extractions ===

(rf/reg-sub
  ::calculator-visible?
  (fn [db _]
    (get db :calculator/visible? false)))


(rf/reg-sub
  ::calculator-queries
  (fn [db _]
    (get db :calculator/queries [])))


;; === Layer 3: derived subscriptions ===

(rf/reg-sub
  ::library-card-counts
  :<- [::game-subs/game-db]
  (fn [game-db _]
    (if (nil? game-db)
      {}
      (let [player-id (queries/get-human-player-id game-db)
            objects   (queries/get-objects-in-zone game-db player-id :library)]
        (if (nil? objects)
          {}
          (->> objects
               (group-by #(get-in % [:object/card :card/id]))
               (reduce-kv (fn [m k v] (assoc m k (count v))) {})))))))


;; === Pure helper functions ===

(defn compute-target-count
  "Sum card-counts for each card-id in :target/cards.
   Cards absent from card-counts contribute 0."
  [card-counts target]
  (reduce + 0 (map #(get card-counts % 0) (:target/cards target))))


(defn compute-step-probability
  "Compute the probability for a single step in isolation.
   N: total library size
   card-counts: {card-id -> count}
   step: {:step/draw-count n :step/targets [...]}

   - No targets → 1.0 (vacuously true)
   - 1 target → at-least-probability
   - 2+ targets → joint-probability
   - draw-count clamped to min(draw-count, N)"
  [N card-counts step]
  (let [targets    (:step/targets step)
        draw-count (min (:step/draw-count step) N)]
    (cond
      (empty? targets) 1.0
      (= 1 (count targets))
      (let [target    (first targets)
            K         (compute-target-count card-counts target)
            min-count (:target/min-count target)]
        (probability/at-least-probability N K draw-count min-count))
      :else
      (let [groups (mapv (fn [target]
                           {:count (compute-target-count card-counts target)
                            :min   (:target/min-count target)})
                         targets)]
        (probability/joint-probability N groups draw-count)))))


(defn compute-query-results
  "Compute the result map for a single query.
   card-counts: {card-id -> count} from current library
   query: {:query/id ... :query/steps [...] ...}

   Returns the query map enriched with:
   - :query/probability — overall probability for the full query
   - :query/steps — steps with :step/probability and :step/targets enriched with counts/probs"
  [card-counts query]
  (let [N       (reduce + 0 (vals card-counts))
        steps   (:query/steps query)
        enrich-step (fn [step]
                      (let [draw-count (min (:step/draw-count step) N)
                            enriched-targets (mapv (fn [target]
                                                     (let [K (compute-target-count card-counts target)
                                                           min-count (:target/min-count target)
                                                           target-p (probability/at-least-probability
                                                                      N K draw-count min-count)]
                                                       (assoc target
                                                              :target/count K
                                                              :target/probability target-p)))
                                                   (:step/targets step))
                            step-p (compute-step-probability N card-counts step)]
                        (assoc step
                               :step/probability step-p
                               :step/targets enriched-targets)))
        enriched-steps (mapv enrich-step steps)]
    ;; Compute overall query probability
    (let [query-p
          (cond
            (empty? steps) 1.0
            (= 1 (count steps))
            (:step/probability (first enriched-steps))
            :else
            ;; Sequential probability: collect all unique target groups across steps
            ;; Build all-groups as union of distinct target specs
            (let [all-target-groups
                  (vec (distinct
                         (mapcat (fn [step]
                                   (mapv (fn [target]
                                           {:count (compute-target-count card-counts target)})
                                         (:step/targets step)))
                                 steps)))
                  ;; For each step, build :targets as [{:group-index i :min m} ...]
                  seq-steps
                  (mapv (fn [step]
                          {:draw-count (min (:step/draw-count step) N)
                           :targets
                           (mapv (fn [target]
                                   ;; Find the index of this target's group spec in all-target-groups
                                   (let [count-k (compute-target-count card-counts target)
                                         idx     (first (keep-indexed
                                                          (fn [i g]
                                                            (when (= (:count g) count-k) i))
                                                          all-target-groups))]
                                     {:group-index idx
                                      :min         (:target/min-count target)}))
                                 (:step/targets step))})
                        steps)]
              (probability/sequential-probability N all-target-groups seq-steps)))]
      (assoc query
             :query/probability query-p
             :query/steps enriched-steps))))


(rf/reg-sub
  ::calculator-results
  :<- [::library-card-counts]
  :<- [::calculator-queries]
  (fn [[card-counts queries] _]
    (mapv #(compute-query-results card-counts %) queries)))
