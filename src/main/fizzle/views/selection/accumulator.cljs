(ns fizzle.views.selection.accumulator
  "Accumulator pattern inline components for stepper-based selections.
   Handles x-mana-cost and storm-split."
  (:require
    [fizzle.events.selection :as selection-events]
    [fizzle.events.selection.costs :as cost-events]
    [fizzle.events.selection.storm :as storm-events]
    [fizzle.views.keyboard :as kbd]
    [fizzle.views.selection.common :as common]
    [re-frame.core :as rf]))


;; === Storm Split ===

(defn storm-split-target-label
  "Human-readable label for a storm-split target player-id."
  [target-id]
  (case target-id
    :player-2 "Opponent"
    :player-1 "You"
    (name target-id)))


;; === Inline Components ===

(defn accumulate-stepper-view
  "Inline component for x-mana-cost and pay-x-life selections.

   Reads :selection/selected-x, :selection/max-x, :selection/domain.
   Domain determines header label:
     :x-mana-cost → 'CHOOSE X VALUE (max N)'
     :pay-x-life → 'PAY X LIFE (max N)'

   Renders: border wrapper > header > stepper row [- value +] > confirm button"
  [selection]
  (let [selected-x (or (:selection/selected-x selection) 0)
        max-x (or (:selection/max-x selection) 0)
        domain (:selection/domain selection)
        header-label (case domain
                       :x-mana-cost (str "CHOOSE X VALUE (max " max-x ")")
                       :pay-x-life (str "PAY X LIFE (max " max-x ")")
                       "ACCUMULATE")
        decrement-hint (kbd/hint-for-action :accumulate :decrement)
        increment-hint (kbd/hint-for-action :accumulate :increment)
        confirm-hint (kbd/hint-for-action :accumulate :confirm)]
    [:div {:class "mb-4 border-2 border-border-accent rounded-lg p-3"}
     [:div {:class "text-text-label mb-3 text-xs"} header-label]
     [:div {:class "flex items-center justify-center gap-5 mb-4"}
      [:button {:class (common/stepper-button-class (pos? selected-x))
                :disabled (not (pos? selected-x))
                :on-click #(rf/dispatch [::cost-events/decrement-x-value])}
       "-"
       (when decrement-hint
         [:span {:class "ml-1 text-xs text-text-muted inline"}
          "[" decrement-hint "]"])]
      [:div {:class "text-text font-bold text-xl min-w-[60px] text-center"}
       (str "X = " selected-x)]
      [:button {:class (common/stepper-button-class (< selected-x max-x))
                :disabled (not (< selected-x max-x))
                :on-click #(rf/dispatch [::cost-events/increment-x-value])}
       "+"
       (when increment-hint
         [:span {:class "ml-1 text-xs text-text-muted inline"}
          "[" increment-hint "]"])]]
     [:button {:class "w-full py-2 px-5 border-none rounded font-bold text-sm cursor-pointer bg-btn-enabled-bg text-white"
               :on-click #(rf/dispatch [::selection-events/confirm-selection])}
      "Confirm"
      (when confirm-hint
        [:span {:class "ml-2 text-xs text-text-muted inline"}
         "[" confirm-hint "]"])]]))


(defn- accumulate-inline-stepper-row
  "Stepper row for storm-split inline component.
   Renders label + [«] [-] count [+] [»] for a single target.

   When target-index is provided (storm-split mode), computes target-specific chord hints.
   When target-index is nil, falls back to non-storm-split hints."
  [target-id count copy-count total-allocated target-index]
  (let [can-decrement? (pos? count)
        can-increment? (< total-allocated copy-count)
        ;; Compute hints based on mode
        [clear-hint dec-hint inc-hint add-all-hint]
        (if target-index
          ;; Storm-split mode: use target-specific action keywords
          ;; target-index is 0-based, action keywords use 1-based numbering (N = target-index + 1)
          (let [n (inc target-index)]
            [(kbd/hint-for-action :storm-split (keyword (str "storm-clear-" n)))
             (kbd/hint-for-action :storm-split (keyword (str "storm-dec-" n)))
             (kbd/hint-for-action :storm-split (keyword (str "storm-inc-" n)))
             (kbd/hint-for-action :storm-split (keyword (str "storm-add-all-" n)))])
          ;; Non-storm-split mode: use accumulate context (backward compatibility)
          [(kbd/hint-for-action :accumulate :decrement)
           (kbd/hint-for-action :accumulate :decrement)
           (kbd/hint-for-action :accumulate :increment)
           (kbd/hint-for-action :accumulate :increment)])]
    [:div {:class "flex items-center justify-between gap-3 mb-3"}
     [:span {:class "text-text font-bold text-sm"}
      (storm-split-target-label target-id)]
     [:div {:class "flex items-center justify-center gap-2"}
      [:button {:class (common/stepper-button-class can-decrement?)
                :disabled (not can-decrement?)
                :on-click #(rf/dispatch [::storm-events/adjust-storm-split target-id (- count)])}
       "«"
       (when clear-hint
         [:span {:class "ml-1 text-xs text-text-muted inline"}
          "[" clear-hint "]"])]
      [:button {:class (common/stepper-button-class can-decrement?)
                :disabled (not can-decrement?)
                :on-click #(rf/dispatch [::storm-events/adjust-storm-split target-id -1])}
       "-"
       (when dec-hint
         [:span {:class "ml-1 text-xs text-text-muted inline"}
          "[" dec-hint "]"])]
      [:span {:class "text-text font-bold text-lg min-w-[3ch] text-center"}
       count]
      [:button {:class (common/stepper-button-class can-increment?)
                :disabled (not can-increment?)
                :on-click #(rf/dispatch [::storm-events/adjust-storm-split target-id 1])}
       "+"
       (when inc-hint
         [:span {:class "ml-1 text-xs text-text-muted inline"}
          "[" inc-hint "]"])]
      [:button {:class (common/stepper-button-class can-increment?)
                :disabled (not can-increment?)
                :on-click #(rf/dispatch [::storm-events/adjust-storm-split target-id (- copy-count total-allocated)])}
       "»"
       (when add-all-hint
         [:span {:class "ml-1 text-xs text-text-muted inline"}
          "[" add-all-hint "]"])]]]))


(defn storm-split-inline-view
  "Inline component for distributing storm copies across targets.

   Reads :selection/copy-count, :selection/valid-targets,
         :selection/allocation, :selection/source-name.

   Renders: border wrapper > header > per-target stepper rows >
            allocation counter > confirm button"
  [selection]
  (let [copy-count (:selection/copy-count selection)
        valid-targets (:selection/valid-targets selection)
        allocation (:selection/allocation selection)
        source-name (:selection/source-name selection)
        total-allocated (apply + (vals allocation))
        valid? (= total-allocated copy-count)
        confirm-hint (kbd/hint-for-action :accumulate :confirm)]
    [:div {:class "mb-4 border-2 border-border-accent rounded-lg p-3"}
     [:div {:class "text-text-label mb-3 text-xs"}
      (str "DISTRIBUTE " copy-count " COPIES"
           (when source-name (str " of " source-name)))]
     [:div {:class "mb-3"}
      (map-indexed
        (fn [idx target]
          ^{:key target}
          [accumulate-inline-stepper-row target (get allocation target 0) copy-count total-allocated idx])
        valid-targets)]
     [:p {:class (str "text-sm m-0 mb-3 "
                      (if valid? "text-health-good" "text-health-danger"))}
      (str total-allocated " / " copy-count " assigned")]
     [:button {:class (str "w-full py-2 px-5 border-none rounded font-bold text-sm "
                           (if valid?
                             "cursor-pointer bg-btn-enabled-bg text-white"
                             "cursor-not-allowed bg-surface-dim text-perm-text-tapped"))
               :disabled (not valid?)
               :on-click #(rf/dispatch [::selection-events/confirm-selection])}
      "Confirm"
      (when confirm-hint
        [:span {:class "ml-2 text-xs text-text-muted inline"}
         "[" confirm-hint "]"])]]))
