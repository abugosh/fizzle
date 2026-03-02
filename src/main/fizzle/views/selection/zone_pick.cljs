(ns fizzle.views.selection.zone-pick
  "Generic zone-pick modal component for card-grid selections.
   Data-driven: selection state fields drive title, counter, and buttons."
  (:require
    [fizzle.events.selection :as selection-events]
    [fizzle.events.selection.costs :as cost-events]
    [fizzle.subs.game :as subs]
    [fizzle.views.card-styles :as card-styles]
    [fizzle.views.selection.common :as common]
    [re-frame.core :as rf]))


(defn- hand-reveal-card-view
  "Card with selectable/dimmed states for hand-reveal and discard-specific."
  [obj selected? selectable?]
  (let [card-name (get-in obj [:object/card :card/name])
        object-id (:object/id obj)
        card-types (get-in obj [:object/card :card/types])
        card-colors (get-in obj [:object/card :card/colors])
        border-class (cond
                       selected? "border-[3px] border-border-accent"
                       selectable? (str "border-2 " (card-styles/get-type-border-class card-types false))
                       :else "border-2 border-border opacity-50")
        bg-class (card-styles/get-color-identity-bg-class card-colors card-types)]
    [:div {:class (str "rounded-md px-3.5 py-2.5 min-w-[90px] text-center "
                       "select-none text-text transition-all duration-100 "
                       (if selectable? "cursor-pointer " "cursor-not-allowed ")
                       border-class " " bg-class)
           :on-click (when selectable?
                       #(rf/dispatch [::selection-events/toggle-selection object-id]))}
     card-name]))


;; === Per-type config maps ===

(def ^:private type-config
  {:discard {:title "Select cards to discard"}
   :tutor {:title "Search your library"
           :empty-text "No matching cards in library"}
   :hand-reveal-discard {:title "Opponent's Hand"
                         :empty-text "Hand is empty"}
   :pile-choice {:title "Choose card for hand"}
   :graveyard-return {:title "Return cards from graveyard to hand"
                      :border-color "gy-flashback-border"
                      :empty-text "No cards in graveyard"
                      :confirm-extra-class "bg-gy-flashback-border"}
   :exile-cards-cost {:title "Select cards to exile (Flashback cost)"
                      :border-color "gy-flashback-border"
                      :empty-text "No eligible cards"
                      :confirm-extra-class "bg-gy-flashback-border"}
   :peek-and-select {:title "Look at the top cards of your library"
                     :border-color "gy-flashback-border"
                     :empty-text "No cards to peek"
                     :confirm-extra-class "bg-gy-flashback-border"}
   :discard-specific-cost {:title "Select cards to discard"}})


(defn- counter-text
  "Generate counter text from selection state."
  [selection]
  (let [sel-type (:selection/type selection)
        selected (:selection/selected selection)
        current (count selected)
        required (:selection/select-count selection)]
    (case sel-type
      :tutor (if (and required (> required 1))
               (str current " / " required " cards selected")
               (if (= current 1) "1 card selected" "Select a card or Find Nothing"))
      :hand-reveal-discard (let [vt (set (:selection/valid-targets selection))]
                             (if (empty? vt) "No valid targets"
                                 (if (seq selected) "1 card selected"
                                     "Choose a noncreature, nonland card")))
      :pile-choice (str current " / " (:selection/hand-count selection)
                        " selected for hand (rest go to graveyard)")
      :graveyard-return (str "Select up to " required " cards (" current " selected)")
      :exile-cards-cost (str "Select blue cards to exile. X = " current " (select at least 1)")
      :peek-and-select (str "Select up to " required " card(s) for your hand (" current " selected). "
                            "Remaining cards go to bottom of library.")
      ;; default: discard, discard-specific-cost
      (str current " / " required " selected"))))


(defn- secondary-button
  "Render the secondary (left) button for a selection type."
  [sel-type]
  (case sel-type
    :tutor [common/cancel-button
            {:label "Find Nothing"
             :on-cancel #(do (rf/dispatch [::selection-events/cancel-selection])
                             (rf/dispatch [::selection-events/confirm-selection]))}]
    :pile-choice [common/cancel-button
                  {:label "Random"
                   :on-cancel #(rf/dispatch [::selection-events/select-random-pile-choice])}]
    :exile-cards-cost [common/cancel-button
                       {:label "Cancel"
                        :on-cancel #(rf/dispatch [::cost-events/cancel-exile-cards-selection])}]
    ;; default: Clear button
    [common/cancel-button
     {:label "Clear"
      :on-cancel #(rf/dispatch [::selection-events/cancel-selection])}]))


(defn- confirm-label
  [sel-type selection]
  (case sel-type
    :tutor (if (and (:selection/select-count selection)
                    (> (:selection/select-count selection) 1))
             "Select Cards" "Select Card")
    :exile-cards-cost (str "Exile " (count (:selection/selected selection)) " cards")
    :discard-specific-cost "Discard"
    "Confirm"))


(defn zone-pick-modal
  "Generic card-grid selection modal driven by selection state."
  [selection cards]
  (let [sel-type (:selection/type selection)
        config (get type-config sel-type {})
        valid? @(rf/subscribe [::subs/selection-valid?])
        valid-targets (when (:selection/valid-targets selection)
                        (set (:selection/valid-targets selection)))
        has-reveal? (#{:hand-reveal-discard :discard-specific-cost} sel-type)
        counter (counter-text selection)
        ;; graveyard/exile/peek use always-good counter style
        always-good? (#{:graveyard-return :exile-cards-cost :peek-and-select} sel-type)]
    [:div {:class common/overlay-class}
     [:div {:class (common/container-class
                     (cond-> {}
                       (:border-color config) (assoc :border-color (:border-color config))))}
      [:h2 {:class "text-text m-0 mb-2 text-lg"}
       (or (:title config) "Select cards")]
      [:p {:class (str "m-0 mb-4 text-sm "
                       (if (or valid? always-good?) "text-health-good" "text-health-danger"))}
       counter]
      [:div {:class "flex flex-wrap gap-2.5 mb-5 min-h-[60px]"}
       (if (seq cards)
         (for [obj cards]
           ^{:key (:object/id obj)}
           (if has-reveal?
             [hand-reveal-card-view obj
              (contains? (:selection/selected selection) (:object/id obj))
              (if valid-targets
                (contains? valid-targets (:object/id obj))
                true)]
             [common/selection-card-view obj
              (contains? (:selection/selected selection) (:object/id obj))
              ::selection-events/toggle-selection]))
         [:div {:class "text-perm-text-tapped"}
          (or (:empty-text config) "No cards available")])]
      [:div {:class "flex justify-end gap-3"}
       [secondary-button sel-type]
       [common/confirm-button
        {:label (confirm-label sel-type selection)
         :valid? valid?
         :on-confirm #(rf/dispatch [::selection-events/confirm-selection])
         :extra-class (:confirm-extra-class config)}]]]]))
