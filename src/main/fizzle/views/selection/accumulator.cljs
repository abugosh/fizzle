(ns fizzle.views.selection.accumulator
  "Accumulator pattern inline components for stepper-based selections.
   Handles x-mana-cost and storm-split."
  (:require
    [fizzle.events.selection :as selection-events]
    [fizzle.events.selection.costs :as cost-events]
    [fizzle.events.selection.storm :as storm-events]
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
                       "ACCUMULATE")]
    [:div {:class "mb-4 border-2 border-border-accent rounded-lg p-3"}
     [:div {:class "text-text-label mb-3 text-xs"} header-label]
     [:div {:class "flex items-center justify-center gap-5 mb-4"}
      [:button {:class (common/stepper-button-class (pos? selected-x))
                :disabled (not (pos? selected-x))
                :on-click #(rf/dispatch [::cost-events/decrement-x-value])}
       "-"]
      [:div {:class "text-text font-bold text-xl min-w-[60px] text-center"}
       (str "X = " selected-x)]
      [:button {:class (common/stepper-button-class (< selected-x max-x))
                :disabled (not (< selected-x max-x))
                :on-click #(rf/dispatch [::cost-events/increment-x-value])}
       "+"]]
     [:button {:class "w-full py-2 px-5 border-none rounded font-bold text-sm cursor-pointer bg-btn-enabled-bg text-white"
               :on-click #(rf/dispatch [::selection-events/confirm-selection])}
      "Confirm"]]))


(defn- accumulate-inline-stepper-row
  "Stepper row for storm-split inline component.
   Renders label + [«] [-] count [+] [»] for a single target."
  [target-id count copy-count total-allocated]
  (let [can-decrement? (pos? count)
        can-increment? (< total-allocated copy-count)]
    [:div {:class "flex items-center justify-between gap-3 mb-3"}
     [:span {:class "text-text font-bold text-sm"}
      (storm-split-target-label target-id)]
     [:div {:class "flex items-center justify-center gap-2"}
      [:button {:class (common/stepper-button-class can-decrement?)
                :disabled (not can-decrement?)
                :on-click #(rf/dispatch [::storm-events/adjust-storm-split target-id (- count)])}
       "«"]
      [:button {:class (common/stepper-button-class can-decrement?)
                :disabled (not can-decrement?)
                :on-click #(rf/dispatch [::storm-events/adjust-storm-split target-id -1])}
       "-"]
      [:span {:class "text-text font-bold text-lg min-w-[3ch] text-center"}
       count]
      [:button {:class (common/stepper-button-class can-increment?)
                :disabled (not can-increment?)
                :on-click #(rf/dispatch [::storm-events/adjust-storm-split target-id 1])}
       "+"]
      [:button {:class (common/stepper-button-class can-increment?)
                :disabled (not can-increment?)
                :on-click #(rf/dispatch [::storm-events/adjust-storm-split target-id (- copy-count total-allocated)])}
       "»"]]]))


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
        valid? (= total-allocated copy-count)]
    [:div {:class "mb-4 border-2 border-border-accent rounded-lg p-3"}
     [:div {:class "text-text-label mb-3 text-xs"}
      (str "DISTRIBUTE " copy-count " COPIES"
           (when source-name (str " of " source-name)))]
     [:div {:class "mb-3"}
      (for [target valid-targets]
        ^{:key target}
        [accumulate-inline-stepper-row target (get allocation target 0) copy-count total-allocated])]
     [:p {:class (str "text-sm m-0 mb-3 "
                      (if valid? "text-health-good" "text-health-danger"))}
      (str total-allocated " / " copy-count " assigned")]
     [:button {:class (str "w-full py-2 px-5 border-none rounded font-bold text-sm "
                           (if valid?
                             "cursor-pointer bg-btn-enabled-bg text-white"
                             "cursor-not-allowed bg-surface-dim text-perm-text-tapped"))
               :disabled (not valid?)
               :on-click #(rf/dispatch [::selection-events/confirm-selection])}
      "Confirm"]]))
