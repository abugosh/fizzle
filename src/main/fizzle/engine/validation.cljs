(ns fizzle.engine.validation
  "Pure validation functions for selection state.

   These functions interpret selection rules to determine validity.
   No re-frame dependencies — importable by both events/ and subs/."
  (:require
    [clojure.set :as set]))


(defn- card-matches-criteria?
  "Check if a card data map matches discard group criteria.
   Criteria follows the same :match/ namespace as query criteria."
  [card-data criteria]
  (let [card-types (set (or (:card/types card-data) #{}))
        card-subtypes (set (or (:card/subtypes card-data) #{}))]
    (and
      (or (empty? (get criteria :match/types #{}))
          (seq (set/intersection card-types (get criteria :match/types #{}))))
      (or (empty? (get criteria :match/subtypes #{}))
          (seq (set/intersection card-subtypes (get criteria :match/subtypes #{})))))))


(defn- satisfies-discard-groups?
  "Check if the selected cards satisfy all discard group requirements.
   Groups with :criteria must be matched by cards with those properties.
   Groups without :criteria accept any card.
   Uses greedy assignment: constrained groups first, then unconstrained."
  [selected card-map groups]
  (let [selected-ids (vec selected)
        constrained (filterv :criteria groups)
        unconstrained (filterv (complement :criteria) groups)]
    (loop [remaining-ids (set selected-ids)
           [group & more] constrained]
      (if-not group
        ;; All constrained groups satisfied — check unconstrained
        (let [unconstrained-need (reduce + 0 (map :count unconstrained))]
          (>= (count remaining-ids) unconstrained-need))
        ;; Check this constrained group
        (let [criteria (:criteria group)
              need (:count group)
              matching (filterv (fn [id]
                                  (when-let [card (get card-map id)]
                                    (card-matches-criteria? card criteria)))
                                (vec remaining-ids))]
          (if (< (count matching) need)
            false
            (recur (reduce disj remaining-ids (take need matching))
                   more)))))))


(defn validate-selection
  "Validate a selection using data-driven rules.
   Checks :selection/validation type and candidate membership.
   Returns true if valid, false otherwise.

   Validation types:
     :exact        — count(selected) == select-count
     :at-most      — count(selected) <= select-count
     :at-least-one — count(selected) >= 1
     :always       — always valid
     :exact-or-zero — 0 or exact (tutor fail-to-find)
     nil           — reject (safe default for unset builders)"
  [selection]
  (let [selected (:selection/selected selection)
        count-selected (count selected)
        select-count (:selection/select-count selection 0)
        candidates (or (:selection/candidates selection)
                       (:selection/candidate-ids selection))
        ;; Universal candidate membership check
        candidates-valid? (if candidates
                            (every? #(contains? candidates %) selected)
                            true)
        ;; Count validation based on :selection/validation
        count-valid? (case (:selection/validation selection)
                       :exact (= count-selected select-count)
                       :at-most (<= count-selected select-count)
                       :at-least-one (pos? count-selected)
                       :always true
                       :exact-or-zero (or (zero? count-selected)
                                          (= count-selected select-count))
                       ;; nil or unknown: reject (safe default)
                       false)
        ;; Discard group validation (when present)
        groups-valid? (if-let [groups (:selection/discard-groups selection)]
                        (if-let [card-map (:selection/candidate-card-map selection)]
                          (satisfies-discard-groups? selected card-map groups)
                          true)
                        true)]
    (and candidates-valid? count-valid? groups-valid?)))
