(ns fizzle.views.modals
  (:require
    [clojure.string :as str]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.game :as events]
    [fizzle.events.selection :as selection-events]
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]))


;; === Shared Style Constants ===

(def overlay-style
  {:position "fixed"
   :top 0
   :left 0
   :right 0
   :bottom 0
   :background "rgba(0, 0, 0, 0.8)"
   :display "flex"
   :align-items "center"
   :justify-content "center"
   :z-index 1000})


(defn container-style
  [{:keys [max-width border-color]
    :or {max-width "600px" border-color "#4A9BD9"}}]
  {:background "#1a1a2a"
   :border (str "2px solid " border-color)
   :border-radius "12px"
   :padding "24px"
   :max-width max-width
   :width "90%"})


(def header-style
  {:color "#eee"
   :margin "0 0 16px 0"
   :font-size "18px"})


;; === Shared Components ===

(defn confirm-button
  [{:keys [label valid? on-confirm padding enabled-bg]
    :or {padding "8px 20px" enabled-bg "#2a6a2a"}}]
  [:button {:style {:padding padding
                    :border "none"
                    :border-radius "4px"
                    :cursor (if valid? "pointer" "not-allowed")
                    :background (if valid? enabled-bg "#333")
                    :color (if valid? "#fff" "#666")
                    :font-size "14px"
                    :font-weight "bold"}
            :disabled (not valid?)
            :on-click on-confirm}
   label])


(defn cancel-button
  [{:keys [label on-cancel]}]
  [:button {:style {:padding "8px 20px"
                    :border "1px solid #555"
                    :border-radius "4px"
                    :cursor "pointer"
                    :background "#333"
                    :color "#ccc"
                    :font-size "14px"}
            :on-click on-cancel}
   label])


(defn modal-wrapper
  [{:keys [title max-width border-color text-align]} & children]
  [:div {:style overlay-style}
   (into [:div {:style (cond-> (container-style {:max-width max-width
                                                 :border-color border-color})
                         text-align (assoc :text-align text-align))}
          [:h2 {:style header-style} title]]
         children)])


;; === Player Target Modal ===

