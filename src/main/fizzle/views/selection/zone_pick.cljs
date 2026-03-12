(ns fizzle.views.selection.zone-pick
  "Generic zone-pick modal component for card-grid selections.
   Data-driven: selection state fields drive title, counter, and buttons."
  (:require
    [fizzle.events.selection :as selection-events]
    [fizzle.events.selection.costs :as cost-events]
    [fizzle.subs.game :as subs]
    [fizzle.views.selection.common :as common]
    [re-frame.core :as rf]))


(def ^:private type-config
  "Per-type overrides. Keys: :title :empty-text :border-color :confirm-extra-class"
  {:discard {:title "Select cards to discard"}
   :tutor {:title "Search your library" :empty-text "No matching cards in library"}
   :hand-reveal-discard {:title "Opponent's Hand" :empty-text "Hand is empty"}
   :pile-choice {:title "Choose card for hand"}
   :graveyard-return {:title "Return cards from graveyard to hand"
                      :border-color "gy-flashback-border" :empty-text "No cards in graveyard"
                      :confirm-extra-class "bg-gy-flashback-border"}
   :exile-cards-cost {:title "Select cards to exile (Flashback cost)"
                      :border-color "gy-flashback-border" :empty-text "No eligible cards"
                      :confirm-extra-class "bg-gy-flashback-border"}
   :peek-and-select {:title "Look at the top cards of your library"
                     :border-color "gy-flashback-border" :empty-text "No cards to peek"
                     :confirm-extra-class "bg-gy-flashback-border"}
   :discard-specific-cost {:title "Select cards to discard"}
   :untap-lands {:title "Select lands to untap" :empty-text "No tapped lands"}})


(defn- counter-text
  [selection]
  (let [t (:selection/type selection)
        n (count (:selection/selected selection))
        r (:selection/select-count selection)]
    (case t
      :tutor (if (and r (> r 1)) (str n " / " r " cards selected")
                 (if (= n 1) "1 card selected" "Select a card or Find Nothing"))
      :hand-reveal-discard (let [vt (set (:selection/valid-targets selection))]
                             (cond (empty? vt) "No valid targets"
                                   (seq (:selection/selected selection)) "1 card selected"
                                   :else "Choose a noncreature, nonland card"))
      :pile-choice (str n " / " (:selection/hand-count selection)
                        " selected for hand (rest go to graveyard)")
      :graveyard-return (str "Select up to " r " cards (" n " selected)")
      :exile-cards-cost (str "Select blue cards to exile. X = " n " (select at least 1)")
      :peek-and-select (str "Select up to " r " card(s) for your hand (" n " selected). "
                            "Remaining cards go to bottom of library.")
      :untap-lands (str "Select up to " r " lands (" n " selected)")
      (str n " / " r " selected"))))


(defn- secondary-button
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
    [common/cancel-button {:label "Clear"
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
        valid-targets (some-> (:selection/valid-targets selection) set)
        has-reveal? (#{:hand-reveal-discard :discard-specific-cost} sel-type)
        always-good? (#{:graveyard-return :exile-cards-cost :peek-and-select} sel-type)]
    [:div {:class common/overlay-class}
     [:div {:class (common/container-class
                     (cond-> {}
                       (:border-color config) (assoc :border-color (:border-color config))))}
      [:h2 {:class "text-text m-0 mb-2 text-lg"} (or (:title config) "Select cards")]
      [:p {:class (str "m-0 mb-4 text-sm "
                       (if (or valid? always-good?) "text-health-good" "text-health-danger"))}
       (counter-text selection)]
      [:div {:class "flex flex-wrap gap-2.5 mb-5 min-h-[60px]"}
       (if (seq cards)
         (for [obj cards]
           ^{:key (:object/id obj)}
           [common/selection-card-view obj
            (contains? (:selection/selected selection) (:object/id obj))
            ::selection-events/toggle-selection
            (if (and has-reveal? valid-targets)
              (contains? valid-targets (:object/id obj))
              true)])
         [:div {:class "text-perm-text-tapped"}
          (or (:empty-text config) "No cards available")])]
      [:div {:class "flex justify-end gap-3"}
       [secondary-button sel-type]
       [common/confirm-button
        {:label (confirm-label sel-type selection)
         :valid? valid?
         :on-confirm #(rf/dispatch [::selection-events/confirm-selection])
         :extra-class (:confirm-extra-class config)}]]]]))
