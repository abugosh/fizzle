(ns fizzle.views.modals
  (:require
    [clojure.string :as str]
    [fizzle.events.game :as events]
    [fizzle.events.selection :as selection-events]
    [fizzle.events.selection.costs :as cost-events]
    [fizzle.events.selection.storm :as storm-events]
    [fizzle.subs.game :as subs]
    [fizzle.views.card-styles :as card-styles]
    [re-frame.core :as rf]))


;; === Shared Class Helpers ===

(def overlay-class
  "fixed inset-0 bg-black/80 flex items-center justify-center z-[1000]")


(defn container-class
  [{:keys [max-width border-color]
    :or {max-width "600px" border-color "border-accent"}}]
  (str "bg-surface-raised border-2 border-" border-color
       " rounded-xl p-6 w-[90%] max-w-[" max-width "]"))


(def header-class
  "text-text text-lg m-0 mb-4")


;; === Shared Components ===

(defn confirm-button
  [{:keys [label valid? on-confirm extra-class]}]
  [:button {:class (str "py-2 px-5 border-none rounded font-bold text-sm "
                        (if valid?
                          "cursor-pointer bg-btn-enabled-bg text-white"
                          "cursor-not-allowed bg-surface-dim text-perm-text-tapped")
                        (when extra-class (str " " extra-class)))
            :disabled (not valid?)
            :on-click on-confirm}
   label])


(defn cancel-button
  [{:keys [label on-cancel]}]
  [:button {:class "py-2 px-5 border border-border rounded cursor-pointer bg-surface-dim text-perm-text text-sm"
            :on-click on-cancel}
   label])


(defn modal-wrapper
  [{:keys [title max-width border-color text-align]} & children]
  [:div {:class overlay-class}
   (into [:div {:class (str (container-class {:max-width max-width
                                              :border-color border-color})
                            (when text-align (str " text-" text-align)))}
          [:h2 {:class header-class} title]]
         children)])


;; === Player Target Modal ===