(defn player-target-button
  "Button to select a player as target."
  [player-id label selected?]
  [:button {:style {:padding "16px 32px"
                    :margin "8px"
                    :border (if selected? "3px solid #4A9BD9" "2px solid #555")
                    :border-radius "8px"
                    :cursor "pointer"
                    :background (if selected? "#1a3a5a" "#1a1a2a")
                    :color "#eee"
                    :font-size "16px"
                    :font-weight "bold"
                    :min-width "120px"
                    :transition "all 0.1s ease"}
            :on-click #(rf/dispatch [::selection-events/select-player-target player-id])}
   label])


;; === Object Target Modal ===

(defn- object-target-card-view
  "A card in the object-target modal, showing selected state."
  [obj selected-target select-event]
  (let [card-name (get-in obj [:object/card :card/name])
        object-id (:object/id obj)
        is-selected? (= object-id selected-target)]
    ^{:key object-id}
    [:div {:style {:border (if is-selected?
                             "3px solid #4A9BD9"
                             "2px solid #555")
                   :border-radius "6px"
                   :padding "10px 14px"
                   :cursor "pointer"
                   :background (if is-selected? "#1a3a5a" "#1a1a2a")
                   :color "#eee"
                   :min-width "90px"
                   :text-align "center"
                   :user-select "none"
                   :transition "all 0.1s ease"}
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
  (let [selected-target (:selection/selected-target selection)
        target-req (:selection/target-requirement selection)
        zone-name (name (or (:target/zone target-req) default-zone))
        valid? (some? selected-target)]
    [:div {:style overlay-style}
     [:div {:style (container-style {})}
      ;; Header
      [:h2 {:style {:color "#eee"
                    :margin "0 0 8px 0"
                    :font-size "18px"}}
       (str "Choose target from " zone-name)]
      ;; Instructions
      [:p {:style {:color (if valid? "#5CB85C" "#F0AD4E")
                   :margin "0 0 16px 0"
                   :font-size "14px"}}
       (if valid? selected-label unselected-label)]
      ;; Card grid
      [:div {:style {:display "flex"
                     :flex-wrap "wrap"
                     :gap "10px"
                     :margin-bottom "20px"
                     :min-height "60px"}}
       (if (seq cards)
         (for [obj cards]
           [object-target-card-view obj selected-target select-event])
         [:div {:style {:color "#666"}}
          "No valid targets"])]
      ;; Confirm button
      [:div {:style {:display "flex"
                     :justify-content "flex-end"}}
       [confirm-button {:label "Confirm"
                        :valid? valid?
                        :on-confirm #(rf/dispatch [confirm-event selected-target])}]]]]))


(defn player-target-modal
  "Modal for selecting a player as target.
   Takes the selection map and a confirm-event keyword.
   Used for spell effects (::confirm-player-target-selection),
   ability targeting (::confirm-ability-target),
   and cast-time targeting (::confirm-cast-time-target)."
  [selection confirm-event]
  (let [selected-target (:selection/selected-target selection)
        valid? (some? selected-target)]
    [modal-wrapper {:title "Choose target player"
                    :max-width "400px"
                    :text-align "center"}
     ;; Player buttons
     [:div {:style {:display "flex"
                    :justify-content "center"
                    :margin-bottom "20px"}}
      [player-target-button :player-1 "You" (= selected-target :player-1)]
      [player-target-button :opponent "Opponent" (= selected-target :opponent)]]
     ;; Confirm button
     [:div {:style {:display "flex"
                    :justify-content "center"}}
      [confirm-button {:label "Confirm"
                       :valid? valid?
                       :on-confirm #(rf/dispatch [confirm-event selected-target])
                       :padding "10px 32px"}]]]))


;; === Card Selection Modals ===

(defn selection-card-view
  "A card in the selection modal, showing selected state.
   Optionally accepts a custom toggle-event to dispatch instead of the default."
  ([obj selected?]
   (selection-card-view obj selected? nil))
  ([obj selected? toggle-event]
   (let [card-name (get-in obj [:object/card :card/name])
         object-id (:object/id obj)]
     [:div {:style {:border (if selected?
                              "3px solid #4A9BD9"
                              "2px solid #555")
                    :border-radius "6px"
                    :padding "10px 14px"
                    :cursor "pointer"
                    :background (if selected? "#1a3a5a" "#1a1a2a")
                    :color "#eee"
                    :min-width "90px"
                    :text-align "center"
                    :user-select "none"
                    :transition "all 0.1s ease"}
            :on-click #(rf/dispatch [(or toggle-event ::selection-events/toggle-selection) object-id])}
      card-name])))


(defn card-selection-modal
  "Modal for selecting cards (discard or tutor)."
  [selection cards]
  (let [selected (:selection/selected selection)
        required-count (:selection/select-count selection)
        current-count (count selected)
        effect-type (:selection/effect-type selection)
        is-tutor? (= effect-type :tutor)
        is-multi-select? (and is-tutor? required-count (> required-count 1))
        allow-fail-to-find? (:selection/allow-fail-to-find? selection)
        ;; Validation logic:
        ;; - Multi-select tutor: exactly select-count OR 0 if fail-to-find allowed
        ;; - Single-select tutor: 0 (fail-to-find) or 1 card
        ;; - Discard: exactly required-count
        valid? (cond
                 is-multi-select?
                 (or (= current-count required-count)
                     (and allow-fail-to-find? (zero? current-count)))

                 is-tutor?
                 (<= current-count 1)

                 :else
                 (= current-count required-count))
        confirm-event (if is-tutor?
                        ::selection-events/confirm-tutor-selection
                        ::selection-events/confirm-selection)]
    [:div {:style overlay-style}
     [:div {:style (container-style {})}
      ;; Header
      [:h2 {:style {:color "#eee"
                    :margin "0 0 8px 0"
                    :font-size "18px"}}
       (case effect-type
         :discard "Select cards to discard"
         :tutor "Search your library"
         "Select cards")]
      ;; Counter / instructions
      [:p {:style {:color (if valid? "#5CB85C" "#F0AD4E")
                   :margin "0 0 16px 0"
                   :font-size "14px"}}
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
      [:div {:style {:display "flex"
                     :flex-wrap "wrap"
                     :gap "10px"
                     :margin-bottom "20px"
                     :min-height "60px"}}
       (if (seq cards)
         (for [obj cards]
           ^{:key (:object/id obj)}
           [selection-card-view obj (contains? selected (:object/id obj))])
         [:div {:style {:color "#666"}}
          (if is-tutor?
            "No matching cards in library"
            "No cards available")])]
      ;; Buttons
      [:div {:style {:display "flex"
                     :justify-content "flex-end"
                     :gap "12px"}}
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


(defn pile-choice-modal
  "Modal for pile choice selection (Intuition-style).
   Player chooses which card(s) go to hand, rest go to graveyard."
  [selection cards]
  (let [selected (:selection/selected selection)
        hand-count (:selection/hand-count selection)
        current-count (count selected)
        valid? (= current-count hand-count)]
    [:div {:style overlay-style}
     [:div {:style (container-style {})}
      ;; Header
      [:h2 {:style {:color "#eee"
                    :margin "0 0 8px 0"
                    :font-size "18px"}}
       "Choose card for hand"]
      ;; Instructions
      [:p {:style {:color (if valid? "#5CB85C" "#F0AD4E")
                   :margin "0 0 16px 0"
                   :font-size "14px"}}
       (str current-count " / " hand-count " selected for hand (rest go to graveyard)")]
      ;; Card grid
      [:div {:style {:display "flex"
                     :flex-wrap "wrap"
                     :gap "10px"
                     :margin-bottom "20px"
                     :min-height "60px"}}
       (for [obj cards]
         ^{:key (:object/id obj)}
         [selection-card-view obj (contains? selected (:object/id obj))])]
      ;; Buttons
      [:div {:style {:display "flex"
                     :justify-content "flex-end"
                     :gap "12px"}}
       ;; Random button
       [cancel-button {:label "Random"
                       :on-cancel #(rf/dispatch [::selection-events/select-random-pile-choice])}]
       ;; Confirm button
       [confirm-button {:label "Confirm"
                        :valid? valid?
                        :on-confirm #(rf/dispatch [::selection-events/confirm-pile-choice-selection])}]]]]))


(defn graveyard-selection-modal
  "Modal for returning cards from graveyard to hand (Ill-Gotten Gains style).
   Player can select 0 to max-count cards (not exact count like discard)."
  [selection cards]
  (let [selected (:selection/selected selection)
        max-count (:selection/select-count selection)
        current-count (count selected)
        ;; Valid if 0 to max-count cards selected
        valid? (<= current-count max-count)]
    [:div {:style overlay-style}
     [:div {:style (container-style {:border-color "#6a4a8a"})}
      ;; Header
      [:h2 {:style {:color "#eee"
                    :margin "0 0 8px 0"
                    :font-size "18px"}}
       "Return cards from graveyard to hand"]
      ;; Instructions
      [:p {:style {:color "#5CB85C"
                   :margin "0 0 16px 0"
                   :font-size "14px"}}
       (str "Select up to " max-count " cards (" current-count " selected)")]
      ;; Card grid
      [:div {:style {:display "flex"
                     :flex-wrap "wrap"
                     :gap "10px"
                     :margin-bottom "20px"
                     :min-height "60px"}}
       (if (seq cards)
         (for [obj cards]
           ^{:key (:object/id obj)}
           [selection-card-view obj (contains? selected (:object/id obj))])
         [:div {:style {:color "#666"}}
          "No cards in graveyard"])]
      ;; Buttons
      [:div {:style {:display "flex"
                     :justify-content "flex-end"
                     :gap "12px"}}
       ;; Clear button
       [cancel-button {:label "Clear"
                       :on-cancel #(rf/dispatch [::selection-events/cancel-selection])}]
       ;; Confirm button (always valid since 0 is allowed)
       [confirm-button {:label "Confirm"
                        :valid? valid?
                        :on-confirm #(rf/dispatch [::selection-events/confirm-graveyard-selection])
                        :enabled-bg "#6a4a8a"}]]]]))


(defn exile-cards-selection-modal
  "Modal for selecting cards to exile for an additional cost (e.g., Flash of Insight flashback).
   Player must select at least 1 card. The count of selected cards becomes X."
  [selection cards]
  (let [selected (:selection/selected selection)
        current-count (count selected)
        valid? (pos? current-count)]
    [:div {:style overlay-style}
     [:div {:style (container-style {:border-color "#6a4a8a"})}
      ;; Header
      [:h2 {:style {:color "#eee"
                    :margin "0 0 8px 0"
                    :font-size "18px"}}
       "Select cards to exile (Flashback cost)"]
      ;; Instructions
      [:p {:style {:color "#5CB85C"
                   :margin "0 0 16px 0"
                   :font-size "14px"}}
       (str "Select blue cards to exile. X = " current-count " (select at least 1)")]
      ;; Card grid
      [:div {:style {:display "flex"
                     :flex-wrap "wrap"
                     :gap "10px"
                     :margin-bottom "20px"
                     :min-height "60px"}}
       (if (seq cards)
         (for [obj cards]
           ^{:key (:object/id obj)}
           [selection-card-view obj (contains? selected (:object/id obj))
            ::selection-events/toggle-exile-card-selection])
         [:div {:style {:color "#666"}}
          "No eligible cards"])]
      ;; Buttons
      [:div {:style {:display "flex"
                     :justify-content "flex-end"
                     :gap "12px"}}
       [cancel-button {:label "Cancel"
                       :on-cancel #(rf/dispatch [::selection-events/cancel-exile-cards-selection])}]
       [confirm-button {:label (str "Exile " current-count " cards")
                        :valid? valid?
                        :on-confirm #(rf/dispatch [::selection-events/confirm-exile-cards-selection])
                        :enabled-bg "#6a4a8a"}]]]]))


(defn peek-selection-modal
  "Modal for peek-and-select effect (e.g., Flash of Insight).
   Player sees top X cards and selects up to N for hand."
  [selection cards]
  (let [selected (:selection/selected selection)
        select-count (:selection/select-count selection)
        current-count (count selected)
        valid? (<= current-count select-count)]
    [:div {:style overlay-style}
     [:div {:style (container-style {:border-color "#6a4a8a"})}
      ;; Header
      [:h2 {:style {:color "#eee"
                    :margin "0 0 8px 0"
                    :font-size "18px"}}
       "Look at the top cards of your library"]
      ;; Instructions
      [:p {:style {:color "#5CB85C"
                   :margin "0 0 16px 0"
                   :font-size "14px"}}
       (str "Select up to " select-count " card(s) for your hand (" current-count " selected). "
            "Remaining cards go to bottom of library.")]
      ;; Card grid
      [:div {:style {:display "flex"
                     :flex-wrap "wrap"
                     :gap "10px"
                     :margin-bottom "20px"
                     :min-height "60px"}}
       (if (seq cards)
         (for [obj cards]
           ^{:key (:object/id obj)}
           [selection-card-view obj (contains? selected (:object/id obj))
            ::selection-events/toggle-peek-card-selection])
         [:div {:style {:color "#666"}}
          "No cards to peek"])]
      ;; Buttons
      [:div {:style {:display "flex"
                     :justify-content "flex-end"
                     :gap "12px"}}
       [cancel-button {:label "Clear"
                       :on-cancel #(rf/dispatch [::selection-events/cancel-selection])}]
       [confirm-button {:label "Confirm"
                        :valid? valid?
                        :on-confirm #(rf/dispatch [::selection-events/confirm-peek-selection])
                        :enabled-bg "#6a4a8a"}]]]]))


;; === Scry Modal ===

(defn- scry-card-view
  "A card in the scry selection modal, showing pile assignment."
  [obj pile]
  (let [card (:object/card obj)
        card-name (or (:card/name card) "Unknown")]
    [:div {:style {:width "100px"
                   :padding "8px"
                   :border-radius "6px"
                   :background (case pile
                                 :top "#2a4a2a"
                                 :bottom "#4a2a2a"
                                 "#1a2a3a")
                   :border "1px solid #4A9BD9"
                   :text-align "center"
                   :font-size "11px"
                   :color "#eee"
                   :cursor "pointer"}}
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
    [:div {:style overlay-style}
     [:div {:style (container-style {})}
      ;; Header
      [:h2 {:style {:color "#eee" :margin "0 0 16px 0"}}
       (str "Scry " (count all-cards))]

      ;; Top Pile section
      [:div {:style {:margin-bottom "16px"}}
       [:h3 {:style {:color "#5CB85C" :font-size "14px" :margin "0 0 8px 0"}}
        "Top of Library (first)"]
       [:div {:style {:display "flex" :flex-wrap "wrap" :gap "8px" :min-height "40px"}}
        (for [obj top-cards]
          ^{:key (:object/id obj)}
          [:div {:on-click #(rf/dispatch [::selection-events/scry-unassign (:object/id obj)])}
           [scry-card-view obj :top]])]]

      ;; Unassigned cards section
      [:div {:style {:margin-bottom "16px"}}
       [:h3 {:style {:color "#aaa" :font-size "14px" :margin "0 0 8px 0"}}
        "Revealed Cards"]
       [:div {:style {:display "flex" :flex-wrap "wrap" :gap "8px" :min-height "40px"}}
        (for [obj unassigned-cards]
          ^{:key (:object/id obj)}
          [:div {:style {:display "flex" :gap "4px" :align-items "center"}}
           [scry-card-view obj nil]
           [:div {:style {:display "flex" :flex-direction "column" :gap "2px"}}
            [:button {:style {:padding "4px 8px" :background "#2a4a2a" :border "1px solid #5CB85C"
                              :border-radius "4px" :color "#eee" :cursor "pointer" :font-size "10px"}
                      :on-click #(rf/dispatch [::selection-events/scry-assign-top (:object/id obj)])}
             "Top"]
            [:button {:style {:padding "4px 8px" :background "#4a2a2a" :border "1px solid #D9534F"
                              :border-radius "4px" :color "#eee" :cursor "pointer" :font-size "10px"}
                      :on-click #(rf/dispatch [::selection-events/scry-assign-bottom (:object/id obj)])}
             "Bottom"]]])]]

      ;; Bottom Pile section
      [:div {:style {:margin-bottom "20px"}}
       [:h3 {:style {:color "#D9534F" :font-size "14px" :margin "0 0 8px 0"}}
        "Bottom of Library (last)"]
       [:div {:style {:display "flex" :flex-wrap "wrap" :gap "8px" :min-height "40px"}}
        (for [obj bottom-cards]
          ^{:key (:object/id obj)}
          [:div {:on-click #(rf/dispatch [::selection-events/scry-unassign (:object/id obj)])}
           [scry-card-view obj :bottom]])]]

      ;; Confirm button
      [:button {:style {:width "100%" :padding "12px" :background "#4A9BD9"
                        :border "none" :border-radius "8px" :color "#fff"
                        :font-size "16px" :cursor "pointer"}
                :on-click #(rf/dispatch [::selection-events/confirm-scry-selection])}
       "Confirm"]]]))


;; === X Mana Selection Modal ===

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
    [:div {:style overlay-style}
     [:div {:style (container-style {:max-width "400px" :border-color "#6a4a8a"})}
      ;; Header
      [:h2 {:style {:color "#eee"
                    :margin "0 0 8px 0"
                    :font-size "18px"}}
       "Choose X value"]
      ;; Instructions
      [:p {:style {:color "#5CB85C"
                   :margin "0 0 16px 0"
                   :font-size "14px"}}
       (str "Total cost: {" total-colorless "}{U} (X = " selected-x ", max " max-x ")")]
      ;; X selector
      [:div {:style {:display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :gap "20px"
                     :margin-bottom "20px"}}
       [:button {:style {:width "40px"
                         :height "40px"
                         :border "1px solid #555"
                         :border-radius "4px"
                         :cursor (if (pos? selected-x) "pointer" "not-allowed")
                         :background (if (pos? selected-x) "#444" "#222")
                         :color "#fff"
                         :font-size "20px"}
                 :disabled (not (pos? selected-x))
                 :on-click #(rf/dispatch [::selection-events/decrement-x-value])}
        "-"]
       [:div {:style {:font-size "32px"
                      :color "#fff"
                      :font-weight "bold"
                      :min-width "60px"
                      :text-align "center"}}
        (str "X = " selected-x)]
       [:button {:style {:width "40px"
                         :height "40px"
                         :border "1px solid #555"
                         :border-radius "4px"
                         :cursor (if (< selected-x max-x) "pointer" "not-allowed")
                         :background (if (< selected-x max-x) "#444" "#222")
                         :color "#fff"
                         :font-size "20px"}
                 :disabled (not (< selected-x max-x))
                 :on-click #(rf/dispatch [::selection-events/increment-x-value])}
        "+"]]
      ;; Buttons
      [:div {:style {:display "flex"
                     :justify-content "flex-end"
                     :gap "12px"}}
       [cancel-button {:label "Cancel"
                       :on-cancel #(rf/dispatch [::selection-events/cancel-x-mana-selection])}]
       [confirm-button {:label "Cast"
                        :valid? true
                        :on-confirm #(rf/dispatch [::selection-events/confirm-x-mana-selection])
                        :enabled-bg "#6a4a8a"}]]]]))


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
    [:button {:style {:width "100%"
                      :padding "12px 16px"
                      :margin-bottom "8px"
                      :border "2px solid #4A9BD9"
                      :border-radius "8px"
                      :cursor "pointer"
                      :background "#1a2a3a"
                      :color "#eee"
                      :text-align "left"
                      :transition "all 0.1s ease"}
              :on-click #(rf/dispatch [::events/select-casting-mode mode])
              :on-mouse-over #(set! (.. % -target -style -background) "#2a3a5a")
              :on-mouse-out #(set! (.. % -target -style -background) "#1a2a3a")}
     [:div {:style {:font-weight "bold"
                    :font-size "14px"
                    :margin-bottom "4px"}}
      (case mode-id
        :primary "Normal Cast"
        :flashback "Flashback"
        (name mode-id))]
     [:div {:style {:font-size "13px"
                    :color "#aaa"}}
      formatted-mana
      (when formatted-additional
        [:span {:style {:margin-left "8px"
                        :color "#c8a"}}
         (str "+ " formatted-additional)])]]))


(defn mode-selector-modal
  "Modal for selecting casting mode when multiple modes available."
  []
  (let [pending @(rf/subscribe [::subs/pending-mode-selection])]
    (when pending
      (let [modes (:modes pending)]
        [:div {:style (merge overlay-style {})
               :on-click #(rf/dispatch [::events/cancel-mode-selection])}
         [:div {:style (container-style {:max-width "400px"})
                :on-click #(.stopPropagation %)}  ; Prevent closing when clicking inside
          ;; Header
          [:h2 {:style {:color "#eee"
                        :margin "0 0 16px 0"
                        :font-size "18px"
                        :text-align "center"}}
           "Choose casting mode"]
          ;; Mode buttons
          [:div {:style {:display "flex"
                         :flex-direction "column"}}
           (for [mode modes]
             ^{:key (:mode/id mode)}
             [mode-button mode])]
          ;; Cancel button
          [:button {:style {:width "100%"
                            :padding "8px 16px"
                            :margin-top "8px"
                            :border "1px solid #555"
                            :border-radius "4px"
                            :cursor "pointer"
                            :background "#333"
                            :color "#999"
                            :font-size "13px"}
                    :on-click #(rf/dispatch [::events/cancel-mode-selection])}
           "Cancel"]]]))))


;; === Selection Modal Router ===

(defn selection-modal
  "Modal overlay for player selection.
   Shows when :game/pending-selection exists.
   Handles discard, tutor, scry, graveyard-return, player-target effects, ability targeting, and cast-time targeting."
  []
  (let [selection @(rf/subscribe [::subs/pending-selection])
        cards @(rf/subscribe [::subs/selection-cards])]
    (when selection
      (let [selection-type (:selection/type selection)
            effect-type (:selection/effect-type selection)
            ;; For ability/cast-time targeting, check if target is a player
            target-req (:selection/target-requirement selection)
            targets-player? (= :player (:target/type target-req))]
        (cond
          ;; Scry selection (has :selection/type :scry)
          (= selection-type :scry)
          [scry-modal selection]

          ;; Pile choice selection (Intuition-style: choose which cards go to hand)
          (= selection-type :pile-choice)
          [pile-choice-modal selection cards]

          ;; Cast-time targeting a player (e.g., Deep Analysis when casting)
          (and (= selection-type :cast-time-targeting) targets-player?)
          [player-target-modal selection ::selection-events/confirm-cast-time-target]

          ;; Cast-time targeting an object (e.g., Recoup targeting graveyard sorcery)
          (and (= selection-type :cast-time-targeting) (not targets-player?))
          [object-target-modal selection cards
           {:select-event ::selection-events/select-cast-time-object-target
            :confirm-event ::selection-events/confirm-cast-time-target
            :default-zone :graveyard
            :selected-label "1 card selected"
            :unselected-label "Select a card"}]

          ;; Ability targeting a player (e.g., Cephalid Coliseum threshold)
          (and (= selection-type :ability-targeting) targets-player?)
          [player-target-modal selection ::ability-events/confirm-ability-target]

          ;; Ability targeting an object (e.g., Seal of Cleansing targeting artifact/enchantment)
          (and (= selection-type :ability-targeting) (not targets-player?))
          [object-target-modal selection cards
           {:select-event ::ability-events/select-ability-object-target
            :confirm-event ::ability-events/confirm-ability-target
            :default-zone :battlefield
            :selected-label "1 target selected"
            :unselected-label "Select a target"}]

          ;; Player target selection (for spell effects during resolution)
          (= effect-type :player-target)
          [player-target-modal selection ::selection-events/confirm-player-target-selection]

          ;; Graveyard return selection (Ill-Gotten Gains style)
          (= selection-type :graveyard-return)
          [graveyard-selection-modal selection cards]

          ;; Exile cards cost selection (Flash of Insight flashback)
          (= effect-type :exile-cards-cost)
          [exile-cards-selection-modal selection cards]

          ;; X mana cost selection (spells with X in cost)
          (= effect-type :x-mana-cost)
          [x-mana-selection-modal selection]

          ;; Peek-and-select selection (Flash of Insight effect)
          (= effect-type :peek-and-select)
          [peek-selection-modal selection cards]

          ;; Card selection (discard, tutor)
          :else
          [card-selection-modal selection cards])))))
