(ns fizzle.views.selection.binary-choice
  "Inline component for :binary-choice selections.

   Renders one button per entry in :selection/choices.
   Domain-agnostic: handles both :replacement-choice and :unless-pay.

   Toggle dispatch shape is determined by :selection/valid-targets:
     - Vector of keywords → dispatches (:choice/action choice) as keyword
       (used by :unless-pay where selected contains :pay or :decline)
     - Absent or non-keyword-vec → dispatches full choice map
       (used by :replacement-choice where selected contains choice map)

   Player flow:
     1. Buttons rendered for each choice in :selection/choices
     2. Player clicks a button → dispatches toggle-selection with choice value
     3. Player clicks Confirm → dispatches confirm-selection"
  (:require
    [fizzle.events.selection :as selection-events]
    [re-frame.core :as rf]))


(defn- keyword-targets?
  "Returns true if :selection/valid-targets is a vector of keywords.
   Used to determine toggle dispatch shape."
  [selection]
  (let [valid-targets (:selection/valid-targets selection)]
    (and (vector? valid-targets)
         (seq valid-targets)
         (every? keyword? valid-targets))))


(defn binary-choice-view
  "Inline component for :binary-choice selections.

   Reads :selection/choices from selection. For each choice, renders a
   button with :choice/label as text.

   Toggle dispatch:
     - If :selection/valid-targets is a vector of keywords (e.g. [:pay :decline]),
       dispatches toggle-selection with (:choice/action choice) keyword.
     - Otherwise (replacement-choice), dispatches toggle-selection with full choice map."
  [selection]
  (let [choices  (:selection/choices selection)
        selected (first (:selection/selected selection))
        kw-mode? (keyword-targets? selection)]
    [:div {:class "mb-4 border-2 border-border-accent rounded-lg p-3"}
     [:div {:class "text-text-label mb-1.5 text-xs"} "CHOOSE AN OPTION"]
     [:div {:class "flex flex-col gap-2 mb-2"}
      (for [choice choices]
        (let [toggle-val (if kw-mode? (:choice/action choice) choice)
              selected?  (= selected toggle-val)]
          ^{:key (:choice/action choice)}
          [:button {:class (str "py-2 px-4 rounded font-bold text-sm text-text border-2 "
                                (if selected?
                                  "border-border-accent bg-modal-selected-bg"
                                  "border-border bg-surface-raised hover:border-text-muted")
                                " cursor-pointer transition-all duration-100")
                    :on-click #(rf/dispatch [::selection-events/toggle-selection toggle-val])}
           (:choice/label choice)]))]
     [:div
      [:button
       {:class (str "py-1.5 px-4 border-none rounded font-bold text-sm "
                    (if (some? selected)
                      "cursor-pointer bg-btn-enabled-bg text-white"
                      "cursor-not-allowed bg-surface-dim text-perm-text-tapped"))
        :disabled (nil? selected)
        :on-click (when (some? selected)
                    #(rf/dispatch [::selection-events/confirm-selection]))}
       "Confirm"]]]))
