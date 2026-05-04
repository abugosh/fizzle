(ns fizzle.views.selection.binary-choice
  "Inline component for :binary-choice selections.

   Renders one button per entry in :selection/choices.
   Domain-agnostic: handles both :replacement-choice and :unless-pay.

   Toggle dispatch shape is determined by :selection/valid-targets presence:
     - Present (e.g. [:pay :decline]) → dispatches (:choice/action choice) as keyword
       (used by :unless-pay where selected contains :pay or :decline)
     - Absent → dispatches full choice map
       (used by :replacement-choice where selected contains choice map)

   Player flow (single-click confirm, Requirement 6):
     1. Buttons rendered for each choice in :selection/choices
     2. Player clicks a button → dispatches toggle-selection then confirm-selection
        (both dispatched on the same click — no separate Confirm step)"
  (:require
    [fizzle.events.selection :as selection-events]
    [fizzle.views.keyboard :as kbd]
    [re-frame.core :as rf]))


(defn- dispatch-action?
  "Returns true when toggle-selection should dispatch (:choice/action choice)
   instead of the full choice map. Keyed on :selection/valid-targets presence:
   domains with valid-targets dispatch the action value; domains without
   (replacement-choice) dispatch the full choice map for apply-domain-policy."
  [selection]
  (boolean (:selection/valid-targets selection)))


(defn binary-choice-view
  "Inline component for :binary-choice selections.

   Reads :selection/choices from selection. For each choice, renders a
   button with :choice/label as text.

   Single-click confirm (Requirement 6): each button dispatches both
   toggle-selection and confirm-selection on click.

   Toggle dispatch:
     - If :selection/valid-targets is present (e.g. [:pay :decline]),
       dispatches toggle-selection with (:choice/action choice) keyword.
     - Otherwise (replacement-choice), dispatches toggle-selection with full choice map."
  [selection]
  (let [choices  (:selection/choices selection)
        action-mode? (dispatch-action? selection)]
    [:div {:class "mb-4 border-2 border-border-accent rounded-lg p-3"}
     [:div {:class "text-text-label mb-1.5 text-xs"} (or (:selection/description selection) "CHOOSE AN OPTION")]
     [:div {:class "flex flex-col gap-2"}
      (for [[idx choice] (map-indexed vector choices)]
        (let [toggle-val (if action-mode? (:choice/action choice) choice)
              mechanism (:selection/mechanism selection)
              action-kw (keyword (str "choose-" (inc idx)))
              hint (kbd/hint-for-action mechanism action-kw)]
          ^{:key (:choice/action choice)}
          [:button {:class "py-2 px-4 rounded font-bold text-sm text-text border-2 border-border bg-surface-raised hover:border-text-muted cursor-pointer transition-all duration-100"
                    :on-click (fn []
                                (rf/dispatch [::selection-events/toggle-selection toggle-val])
                                (rf/dispatch [::selection-events/confirm-selection]))}
           (:choice/label choice)
           (when hint
             [:span {:class "ml-2 text-xs text-text-muted inline"}
              "[" hint "]"])]))]]))
