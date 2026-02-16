(ns fizzle.engine.validation
  "Pure validation functions for selection state.

   These functions interpret selection rules to determine validity.
   No re-frame dependencies — importable by both events/ and subs/.")


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
                       false)]
    (and candidates-valid? count-valid?)))
