(ns fizzle.views.selection.accumulator
  "Accumulator pattern modal components for stepper-based selections.
   Handles x-mana-cost and storm-split."
  (:require
    [fizzle.events.selection :as selection-events]
    [fizzle.events.selection.costs :as cost-events]
    [fizzle.events.selection.storm :as storm-events]
    [fizzle.subs.game :as subs]
    [fizzle.views.selection.common :as common]
    [re-frame.core :as rf]))


;; === X Mana Selection ===

(defn x-mana-selection-modal
  "Modal for selecting X value for a spell with X in its mana cost."
  [selection]
  (let [selected-x (or (:selection/selected-x selection) 0)
        max-x (or (:selection/max-x selection) 0)
        mode (:selection/mode selection)
        mana-cost (:mode/mana-cost mode)
        fixed-colorless (get mana-cost :colorless 0)
        total-colorless (+ fixed-colorless selected-x)]
    [:div {:class common/overlay-class}
     [:div {:class (common/container-class {:max-width "400px" :border-color "gy-flashback-border"})}
      [:h2 {:class "text-text m-0 mb-2 text-lg"} "Choose X value"]
      [:p {:class "text-health-good m-0 mb-4 text-sm"}
       (str "Total cost: {" total-colorless "}{U} (X = " selected-x ", max " max-x ")")]
      [:div {:class "flex items-center justify-center gap-5 mb-5"}
       [:button {:class (common/stepper-button-class (pos? selected-x))
                 :disabled (not (pos? selected-x))
                 :on-click #(rf/dispatch [::cost-events/decrement-x-value])}
        "-"]
       [:div {:class "text-[32px] text-white font-bold min-w-[60px] text-center"}
        (str "X = " selected-x)]
       [:button {:class (common/stepper-button-class (< selected-x max-x))
                 :disabled (not (< selected-x max-x))
                 :on-click #(rf/dispatch [::cost-events/increment-x-value])}
        "+"]]
      [:div {:class "flex justify-end gap-3"}
       [common/cancel-button {:label "Cancel"
                              :on-cancel #(rf/dispatch [::cost-events/cancel-x-mana-selection])}]
       [common/confirm-button {:label "Cast"
                               :valid? true
                               :on-confirm #(rf/dispatch [::selection-events/confirm-selection])
                               :extra-class "bg-gy-flashback-border"}]]]]))


;; === Pay X Life Selection ===

(defn pay-x-life-selection-modal
  "Modal for selecting X value for a spell with pay-X-life cost."
  [selection]
  (let [selected-x (or (:selection/selected-x selection) 0)
        max-x (or (:selection/max-x selection) 0)]
    [:div {:class common/overlay-class}
     [:div {:class (common/container-class {:max-width "400px" :border-color "gy-flashback-border"})}
      [:h2 {:class "text-text m-0 mb-2 text-lg"} "Pay X Life"]
      [:p {:class "text-health-good m-0 mb-4 text-sm"}
       (str "Pay " selected-x " life (max " max-x ")")]
      [:div {:class "flex items-center justify-center gap-5 mb-5"}
       [:button {:class (common/stepper-button-class (pos? selected-x))
                 :disabled (not (pos? selected-x))
                 :on-click #(rf/dispatch [::cost-events/decrement-x-value])}
        "-"]
       [:div {:class "text-[32px] text-white font-bold min-w-[60px] text-center"}
        (str "X = " selected-x)]
       [:button {:class (common/stepper-button-class (< selected-x max-x))
                 :disabled (not (< selected-x max-x))
                 :on-click #(rf/dispatch [::cost-events/increment-x-value])}
        "+"]]
      [:div {:class "flex justify-end gap-3"}
       [common/cancel-button {:label "Cancel"
                              :on-cancel #(rf/dispatch [::cost-events/cancel-x-mana-selection])}]
       [common/confirm-button {:label "Cast"
                               :valid? true
                               :on-confirm #(rf/dispatch [::selection-events/confirm-selection])
                               :extra-class "bg-gy-flashback-border"}]]]]))


;; === Storm Split ===

(defn storm-split-target-label
  "Human-readable label for a storm-split target player-id."
  [target-id]
  (case target-id
    :player-2 "Opponent"
    :player-1 "You"
    (name target-id)))


(defn- storm-split-stepper-row
  [target-id count copy-count total-allocated]
  (let [can-decrement? (pos? count)
        can-increment? (< total-allocated copy-count)]
    [:div {:class "flex items-center justify-center gap-2 mb-3"}
     [:span {:class "text-text font-bold text-sm w-[80px] text-right mr-2"}
      (storm-split-target-label target-id)]
     [:button {:class (common/stepper-button-class can-decrement?)
               :disabled (not can-decrement?)
               :on-click #(rf/dispatch [::storm-events/adjust-storm-split target-id (- count)])}
      "\u00AB"]
     [:button {:class (common/stepper-button-class can-decrement?)
               :disabled (not can-decrement?)
               :on-click #(rf/dispatch [::storm-events/adjust-storm-split target-id -1])}
      "-"]
     [:span {:class "text-text font-bold text-[24px] min-w-[3ch] text-center"}
      count]
     [:button {:class (common/stepper-button-class can-increment?)
               :disabled (not can-increment?)
               :on-click #(rf/dispatch [::storm-events/adjust-storm-split target-id 1])}
      "+"]
     [:button {:class (common/stepper-button-class can-increment?)
               :disabled (not can-increment?)
               :on-click #(rf/dispatch [::storm-events/adjust-storm-split target-id (- copy-count total-allocated)])}
      "\u00BB"]]))


(defn storm-split-modal
  "Modal for distributing storm copies across targets."
  [selection]
  (let [copy-count (:selection/copy-count selection)
        valid-targets (:selection/valid-targets selection)
        allocation (:selection/allocation selection)
        total-allocated (apply + (vals allocation))
        valid? (= total-allocated copy-count)
        source-name @(rf/subscribe [::subs/storm-split-source-name])]
    [common/modal-wrapper {:title "Distribute Storm Copies"
                           :max-width "400px"
                           :text-align "center"}
     [:p {:class "text-text-muted text-sm m-0 mb-5"}
      (str copy-count " " (if (= 1 copy-count) "copy" "copies")
           (when source-name (str " of " source-name)))]
     [:div {:class "mb-4"}
      (for [target valid-targets]
        ^{:key target}
        [storm-split-stepper-row target (get allocation target 0) copy-count total-allocated])]
     [:p {:class (str "text-sm m-0 mb-4 "
                      (if valid? "text-health-good" "text-health-danger"))}
      (str total-allocated " / " copy-count " assigned")]
     [:div {:class "flex justify-center gap-3"}
      [common/cancel-button {:label "Reset"
                             :on-cancel #(rf/dispatch [::storm-events/reset-storm-split])}]
      [common/confirm-button {:label "Confirm"
                              :valid? valid?
                              :on-confirm #(rf/dispatch [::selection-events/confirm-selection])
                              :extra-class "py-2.5 px-8"}]]]))


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
   Renders label + [-] count [+] for a single target."
  [target-id count copy-count total-allocated]
  (let [can-decrement? (pos? count)
        can-increment? (< total-allocated copy-count)]
    [:div {:class "flex items-center justify-between gap-3 mb-3"}
     [:span {:class "text-text font-bold text-sm"}
      (storm-split-target-label target-id)]
     [:div {:class "flex items-center justify-center gap-2"}
      [:button {:class (common/stepper-button-class can-decrement?)
                :disabled (not can-decrement?)
                :on-click #(rf/dispatch [::storm-events/adjust-storm-split target-id -1])}
       "-"]
      [:span {:class "text-text font-bold text-lg min-w-[3ch] text-center"}
       count]
      [:button {:class (common/stepper-button-class can-increment?)
                :disabled (not can-increment?)
                :on-click #(rf/dispatch [::storm-events/adjust-storm-split target-id 1])}
       "+"]]]))


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
