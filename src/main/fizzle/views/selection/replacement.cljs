(ns fizzle.views.selection.replacement
  "Modal component for :replacement-choice selections.

   Renders discrete proceed/redirect buttons from :selection/choices.
   Data-driven: button labels come from :choice/label, never hardcoded strings.

   Player flow:
     1. Buttons rendered for each choice in :selection/choices
     2. Player clicks a button → dispatches toggle-selection with choice map
     3. Player clicks Confirm → dispatches confirm-selection
        (or auto-confirm fires if :selection/auto-confirm? true)"
  (:require
    [fizzle.events.selection :as selection-events]
    [fizzle.views.selection.common :as common]
    [re-frame.core :as rf]))


(defn replacement-choice-modal
  "Pure modal component for :replacement-choice selections.

   Renders one button per entry in :selection/choices.
   Each button shows :choice/label text and dispatches toggle-selection
   with the full choice map on click.

   A Confirm button at the bottom is enabled when a choice is selected
   (:selection/selected is non-nil)."
  [selection]
  (let [choices  (:selection/choices selection)
        selected (first (:selection/selected selection))
        valid?   (some? selected)]
    [common/modal-wrapper {:title "Replace event?" :max-width "400px" :text-align "center"}
     [:div {:class "flex flex-col gap-3 mb-5"}
      (for [choice choices]
        ^{:key (:choice/action choice)}
        [:button {:class (str "py-3 px-4 border-2 rounded-lg cursor-pointer text-text "
                              "text-sm font-medium transition-all duration-100 "
                              (if (= selected choice)
                                "border-border-accent bg-modal-selected-bg"
                                "border-border bg-surface-raised hover:border-text-muted"))
                  :on-click #(rf/dispatch [::selection-events/toggle-selection choice])}
         (:choice/label choice)])]
     [:div {:class "flex justify-center"}
      [common/confirm-button {:label "Confirm" :valid? valid?
                              :on-confirm #(rf/dispatch [::selection-events/confirm-selection])
                              :extra-class "py-2.5 px-8"}]]]))