(defn player-target-button
  "Button to select a player as target."
  [player-id label selected?]
  [:button {:class (str "py-4 px-8 m-2 rounded-lg cursor-pointer text-text text-base "
                        "font-bold min-w-[120px] transition-all duration-100 "
                        (if selected?
                          "border-[3px] border-border-accent bg-modal-selected-bg"
                          "border-2 border-border bg-surface-raised"))
            :on-click #(rf/dispatch [::selection-events/toggle-selection player-id])}
   label])


;; === Object Target Modal ===

(defn- object-target-card-view
  "A card in the object-target modal, showing selected state."
  [obj selected select-event]
  (let [card-name (get-in obj [:object/card :card/name])
        object-id (:object/id obj)
        is-selected? (contains? selected object-id)
        card-types (get-in obj [:object/card :card/types])
        card-colors (get-in obj [:object/card :card/colors])
        border-class (if is-selected?
                       "border-[3px] border-border-accent"
                       (str "border-2 " (card-styles/get-type-border-class card-types false)))
        bg-class (card-styles/get-color-identity-bg-class card-colors card-types)]
    ^{:key object-id}
    [:div {:class (str "rounded-md px-3.5 py-2.5 cursor-pointer min-w-[90px] text-center "
                       "select-none text-text transition-all duration-100 "
                       border-class " " bg-class)
           :on-click #(rf/dispatch [select-event object-id])}
     card-name]))


(defn object-target-modal
  "Modal for selecting an object as target.
   Used for ability targeting (e.g., Seal of Cleansing) and cast-time targeting (e.g., Recoup).
   Takes the selection map, cards, and an options map with:
     :select-event   - event to dispatch when a card is clicked
     :confirm-event  - event to dispatch on confirm
     :default-zone   - fallback zone name if not in target-requirement
     :selected-label - text when a target is selected
     :unselected-label - text when no target is selected"
  [selection cards {:keys [select-event confirm-event default-zone
                           selected-label unselected-label]}]
  (let [selected (or (:selection/selected selection) #{})
        target-req (:selection/target-requirement selection)
        zone-name (name (or (:target/zone target-req) default-zone))
        valid? @(rf/subscribe [::subs/selection-valid?])]
    [:div {:class overlay-class}
     [:div {:class (container-class {})}
      ;; Header
      [:h2 {:class "text-text m-0 mb-2 text-lg"}
       (str "Choose target from " zone-name)]
      ;; Instructions
      [:p {:class (str "m-0 mb-4 text-sm "
                       (if valid? "text-health-good" "text-health-danger"))}
       (if valid? selected-label unselected-label)]
      ;; Card grid
      [:div {:class "flex flex-wrap gap-2.5 mb-5 min-h-[60px]"}
       (if (seq cards)
         (for [obj cards]
           [object-target-card-view obj selected select-event])
         [:div {:class "text-perm-text-tapped"}
          "No valid targets"])]
      ;; Confirm button
      [:div {:class "flex justify-end"}
       [confirm-button {:label "Confirm"
                        :valid? valid?
                        :on-confirm #(rf/dispatch [confirm-event])}]]]]))


(defn player-target-modal
  "Modal for selecting a player as target.
   Takes the selection map and a confirm-event keyword.
   Used for spell effects, ability targeting, and cast-time targeting.
   Only shows buttons for players in :selection/valid-targets (or both if nil).
   All dispatch ::confirm-selection."
  [selection confirm-event]
  (let [selected (or (:selection/selected selection) #{})
        valid? @(rf/subscribe [::subs/selection-valid?])
        valid-targets (when-let [vt (:selection/valid-targets selection)]
                        (set vt))
        show-self? (or (nil? valid-targets) (contains? valid-targets :player-1))
        show-opponent? (or (nil? valid-targets) (contains? valid-targets :opponent))]
    [modal-wrapper {:title "Choose target player"
                    :max-width "400px"
                    :text-align "center"}
     ;; Player buttons — only for valid targets
     [:div {:class "flex justify-center mb-5"}
      (when show-self?
        [player-target-button :player-1 "You" (contains? selected :player-1)])
      (when show-opponent?
        [player-target-button :opponent "Opponent" (contains? selected :opponent)])]
     ;; Confirm button
     [:div {:class "flex justify-center"}
      [confirm-button {:label "Confirm"
                       :valid? valid?
                       :on-confirm #(rf/dispatch [confirm-event])
                       :extra-class "py-2.5 px-8"}]]]))


;; === Card Selection Modals ===

(defn selection-card-view
  "A card in the selection modal, showing selected state.
   Optionally accepts a custom toggle-event to dispatch instead of the default."
  ([obj selected?]
   (selection-card-view obj selected? nil))
  ([obj selected? toggle-event]
   (let [card-name (get-in obj [:object/card :card/name])
         object-id (:object/id obj)
         card-types (get-in obj [:object/card :card/types])
         card-colors (get-in obj [:object/card :card/colors])
         border-class (if selected?
                        "border-[3px] border-border-accent"
                        (str "border-2 " (card-styles/get-type-border-class card-types false)))
         bg-class (card-styles/get-color-identity-bg-class card-colors card-types)]
     [:div {:class (str "rounded-md px-3.5 py-2.5 cursor-pointer min-w-[90px] text-center "
                        "select-none text-text transition-all duration-100 "
                        border-class " " bg-class)
            :on-click #(rf/dispatch [(or toggle-event ::selection-events/toggle-selection) object-id])}
      card-name])))


(defn card-selection-modal
  "Modal for selecting cards (discard or tutor)."
  [selection cards]
  (let [selected (:selection/selected selection)
        required-count (:selection/select-count selection)
        current-count (count selected)
        selection-type (:selection/type selection)
        is-tutor? (= selection-type :tutor)
        is-multi-select? (and is-tutor? required-count (> required-count 1))
        valid? @(rf/subscribe [::subs/selection-valid?])
        confirm-event ::selection-events/confirm-selection]
    [:div {:class overlay-class}
     [:div {:class (container-class {})}
      ;; Header
      [:h2 {:class "text-text m-0 mb-2 text-lg"}
       (case selection-type
         :discard "Select cards to discard"
         :tutor "Search your library"
         "Select cards")]
      ;; Counter / instructions
      [:p {:class (str "m-0 mb-4 text-sm "
                       (if valid? "text-health-good" "text-health-danger"))}
       (cond
         is-multi-select?
         (str current-count " / " required-count " cards selected")

         is-tutor?
         (if (= current-count 1)
           "1 card selected"
           "Select a card or Find Nothing")

         :else
         (str current-count " / " required-count " selected"))]
      ;; Card grid
      [:div {:class "flex flex-wrap gap-2.5 mb-5 min-h-[60px]"}
       (if (seq cards)
         (for [obj cards]
           ^{:key (:object/id obj)}
           [selection-card-view obj (contains? selected (:object/id obj))])
         [:div {:class "text-perm-text-tapped"}
          (if is-tutor?
            "No matching cards in library"
            "No cards available")])]
      ;; Buttons
      [:div {:class "flex justify-end gap-3"}
       ;; For tutors: "Find Nothing" button (fail-to-find)
       (when is-tutor?
         [cancel-button {:label "Find Nothing"
                         :on-cancel #(do
                                       (rf/dispatch [::selection-events/cancel-selection])
                                       (rf/dispatch [confirm-event]))}])
       ;; For discard: Clear button
       (when (not is-tutor?)
         [cancel-button {:label "Clear"
                         :on-cancel #(rf/dispatch [::selection-events/cancel-selection])}])
       ;; Confirm button
       [confirm-button {:label (cond
                                 is-multi-select? "Select Cards"
                                 is-tutor? "Select Card"
                                 :else "Confirm")
                        :valid? valid?
                        :on-confirm #(rf/dispatch [confirm-event])}]]]]))


(defn- hand-reveal-card-view
  "A card in the hand-reveal modal. Selectable cards are clickable,
   non-selectable cards are dimmed and inert."
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


(defn hand-reveal-discard-modal
  "Modal for hand-reveal discard (Duress). Shows the full opponent hand
   with all cards visible. Only cards matching criteria are selectable;
   non-matching cards are dimmed."
  [selection cards]
  (let [selected (:selection/selected selection)
        valid-targets (set (:selection/valid-targets selection))
        valid? @(rf/subscribe [::subs/selection-valid?])]
    [:div {:class overlay-class}
     [:div {:class (container-class {})}
      [:h2 {:class "text-text m-0 mb-2 text-lg"}
       "Opponent's Hand"]
      [:p {:class (str "m-0 mb-4 text-sm "
                       (if valid? "text-health-good" "text-health-danger"))}
       (if (empty? valid-targets)
         "No valid targets"
         (if (seq selected)
           "1 card selected"
           "Choose a noncreature, nonland card"))]
      [:div {:class "flex flex-wrap gap-2.5 mb-5 min-h-[60px]"}
       (if (seq cards)
         (for [obj cards]
           ^{:key (:object/id obj)}
           [hand-reveal-card-view obj
            (contains? selected (:object/id obj))
            (contains? valid-targets (:object/id obj))])
         [:div {:class "text-perm-text-tapped"} "Hand is empty"])]
      [:div {:class "flex justify-end gap-3"}
       [cancel-button {:label "Clear"
                       :on-cancel #(rf/dispatch [::selection-events/cancel-selection])}]
       [confirm-button {:label "Confirm"
                        :valid? valid?
                        :on-confirm #(rf/dispatch [::selection-events/confirm-selection])}]]]]))


(defn pile-choice-modal
  "Modal for pile choice selection (Intuition-style).
   Player chooses which card(s) go to hand, rest go to graveyard."
  [selection cards]
  (let [selected (:selection/selected selection)
        hand-count (:selection/hand-count selection)
        current-count (count selected)
        valid? @(rf/subscribe [::subs/selection-valid?])]
    [:div {:class overlay-class}
     [:div {:class (container-class {})}
      ;; Header
      [:h2 {:class "text-text m-0 mb-2 text-lg"}
       "Choose card for hand"]
      ;; Instructions
      [:p {:class (str "m-0 mb-4 text-sm "
                       (if valid? "text-health-good" "text-health-danger"))}
       (str current-count " / " hand-count " selected for hand (rest go to graveyard)")]
      ;; Card grid
      [:div {:class "flex flex-wrap gap-2.5 mb-5 min-h-[60px]"}
       (for [obj cards]
         ^{:key (:object/id obj)}
         [selection-card-view obj (contains? selected (:object/id obj))])]
      ;; Buttons
      [:div {:class "flex justify-end gap-3"}
       ;; Random button
       [cancel-button {:label "Random"
                       :on-cancel #(rf/dispatch [::selection-events/select-random-pile-choice])}]
       ;; Confirm button
       [confirm-button {:label "Confirm"
                        :valid? valid?
                        :on-confirm #(rf/dispatch [::selection-events/confirm-selection])}]]]]))


(defn graveyard-selection-modal
  "Modal for returning cards from graveyard to hand (Ill-Gotten Gains style).
   Player can select 0 to max-count cards (not exact count like discard)."
  [selection cards]
  (let [selected (:selection/selected selection)
        max-count (:selection/select-count selection)
        current-count (count selected)
        valid? @(rf/subscribe [::subs/selection-valid?])]
    [:div {:class overlay-class}
     [:div {:class (container-class {:border-color "gy-flashback-border"})}
      ;; Header
      [:h2 {:class "text-text m-0 mb-2 text-lg"}
       "Return cards from graveyard to hand"]
      ;; Instructions
      [:p {:class "text-health-good m-0 mb-4 text-sm"}
       (str "Select up to " max-count " cards (" current-count " selected)")]
      ;; Card grid
      [:div {:class "flex flex-wrap gap-2.5 mb-5 min-h-[60px]"}
       (if (seq cards)
         (for [obj cards]
           ^{:key (:object/id obj)}
           [selection-card-view obj (contains? selected (:object/id obj))])
         [:div {:class "text-perm-text-tapped"}
          "No cards in graveyard"])]
      ;; Buttons
      [:div {:class "flex justify-end gap-3"}
       ;; Clear button
       [cancel-button {:label "Clear"
                       :on-cancel #(rf/dispatch [::selection-events/cancel-selection])}]
       ;; Confirm button (always valid since 0 is allowed)
       [confirm-button {:label "Confirm"
                        :valid? valid?
                        :on-confirm #(rf/dispatch [::selection-events/confirm-selection])
                        :extra-class "bg-gy-flashback-border"}]]]]))


(defn exile-cards-selection-modal
  "Modal for selecting cards to exile for an additional cost (e.g., Flash of Insight flashback).
   Player must select at least 1 card. The count of selected cards becomes X."
  [selection cards]
  (let [selected (:selection/selected selection)
        current-count (count selected)
        valid? @(rf/subscribe [::subs/selection-valid?])]
    [:div {:class overlay-class}
     [:div {:class (container-class {:border-color "gy-flashback-border"})}
      ;; Header
      [:h2 {:class "text-text m-0 mb-2 text-lg"}
       "Select cards to exile (Flashback cost)"]
      ;; Instructions
      [:p {:class "text-health-good m-0 mb-4 text-sm"}
       (str "Select blue cards to exile. X = " current-count " (select at least 1)")]
      ;; Card grid
      [:div {:class "flex flex-wrap gap-2.5 mb-5 min-h-[60px]"}
       (if (seq cards)
         (for [obj cards]
           ^{:key (:object/id obj)}
           [selection-card-view obj (contains? selected (:object/id obj))
            ::selection-events/toggle-selection])
         [:div {:class "text-perm-text-tapped"}
          "No eligible cards"])]
      ;; Buttons
      [:div {:class "flex justify-end gap-3"}
       [cancel-button {:label "Cancel"
                       :on-cancel #(rf/dispatch [::cost-events/cancel-exile-cards-selection])}]
       [confirm-button {:label (str "Exile " current-count " cards")
                        :valid? valid?
                        :on-confirm #(rf/dispatch [::selection-events/confirm-selection])
                        :extra-class "bg-gy-flashback-border"}]]]]))


(defn peek-selection-modal
  "Modal for peek-and-select effect (e.g., Flash of Insight).
   Player sees top X cards and selects up to N for hand."
  [selection cards]
  (let [selected (:selection/selected selection)
        select-count (:selection/select-count selection)
        current-count (count selected)
        valid? @(rf/subscribe [::subs/selection-valid?])]
    [:div {:class overlay-class}
     [:div {:class (container-class {:border-color "gy-flashback-border"})}
      ;; Header
      [:h2 {:class "text-text m-0 mb-2 text-lg"}
       "Look at the top cards of your library"]
      ;; Instructions
      [:p {:class "text-health-good m-0 mb-4 text-sm"}
       (str "Select up to " select-count " card(s) for your hand (" current-count " selected). "
            "Remaining cards go to bottom of library.")]
      ;; Card grid
      [:div {:class "flex flex-wrap gap-2.5 mb-5 min-h-[60px]"}
       (if (seq cards)
         (for [obj cards]
           ^{:key (:object/id obj)}
           [selection-card-view obj (contains? selected (:object/id obj))
            ::selection-events/toggle-selection])
         [:div {:class "text-perm-text-tapped"}
          "No cards to peek"])]
      ;; Buttons
      [:div {:class "flex justify-end gap-3"}
       [cancel-button {:label "Clear"
                       :on-cancel #(rf/dispatch [::selection-events/cancel-selection])}]
       [confirm-button {:label "Confirm"
                        :valid? valid?
                        :on-confirm #(rf/dispatch [::selection-events/confirm-selection])
                        :extra-class "bg-gy-flashback-border"}]]]]))


;; === Scry Modal ===

(defn- scry-card-view
  "A card in the scry selection modal, showing pile assignment.
   Scry pile colors take priority over color identity."
  [obj pile]
  (let [card (:object/card obj)
        card-name (or (:card/name card) "Unknown")
        card-types (:card/types card)
        card-colors (:card/colors card)
        bg-class (case pile
                   :top "bg-scry-top-bg"
                   :bottom "bg-scry-bottom-bg"
                   (card-styles/get-color-identity-bg-class card-colors card-types))]
    [:div {:class (str "w-[100px] p-2 rounded-md border border-border-accent "
                       "text-center text-[11px] text-text cursor-pointer "
                       bg-class)}
     card-name]))


(defn scry-modal
  "Modal for scry selection - assign cards to top or bottom piles."
  [selection]
  (let [scry-data @(rf/subscribe [::subs/scry-cards])
        all-cards (:cards scry-data)
        top-pile-ids (set (:selection/top-pile selection))
        bottom-pile-ids (set (:selection/bottom-pile selection))
        assigned (into top-pile-ids bottom-pile-ids)
        unassigned-cards (remove #(contains? assigned (:object/id %)) all-cards)
        top-cards (filter #(contains? top-pile-ids (:object/id %)) all-cards)
        bottom-cards (filter #(contains? bottom-pile-ids (:object/id %)) all-cards)]
    [:div {:class overlay-class}
     [:div {:class (container-class {})}
      ;; Header
      [:h2 {:class header-class}
       (str "Scry " (count all-cards))]

      ;; Top Pile section
      [:div {:class "mb-4"}
       [:h3 {:class "text-health-good text-sm m-0 mb-2"}
        "Top of Library (first)"]
       [:div {:class "flex flex-wrap gap-2 min-h-[40px]"}
        (for [obj top-cards]
          ^{:key (:object/id obj)}
          [:div {:on-click #(rf/dispatch [::selection-events/scry-unassign (:object/id obj)])}
           [scry-card-view obj :top]])]]

      ;; Unassigned cards section
      [:div {:class "mb-4"}
       [:h3 {:class "text-text-muted text-sm m-0 mb-2"}
        "Revealed Cards"]
       [:div {:class "flex flex-wrap gap-2 min-h-[40px]"}
        (for [obj unassigned-cards]
          ^{:key (:object/id obj)}
          [:div {:class "flex gap-1 items-center"}
           [scry-card-view obj nil]
           [:div {:class "flex flex-col gap-0.5"}
            [:button {:class "py-1 px-2 bg-scry-top-bg border border-health-good rounded text-text cursor-pointer text-[10px]"
                      :on-click #(rf/dispatch [::selection-events/scry-assign-top (:object/id obj)])}
             "Top"]
            [:button {:class "py-1 px-2 bg-scry-bottom-bg border border-health-critical rounded text-text cursor-pointer text-[10px]"
                      :on-click #(rf/dispatch [::selection-events/scry-assign-bottom (:object/id obj)])}
             "Bottom"]]])]]

      ;; Bottom Pile section
      [:div {:class "mb-5"}
       [:h3 {:class "text-health-critical text-sm m-0 mb-2"}
        "Bottom of Library (last)"]
       [:div {:class "flex flex-wrap gap-2 min-h-[40px]"}
        (for [obj bottom-cards]
          ^{:key (:object/id obj)}
          [:div {:on-click #(rf/dispatch [::selection-events/scry-unassign (:object/id obj)])}
           [scry-card-view obj :bottom]])]]

      ;; Confirm button
      [:button {:class "w-full p-3 bg-accent border-none rounded-lg text-white text-base cursor-pointer"
                :on-click #(rf/dispatch [::selection-events/confirm-selection])}
       "Confirm"]]]))


;; === Order Bottom Modal ===

(defn- order-bottom-card-view
  "A card in the order-bottom modal."
  [obj ordered? index]
  (let [card (:object/card obj)
        card-name (or (:card/name card) "Unknown")
        object-id (:object/id obj)
        card-types (:card/types card)
        card-colors (:card/colors card)
        border-class (if ordered?
                       "border-[3px] border-border-accent"
                       (str "border-2 " (card-styles/get-type-border-class card-types false)))
        bg-class (card-styles/get-color-identity-bg-class card-colors card-types)]
    [:div {:class (str "rounded-md px-3.5 py-2.5 cursor-pointer min-w-[90px] text-center "
                       "select-none text-text transition-all duration-100 "
                       border-class " " bg-class)
           :on-click #(rf/dispatch [(if ordered?
                                      ::selection-events/unorder-card
                                      ::selection-events/order-card)
                                    object-id])}
     (if ordered?
       (str (inc index) ". " card-name)
       card-name)]))


(defn- order-bottom-modal
  "Modal for ordering cards on the bottom of library.
   Player clicks cards to sequence them (first click = top of bottom pile).
   Click ordered card to remove from sequence."
  []
  (let [data @(rf/subscribe [::subs/order-bottom-cards])
        ordered-cards (:ordered-cards data)
        unsequenced-cards (:unsequenced-cards data)
        all-ordered? (:all-ordered? data)]
    [modal-wrapper {:title "Order cards for bottom of library"}
     ;; Instructions
     [:p {:class "text-text-muted text-sm m-0 mb-4"}
      "Click cards to order them (first click = closest to rest of library). Click ordered cards to unsequence."]
     ;; Unsequenced section
     [:div {:class "mb-4"}
      [:h3 {:class "text-text-muted text-sm m-0 mb-2"}
       "Unsequenced"]
      [:div {:class "flex flex-wrap gap-2.5 min-h-[40px]"}
       (if (seq unsequenced-cards)
         (for [obj unsequenced-cards]
           ^{:key (:object/id obj)}
           [order-bottom-card-view obj false nil])
         [:div {:class "text-perm-text-tapped text-sm"}
          "All cards sequenced"])]]
     ;; Ordered section
     [:div {:class "mb-5"}
      [:h3 {:class "text-health-good text-sm m-0 mb-2"}
       (str "Ordered (" (count ordered-cards) ")")]
      [:div {:class "flex flex-wrap gap-2.5 min-h-[40px]"}
       (for [[idx obj] (map-indexed vector ordered-cards)]
         ^{:key (:object/id obj)}
         [order-bottom-card-view obj true idx])]]
     ;; Buttons
     [:div {:class "flex justify-end gap-3"}
      [cancel-button {:label "Any Order"
                      :on-cancel #(rf/dispatch [::selection-events/any-order])}]
      [confirm-button {:label "Confirm"
                       :valid? all-ordered?
                       :on-confirm #(rf/dispatch [::selection-events/confirm-selection])}]]]))


(defn- peek-and-reorder-modal
  "Modal for reordering cards on top of library (Portent).
   Player clicks cards to set the order (first click = top of library).
   Click ordered card to remove from sequence."
  []
  (let [data @(rf/subscribe [::subs/peek-and-reorder-cards])
        ordered-cards (:ordered-cards data)
        unsequenced-cards (:unsequenced-cards data)
        all-ordered? (:all-ordered? data)]
    [modal-wrapper {:title "Reorder top of library"}
     ;; Instructions
     [:p {:class "text-text-muted text-sm m-0 mb-4"}
      "Click cards to set order (first click = top of library). Click ordered cards to unsequence."]
     ;; Unsequenced section
     [:div {:class "mb-4"}
      [:h3 {:class "text-text-muted text-sm m-0 mb-2"}
       "Unsequenced"]
      [:div {:class "flex flex-wrap gap-2.5 min-h-[40px]"}
       (if (seq unsequenced-cards)
         (for [obj unsequenced-cards]
           ^{:key (:object/id obj)}
           [order-bottom-card-view obj false nil])
         [:div {:class "text-perm-text-tapped text-sm"}
          "All cards sequenced"])]]
     ;; Ordered section
     [:div {:class "mb-5"}
      [:h3 {:class "text-health-good text-sm m-0 mb-2"}
       (str "Ordered (" (count ordered-cards) ")")]
      [:div {:class "flex flex-wrap gap-2.5 min-h-[40px]"}
       (for [[idx obj] (map-indexed vector ordered-cards)]
         ^{:key (:object/id obj)}
         [order-bottom-card-view obj true idx])]]
     ;; Buttons
     [:div {:class "flex justify-end gap-3"}
      [cancel-button {:label "Any Order"
                      :on-cancel #(rf/dispatch [::selection-events/any-order])}]
      [confirm-button {:label "Confirm"
                       :valid? all-ordered?
                       :on-confirm #(rf/dispatch [::selection-events/confirm-selection])}]]]))


;; === X Mana Selection Modal ===

(defn- stepper-button-class
  [enabled?]
  (str "w-10 h-10 border border-border rounded cursor-"
       (if enabled? "pointer" "not-allowed")
       " text-white text-xl "
       (if enabled? "bg-surface-elevated" "bg-btn-disabled-bg")))


(defn x-mana-selection-modal
  "Modal for selecting X value for a spell with X in its mana cost.
   Player chooses X from 0 to max-x."
  [selection]
  (let [selected-x (or (:selection/selected-x selection) 0)
        max-x (or (:selection/max-x selection) 0)
        mode (:selection/mode selection)
        mana-cost (:mode/mana-cost mode)
        ;; Calculate total cost with current X
        fixed-colorless (get mana-cost :colorless 0)
        total-colorless (+ fixed-colorless selected-x)]
    [:div {:class overlay-class}
     [:div {:class (container-class {:max-width "400px" :border-color "gy-flashback-border"})}
      ;; Header
      [:h2 {:class "text-text m-0 mb-2 text-lg"}
       "Choose X value"]
      ;; Instructions
      [:p {:class "text-health-good m-0 mb-4 text-sm"}
       (str "Total cost: {" total-colorless "}{U} (X = " selected-x ", max " max-x ")")]
      ;; X selector
      [:div {:class "flex items-center justify-center gap-5 mb-5"}
       [:button {:class (stepper-button-class (pos? selected-x))
                 :disabled (not (pos? selected-x))
                 :on-click #(rf/dispatch [::cost-events/decrement-x-value])}
        "-"]
       [:div {:class "text-[32px] text-white font-bold min-w-[60px] text-center"}
        (str "X = " selected-x)]
       [:button {:class (stepper-button-class (< selected-x max-x))
                 :disabled (not (< selected-x max-x))
                 :on-click #(rf/dispatch [::cost-events/increment-x-value])}
        "+"]]
      ;; Buttons
      [:div {:class "flex justify-end gap-3"}
       [cancel-button {:label "Cancel"
                       :on-cancel #(rf/dispatch [::cost-events/cancel-x-mana-selection])}]
       [confirm-button {:label "Cast"
                        :valid? true
                        :on-confirm #(rf/dispatch [::selection-events/confirm-selection])
                        :extra-class "bg-gy-flashback-border"}]]]]))


;; === Mode Selector Modal ===

(defn- format-mana-cost
  "Format a mana cost map as a string (e.g., {3}{U})."
  [mana-cost]
  (if (or (nil? mana-cost) (empty? mana-cost))
    "{0}"
    (str
      (when-let [c (:colorless mana-cost)]
        (when (pos? c) (str "{" c "}")))
      (when-let [w (:white mana-cost)]
        (apply str (repeat w "{W}")))
      (when-let [u (:blue mana-cost)]
        (apply str (repeat u "{U}")))
      (when-let [b (:black mana-cost)]
        (apply str (repeat b "{B}")))
      (when-let [r (:red mana-cost)]
        (apply str (repeat r "{R}")))
      (when-let [g (:green mana-cost)]
        (apply str (repeat g "{G}"))))))


(defn- format-additional-costs
  "Format additional costs for display."
  [additional-costs]
  (when (seq additional-costs)
    (->> additional-costs
         (map (fn [cost]
                (case (:cost/type cost)
                  :pay-life (str "Pay " (:cost/amount cost) " life")
                  :return-lands (str "Return " (:cost/count cost) " " (name (:cost/subtype cost)) "s")
                  :sacrifice (str "Sacrifice " (:cost/count cost) " " (name (:cost/subtype cost)))
                  "Additional cost")))
         (str/join ", "))))


(defn- mode-button
  "Button for selecting a casting mode."
  [mode]
  (let [mode-id (:mode/id mode)
        mana-cost (:mode/mana-cost mode)
        additional-costs (:mode/additional-costs mode)
        formatted-mana (format-mana-cost mana-cost)
        formatted-additional (format-additional-costs additional-costs)]
    [:button {:class (str "w-full py-3 px-4 mb-2 border-2 border-border-accent rounded-lg "
                          "cursor-pointer bg-mode-btn-bg text-text text-left "
                          "transition-all duration-100 hover:bg-mode-btn-hover")
              :on-click #(rf/dispatch [::events/select-casting-mode mode])}
     [:div {:class "font-bold text-sm mb-1"}
      (case mode-id
        :primary "Normal Cast"
        :flashback "Flashback"
        (name mode-id))]
     [:div {:class "text-[13px] text-text-muted"}
      formatted-mana
      (when formatted-additional
        [:span {:class "ml-2 text-cost-text"}
         (str "+ " formatted-additional)])]]))


(defn mode-selector-modal
  "Modal for selecting casting mode when multiple modes available."
  []
  (let [pending @(rf/subscribe [::subs/pending-mode-selection])]
    (when pending
      (let [modes (:modes pending)]
        [:div {:class overlay-class
               :on-click #(rf/dispatch [::events/cancel-mode-selection])}
         [:div {:class (container-class {:max-width "400px"})
                :on-click #(.stopPropagation %)}
          ;; Header
          [:h2 {:class "text-text m-0 mb-4 text-lg text-center"}
           "Choose casting mode"]
          ;; Mode buttons
          [:div {:class "flex flex-col"}
           (for [mode modes]
             ^{:key (:mode/id mode)}
             [mode-button mode])]
          ;; Cancel button
          [:button {:class (str "w-full py-2 px-4 mt-2 border border-border rounded "
                                "cursor-pointer bg-surface-dim text-text-label text-[13px]")
                    :on-click #(rf/dispatch [::events/cancel-mode-selection])}
           "Cancel"]]]))))


;; === Spell Mode Selection Modal ===
;; For modal spells (REB, BEB, Pyroblast, Hydroblast) — player chooses spell mode

(defn- spell-mode-button
  "Button for selecting a spell mode (e.g., 'Counter target blue spell')."
  [mode]
  [:button {:class (str "w-full py-3 px-4 mb-2 border-2 border-border-accent rounded-lg "
                        "cursor-pointer bg-mode-btn-bg text-text text-left "
                        "transition-all duration-100 hover:bg-mode-btn-hover")
            :on-click #(rf/dispatch [::events/select-spell-mode mode])}
   [:div {:class "font-bold text-sm"}
    (:mode/label mode)]])


(defn spell-mode-selector-modal
  "Modal for selecting spell mode when casting a modal spell."
  []
  (let [pending @(rf/subscribe [::subs/pending-spell-mode-selection])]
    (when pending
      (let [modes (:modes pending)]
        [:div {:class overlay-class
               :on-click #(rf/dispatch [::events/cancel-spell-mode-selection])}
         [:div {:class (container-class {:max-width "400px"})
                :on-click #(.stopPropagation %)}
          ;; Header
          [:h2 {:class "text-text m-0 mb-4 text-lg text-center"}
           "Choose one"]
          ;; Mode buttons
          [:div {:class "flex flex-col"}
           (for [mode modes]
             ^{:key (:mode/label mode)}
             [spell-mode-button mode])]
          ;; Cancel button
          [:button {:class (str "w-full py-2 px-4 mt-2 border border-border rounded "
                                "cursor-pointer bg-surface-dim text-text-label text-[13px]")
                    :on-click #(rf/dispatch [::events/cancel-spell-mode-selection])}
           "Cancel"]]]))))


;; === Storm Split Modal ===

(defn storm-split-target-label
  "Human-readable label for a storm-split target player-id."
  [target-id]
  (case target-id
    :opponent "Opponent"
    :player-1 "You"
    (name target-id)))


(defn- storm-split-stepper-row
  [target-id count copy-count total-allocated]
  (let [can-decrement? (pos? count)
        can-increment? (< total-allocated copy-count)]
    [:div {:class "flex items-center justify-center gap-2 mb-3"}
     [:span {:class "text-text font-bold text-sm w-[80px] text-right mr-2"}
      (storm-split-target-label target-id)]
     [:button {:class (stepper-button-class can-decrement?)
               :disabled (not can-decrement?)
               :on-click #(rf/dispatch [::storm-events/adjust-storm-split target-id (- count)])}
      "\u00AB"]
     [:button {:class (stepper-button-class can-decrement?)
               :disabled (not can-decrement?)
               :on-click #(rf/dispatch [::storm-events/adjust-storm-split target-id -1])}
      "-"]
     [:span {:class "text-text font-bold text-[24px] min-w-[3ch] text-center"}
      count]
     [:button {:class (stepper-button-class can-increment?)
               :disabled (not can-increment?)
               :on-click #(rf/dispatch [::storm-events/adjust-storm-split target-id 1])}
      "+"]
     [:button {:class (stepper-button-class can-increment?)
               :disabled (not can-increment?)
               :on-click #(rf/dispatch [::storm-events/adjust-storm-split target-id (- copy-count total-allocated)])}
      "\u00BB"]]))


(defn- storm-split-modal
  [selection]
  (let [copy-count (:selection/copy-count selection)
        valid-targets (:selection/valid-targets selection)
        allocation (:selection/allocation selection)
        total-allocated (apply + (vals allocation))
        valid? (= total-allocated copy-count)
        source-name @(rf/subscribe [::subs/storm-split-source-name])]
    [modal-wrapper {:title "Distribute Storm Copies"
                    :max-width "400px"
                    :text-align "center"}
     ;; Summary
     [:p {:class "text-text-muted text-sm m-0 mb-5"}
      (str copy-count " " (if (= 1 copy-count) "copy" "copies")
           (when source-name (str " of " source-name)))]
     ;; Stepper rows
     [:div {:class "mb-4"}
      (for [target valid-targets]
        ^{:key target}
        [storm-split-stepper-row target (get allocation target 0) copy-count total-allocated])]
     ;; Total indicator
     [:p {:class (str "text-sm m-0 mb-4 "
                      (if valid? "text-health-good" "text-health-danger"))}
      (str total-allocated " / " copy-count " assigned")]
     ;; Buttons
     [:div {:class "flex justify-center gap-3"}
      [cancel-button {:label "Reset"
                      :on-cancel #(rf/dispatch [::storm-events/reset-storm-split])}]
      [confirm-button {:label "Confirm"
                       :valid? valid?
                       :on-confirm #(rf/dispatch [::selection-events/confirm-selection])
                       :extra-class "py-2.5 px-8"}]]]))


;; === Return-Land-Cost Modal (Daze Alternate Cost) ===

(defn- return-land-cost-modal
  "Modal for selecting a land to return to hand as alternate casting cost.
   Shows matching lands on the battlefield."
  [selection cards]
  [object-target-modal selection cards
   {:select-event ::selection-events/toggle-selection
    :confirm-event ::selection-events/confirm-selection
    :default-zone :battlefield
    :selected-label "1 land selected"
    :unselected-label "Select a land to return to hand"}])


;; === Discard-Specific-Cost Modal (Foil Alternate Cost) ===

(defn- discard-specific-cost-modal
  "Modal for selecting cards to discard as alternate casting cost.
   Shows hand cards; player must select the required number."
  [selection cards]
  (let [selected (or (:selection/selected selection) #{})
        required-count (:selection/select-count selection)
        current-count (count selected)
        valid-targets (set (:selection/valid-targets selection))
        valid? @(rf/subscribe [::subs/selection-valid?])]
    [:div {:class overlay-class}
     [:div {:class (container-class {})}
      [:h2 {:class "text-text m-0 mb-2 text-lg"}
       "Select cards to discard"]
      [:p {:class (str "m-0 mb-4 text-sm "
                       (if valid? "text-health-good" "text-health-danger"))}
       (str current-count " / " required-count " selected")]
      [:div {:class "flex flex-wrap gap-2.5 mb-5 min-h-[60px]"}
       (if (seq cards)
         (for [obj cards]
           (let [object-id (:object/id obj)
                 selectable? (contains? valid-targets object-id)]
             ^{:key object-id}
             [hand-reveal-card-view obj
              (contains? selected object-id)
              selectable?]))
         [:div {:class "text-perm-text-tapped"}
          "No cards available"])]
      [:div {:class "flex justify-end gap-3"}
       [cancel-button {:label "Clear"
                       :on-cancel #(rf/dispatch [::selection-events/cancel-selection])}]
       [confirm-button {:label "Discard"
                        :valid? valid?
                        :on-confirm #(rf/dispatch [::selection-events/confirm-selection])}]]]]))


;; === Selection Modal Registry ===
;;
;; Multimethod dispatches to the correct modal component per selection type.
;; Adding a new selection type requires only a new defmethod here.
;; Targeting types use a derived dispatch: player-targeting variants
;; dispatch as :targeting-player to share the player-target-modal.

(defn- modal-dispatch-key
  "Derive dispatch key for selection modal multimethod.
   Targeting types (cast-time-targeting, ability-targeting) that target a player
   dispatch as :targeting-player to share player-target-modal rendering."
  [selection]
  (let [sel-type (:selection/type selection)
        target-req (:selection/target-requirement selection)]
    (if (and (#{:cast-time-targeting :ability-targeting} sel-type)
             (= :player (:target/type target-req)))
      :targeting-player
      sel-type)))


(defmulti render-selection-modal
  "Render the appropriate modal component for a selection type.
   Dispatches on modal-dispatch-key."
  (fn [selection _cards] (modal-dispatch-key selection)))


(defmethod render-selection-modal :scry [selection _cards]
  [scry-modal selection])


(defmethod render-selection-modal :pile-choice [selection cards]
  [pile-choice-modal selection cards])


(defmethod render-selection-modal :targeting-player [selection _cards]
  [player-target-modal selection ::selection-events/confirm-selection])


(defmethod render-selection-modal :cast-time-targeting [selection cards]
  [object-target-modal selection cards
   {:select-event ::selection-events/toggle-selection
    :confirm-event ::selection-events/confirm-selection
    :default-zone :graveyard
    :selected-label "1 card selected"
    :unselected-label "Select a card"}])


(defmethod render-selection-modal :ability-targeting [selection cards]
  [object-target-modal selection cards
   {:select-event ::selection-events/toggle-selection
    :confirm-event ::selection-events/confirm-selection
    :default-zone :battlefield
    :selected-label "1 target selected"
    :unselected-label "Select a target"}])


(defmethod render-selection-modal :player-target [selection _cards]
  [player-target-modal selection ::selection-events/confirm-selection])


(defmethod render-selection-modal :graveyard-return [selection cards]
  [graveyard-selection-modal selection cards])


(defmethod render-selection-modal :exile-cards-cost [selection cards]
  [exile-cards-selection-modal selection cards])


(defmethod render-selection-modal :x-mana-cost [selection _cards]
  [x-mana-selection-modal selection])


(defmethod render-selection-modal :peek-and-select [selection cards]
  [peek-selection-modal selection cards])


(defmethod render-selection-modal :order-bottom [_selection _cards]
  [order-bottom-modal])


(defmethod render-selection-modal :peek-and-reorder [_selection _cards]
  [peek-and-reorder-modal])


(defmethod render-selection-modal :storm-split [selection _cards]
  [storm-split-modal selection])


(defmethod render-selection-modal :hand-reveal-discard [selection cards]
  [hand-reveal-discard-modal selection cards])


(defmethod render-selection-modal :unless-pay [_selection _cards]
  nil)


(defmethod render-selection-modal :return-land-cost [selection cards]
  [return-land-cost-modal selection cards])


(defmethod render-selection-modal :discard-specific-cost [selection cards]
  [discard-specific-cost-modal selection cards])


(defmethod render-selection-modal :mana-allocation [_selection _cards]
  nil)


(defmethod render-selection-modal :default [selection cards]
  [card-selection-modal selection cards])


;; === Selection Modal Router ===

(defn selection-modal
  "Modal overlay for player selection.
   Shows when :game/pending-selection exists.
   Dispatches to render-selection-modal multimethod."
  []
  (let [selection @(rf/subscribe [::subs/pending-selection])
        cards @(rf/subscribe [::subs/selection-cards])]
    (when selection
      (render-selection-modal selection cards))))
