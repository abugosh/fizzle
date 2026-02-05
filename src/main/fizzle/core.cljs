(ns fizzle.core
  (:require
    [clojure.string :as str]
    [fizzle.events.game :as events]
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]
    [reagent.dom :as rdom]))


(def ^:private mana-colors
  {:white "W" :blue "U" :black "B" :red "R" :green "G" :colorless "C"})


(defn- card-view
  [obj selected?]
  (let [card-name (get-in obj [:object/card :card/name])
        object-id (:object/id obj)]
    [:div {:style {:border (if selected?
                             "2px solid #FFD700"
                             "2px solid #555")
                   :border-radius "6px"
                   :padding "10px 14px"
                   :margin "0 6px"
                   :cursor "pointer"
                   :background (if selected? "#2a2a3a" "#1a1a2a")
                   :color "#eee"
                   :min-width "100px"
                   :text-align "center"
                   :user-select "none"}
           :on-click #(rf/dispatch [::events/select-card object-id])}
     card-name]))


(defn- hand-view
  []
  (let [hand @(rf/subscribe [::subs/hand])
        selected @(rf/subscribe [::subs/selected-card])]
    [:div {:style {:margin-bottom "16px"}}
     [:div {:style {:color "#999" :margin-bottom "6px" :font-size "12px"}} "HAND"]
     [:div {:style {:display "flex" :flex-wrap "wrap"}}
      (for [obj hand]
        ^{:key (:object/id obj)}
        [card-view obj (= (:object/id obj) selected)])]]))


(defn- mana-pool-view
  []
  (let [mana @(rf/subscribe [::subs/mana-pool])]
    [:div {:style {:margin-bottom "16px"}}
     [:div {:style {:color "#999" :margin-bottom "6px" :font-size "12px"}} "MANA POOL"]
     [:div {:style {:display "flex" :gap "10px"}}
      (for [[color amount] mana
            :when (pos? amount)]
        ^{:key color}
        [:span {:style {:color (case color
                                 :white "#FFF"
                                 :blue "#4A9BD9"
                                 :black "#A080C0"
                                 :red "#D9534F"
                                 :green "#5CB85C"
                                 :colorless "#AAA"
                                 "#AAA")
                        :font-weight "bold"
                        :font-size "18px"}}
         (str (get mana-colors color (name color)) ": " amount)])]]))


(defn- storm-count-view
  []
  (let [storm @(rf/subscribe [::subs/storm-count])]
    [:div {:style {:margin-bottom "16px"}}
     [:div {:style {:color "#999" :margin-bottom "6px" :font-size "12px"}} "STORM COUNT"]
     [:div {:style {:font-size "24px" :font-weight "bold" :color "#FFD700"}} storm]]))


(def ^:private mana-color-display
  {:white {:symbol "W" :color "#FFF" :bg "#EEE"}
   :blue {:symbol "U" :color "#4A9BD9" :bg "#2a4a6a"}
   :black {:symbol "B" :color "#A080C0" :bg "#3a2a4a"}
   :red {:symbol "R" :color "#D9534F" :bg "#5a2a2a"}
   :green {:symbol "G" :color "#5CB85C" :bg "#2a4a2a"}})


(defn- mana-button
  [object-id mana-color tapped?]
  (let [{:keys [symbol color bg]} (get mana-color-display mana-color)]
    [:button {:style {:padding "2px 6px"
                      :margin "0 2px"
                      :border "1px solid #555"
                      :border-radius "3px"
                      :cursor (if tapped? "default" "pointer")
                      :background (if tapped? "#333" bg)
                      :color (if tapped? "#555" color)
                      :font-size "11px"
                      :font-weight "bold"}
              :disabled tapped?
              :on-click #(rf/dispatch [::events/activate-mana-ability object-id mana-color])}
     symbol]))


(defn- get-producible-colors
  "Get the mana colors a permanent can produce from its abilities."
  [obj]
  (let [abilities (get-in obj [:object/card :card/abilities])
        mana-abilities (filter #(= :mana (:ability/type %)) abilities)]
    (if (seq mana-abilities)
      (let [;; Check :ability/produces (e.g., Lotus Petal, basic lands)
            produces-maps (keep :ability/produces mana-abilities)
            ;; Check :ability/effects for :add-mana (e.g., LED)
            effect-mana-maps (->> mana-abilities
                                  (mapcat :ability/effects)
                                  (filter #(= :add-mana (:effect/type %)))
                                  (keep :effect/mana))
            all-mana-maps (concat produces-maps effect-mana-maps)
            ;; Check for {:any N} which means any color
            has-any? (some :any all-mana-maps)]
        (if has-any?
          [:white :blue :black :red :green]
          ;; Get specific colors from mana maps
          (distinct (mapcat keys all-mana-maps))))
      ;; No mana abilities - no buttons
      [])))


(defn- get-activated-abilities
  "Get non-mana activated abilities with their indices."
  [obj]
  (let [abilities (get-in obj [:object/card :card/abilities])]
    (->> abilities
         (map-indexed vector)
         (filter (fn [[_idx ability]] (= :activated (:ability/type ability))))
         (into []))))


(defn- ability-button
  "Button to activate a non-mana ability."
  [object-id ability-index label tapped?]
  [:button {:style {:padding "2px 8px"
                    :margin "2px"
                    :border "1px solid #555"
                    :border-radius "3px"
                    :cursor (if tapped? "default" "pointer")
                    :background (if tapped? "#333" "#4a3a2a")
                    :color (if tapped? "#555" "#d4a")
                    :font-size "11px"
                    :font-weight "bold"}
            :disabled tapped?
            :on-click #(rf/dispatch [::events/activate-ability object-id ability-index])}
   label])


(defn- permanent-view
  [obj]
  (let [card-name (get-in obj [:object/card :card/name])
        object-id (:object/id obj)
        tapped? (:object/tapped obj)
        counters (:object/counters obj)
        producible-colors (get-producible-colors obj)
        activated-abilities (get-activated-abilities obj)]
    [:div {:style {:border (if tapped? "2px solid #444" "2px solid #6a6")
                   :border-radius "6px"
                   :padding "8px"
                   :margin "0 6px 6px 0"
                   :background (if tapped? "#1a1a1a" "#1a2a1a")
                   :color (if tapped? "#666" "#ccc")
                   :min-width "100px"
                   :text-align "center"
                   :transform (when tapped? "rotate(6deg)")}}
     [:div {:style {:font-size "13px" :margin-bottom "4px"}}
      card-name
      (when tapped? " (tapped)")]
     ;; Show counters if any
     (when (and counters (seq counters))
       [:div {:style {:font-size "11px" :color "#888" :margin-bottom "4px"}}
        (for [[counter-type count] counters]
          ^{:key counter-type}
          [:span {:style {:margin-right "6px"}}
           (str (name counter-type) ": " count)])])
     ;; Mana buttons - only for colors this permanent can produce
     (when (seq producible-colors)
       [:div {:style {:display "flex" :justify-content "center" :flex-wrap "wrap"}}
        (for [color producible-colors]
          ^{:key color}
          [mana-button object-id color tapped?])])
     ;; Activated ability buttons (non-mana)
     (when (seq activated-abilities)
       [:div {:style {:display "flex" :justify-content "center" :flex-wrap "wrap" :margin-top "4px"}}
        (for [[idx ability] activated-abilities]
          (let [label (or (:ability/name ability)
                          (when-let [desc (:ability/description ability)]
                            ;; Truncate long descriptions to first 15 chars
                            (if (> (count desc) 15)
                              (str (subs desc 0 12) "...")
                              desc))
                          "Activate")]
            ^{:key idx}
            [ability-button object-id idx label tapped?]))])]))


(defn- battlefield-view
  []
  (let [battlefield @(rf/subscribe [::subs/battlefield])]
    [:div {:style {:margin-bottom "16px"}}
     [:div {:style {:color "#999" :margin-bottom "6px" :font-size "12px"}} "BATTLEFIELD"]
     (if (seq battlefield)
       [:div {:style {:display "flex" :flex-wrap "wrap"}}
        (for [obj battlefield]
          ^{:key (:object/id obj)}
          [permanent-view obj])]
       [:div {:style {:color "#555" :font-size "13px"}} "No permanents"])]))


(defn- life-view
  []
  (let [player-life @(rf/subscribe [::subs/player-life])
        opponent-life @(rf/subscribe [::subs/opponent-life])]
    [:div {:style {:margin-bottom "16px"}}
     [:div {:style {:color "#999" :margin-bottom "6px" :font-size "12px"}} "LIFE TOTALS"]
     [:div {:style {:display "flex" :gap "30px"}}
      [:div
       [:span {:style {:color "#888" :font-size "13px"}} "You: "]
       [:span {:style {:font-size "20px"
                       :font-weight "bold"
                       :color (cond
                                (<= player-life 0) "#D9534F"
                                (<= player-life 5) "#F0AD4E"
                                :else "#5CB85C")}}
        player-life]]
      [:div
       [:span {:style {:color "#888" :font-size "13px"}} "Opponent: "]
       [:span {:style {:font-size "20px"
                       :font-weight "bold"
                       :color (cond
                                (<= opponent-life 0) "#5CB85C"
                                (<= opponent-life 5) "#F0AD4E"
                                :else "#D9534F")}}
        opponent-life]]]]))


(defn- graveyard-card-view
  "A card in the graveyard. Flashback-castable cards are highlighted and selectable."
  [obj flashback-castable? selected?]
  (let [card-name (get-in obj [:object/card :card/name])
        object-id (:object/id obj)]
    [:div {:style {:padding "4px 8px"
                   :background (cond
                                 selected? "#3a2a5a"
                                 flashback-castable? "#2a2a3a"
                                 :else "#2a1a2a")
                   :border (cond
                             selected? "2px solid #FFD700"
                             flashback-castable? "1px solid #6a4a8a"
                             :else "1px solid #444")
                   :border-radius "3px"
                   :font-size "12px"
                   :color (if flashback-castable? "#c8a" "#aaa")
                   :cursor (if flashback-castable? "pointer" "default")
                   :user-select "none"}
           :on-click (when flashback-castable?
                       #(rf/dispatch [::events/select-card object-id]))}
     card-name
     (when flashback-castable?
       [:span {:style {:margin-left "6px"
                       :padding "1px 4px"
                       :background "#6a2a6a"
                       :border-radius "2px"
                       :font-size "9px"
                       :color "#eee"}}
        "FB"])]))


(defn- graveyard-view
  []
  (let [graveyard @(rf/subscribe [::subs/graveyard])
        flashback-ids @(rf/subscribe [::subs/flashback-castable-ids])
        selected @(rf/subscribe [::subs/selected-card])
        card-count (count graveyard)
        threshold? (>= card-count 7)]
    [:div {:style {:margin-bottom "16px"}}
     [:div {:style {:color "#999" :margin-bottom "6px" :font-size "12px"}}
      (str "GRAVEYARD (" card-count ")")
      (when threshold?
        [:span {:style {:margin-left "8px"
                        :padding "2px 6px"
                        :background "#6a2a6a"
                        :border-radius "3px"
                        :font-size "10px"
                        :color "#eee"}}
         "THRESHOLD"])]
     (if (seq graveyard)
       [:div {:style {:display "flex"
                      :flex-direction "column"
                      :gap "2px"
                      :max-height "200px"
                      :overflow-y "auto"}}
        (for [obj graveyard]
          ^{:key (:object/id obj)}
          [graveyard-card-view obj
           (contains? flashback-ids (:object/id obj))
           (= (:object/id obj) selected)])]
       [:div {:style {:color "#555" :font-size "13px"}} "Empty"])]))


(defn- stack-item-view
  [item]
  (let [label (or (get-in item [:object/card :card/name])
                  "Unknown")]
    [:div {:style {:border "1px solid #444"
                   :border-radius "4px"
                   :padding "6px 10px"
                   :margin-bottom "4px"
                   :background "#1a1a2a"
                   :color "#ccc"
                   :font-size "13px"}}
     (if (:trigger/type item)
       (if-let [desc (:trigger/description item)]
         (str "Trigger: " (:trigger/card-name item) " " desc)
         (str "Trigger: " (name (:trigger/type item))))
       label)]))


(defn- stack-view
  []
  (let [stack @(rf/subscribe [::subs/stack])]
    [:div {:style {:margin-bottom "16px"}}
     [:div {:style {:color "#999" :margin-bottom "6px" :font-size "12px"}} "STACK"]
     (if (seq stack)
       [:div
        (for [[idx item] (map-indexed vector stack)]
          ^{:key idx}
          [stack-item-view item])]
       [:div {:style {:color "#555" :font-size "13px"}} "Empty"])]))


(defn- phase-indicator
  "Display a single phase name, highlighted if it's the current phase."
  [phase current-phase]
  [:span {:style {:padding "4px 8px"
                  :border-radius "3px"
                  :font-size "11px"
                  :background (if (= phase current-phase) "#4A9BD9" "transparent")
                  :color (if (= phase current-phase) "#fff" "#666")}}
   (name phase)])


(defn- turn-bar
  "Display turn counter, phase strip, and Next Phase/New Turn button."
  []
  (let [current-phase (or @(rf/subscribe [::subs/current-phase]) :main1)
        current-turn (or @(rf/subscribe [::subs/current-turn]) 1)
        at-cleanup? (= current-phase :cleanup)]
    [:div {:style {:display "flex"
                   :align-items "center"
                   :gap "16px"
                   :padding "10px 16px"
                   :margin-bottom "16px"
                   :background "#1a1a2a"
                   :border-bottom "1px solid #333"}}
     [:span {:style {:font-weight "bold" :color "#eee" :font-size "14px"}}
      (str "Turn " current-turn)]
     [:div {:style {:display "flex" :gap "4px"}}
      (for [phase events/phases]
        ^{:key phase}
        [phase-indicator phase current-phase])]
     [:button {:style {:padding "6px 14px"
                       :border "1px solid #555"
                       :border-radius "4px"
                       :cursor "pointer"
                       :background "#2a6a2a"
                       :color "#fff"
                       :font-size "13px"
                       :font-weight "bold"}
               :on-click #(rf/dispatch [(if at-cleanup?
                                          ::events/start-turn
                                          ::events/advance-phase)])}
      (if at-cleanup? "New Turn" "Next Phase")]]))


(defn- controls-view
  []
  (let [can-cast? @(rf/subscribe [::subs/can-cast?])
        can-play-land? @(rf/subscribe [::subs/can-play-land?])
        selected @(rf/subscribe [::subs/selected-card])
        stack @(rf/subscribe [::subs/stack])
        btn-style (fn [enabled?]
                    {:padding "8px 20px"
                     :margin-right "10px"
                     :border "1px solid #555"
                     :border-radius "4px"
                     :cursor (if enabled? "pointer" "default")
                     :background (if enabled? "#2a6a2a" "#222")
                     :color (if enabled? "#fff" "#555")
                     :font-size "14px"
                     :font-weight "bold"})]
    [:div {:style {:margin-bottom "16px"}}
     [:button {:style (btn-style can-cast?)
               :disabled (not can-cast?)
               :on-click #(rf/dispatch [::events/cast-spell])}
      "Cast"]
     [:button {:style (btn-style can-play-land?)
               :disabled (not can-play-land?)
               :on-click #(rf/dispatch [::events/play-land selected])}
      "Play Land"]
     [:button {:style (btn-style (seq stack))
               :disabled (empty? stack)
               :on-click #(rf/dispatch [::events/resolve-top])}
      "Resolve"]]))


;; === Selection Modal ===
;; Modal overlay for selecting cards (e.g., Careful Study discard)

(defn- selection-card-view
  "A card in the selection modal, showing selected state."
  [obj selected?]
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
           :on-click #(rf/dispatch [::events/toggle-selection object-id])}
     card-name]))


(defn- player-target-button
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
            :on-click #(rf/dispatch [::events/select-player-target player-id])}
   label])


(defn- player-target-modal
  "Modal for selecting a player as target (e.g., Deep Analysis)."
  [selection]
  (let [selected-target (:selection/selected-target selection)
        valid? (some? selected-target)]
    [:div {:style {:position "fixed"
                   :top 0
                   :left 0
                   :right 0
                   :bottom 0
                   :background "rgba(0, 0, 0, 0.8)"
                   :display "flex"
                   :align-items "center"
                   :justify-content "center"
                   :z-index 1000}}
     [:div {:style {:background "#1a1a2a"
                    :border "2px solid #4A9BD9"
                    :border-radius "12px"
                    :padding "24px"
                    :max-width "400px"
                    :width "90%"
                    :text-align "center"}}
      ;; Header
      [:h2 {:style {:color "#eee"
                    :margin "0 0 16px 0"
                    :font-size "18px"}}
       "Choose target player"]
      ;; Player buttons
      [:div {:style {:display "flex"
                     :justify-content "center"
                     :margin-bottom "20px"}}
       [player-target-button :player-1 "You" (= selected-target :player-1)]
       [player-target-button :opponent "Opponent" (= selected-target :opponent)]]
      ;; Confirm button
      [:div {:style {:display "flex"
                     :justify-content "center"}}
       [:button {:style {:padding "10px 32px"
                         :border "none"
                         :border-radius "4px"
                         :cursor (if valid? "pointer" "not-allowed")
                         :background (if valid? "#2a6a2a" "#333")
                         :color (if valid? "#fff" "#666")
                         :font-size "14px"
                         :font-weight "bold"}
                 :disabled (not valid?)
                 :on-click #(rf/dispatch [::events/confirm-player-target-selection])}
        "Confirm"]]]]))


(defn- ability-player-target-modal
  "Modal for selecting a player as target for an activated ability (e.g., Cephalid Coliseum).
   Uses ::confirm-ability-target event instead of ::confirm-player-target-selection."
  [selection]
  (let [selected-target (:selection/selected-target selection)
        valid? (some? selected-target)]
    [:div {:style {:position "fixed"
                   :top 0
                   :left 0
                   :right 0
                   :bottom 0
                   :background "rgba(0, 0, 0, 0.8)"
                   :display "flex"
                   :align-items "center"
                   :justify-content "center"
                   :z-index 1000}}
     [:div {:style {:background "#1a1a2a"
                    :border "2px solid #4A9BD9"
                    :border-radius "12px"
                    :padding "24px"
                    :max-width "400px"
                    :width "90%"
                    :text-align "center"}}
      ;; Header
      [:h2 {:style {:color "#eee"
                    :margin "0 0 16px 0"
                    :font-size "18px"}}
       "Choose target player"]
      ;; Player buttons
      [:div {:style {:display "flex"
                     :justify-content "center"
                     :margin-bottom "20px"}}
       [player-target-button :player-1 "You" (= selected-target :player-1)]
       [player-target-button :opponent "Opponent" (= selected-target :opponent)]]
      ;; Confirm button
      [:div {:style {:display "flex"
                     :justify-content "center"}}
       [:button {:style {:padding "10px 32px"
                         :border "none"
                         :border-radius "4px"
                         :cursor (if valid? "pointer" "not-allowed")
                         :background (if valid? "#2a6a2a" "#333")
                         :color (if valid? "#fff" "#666")
                         :font-size "14px"
                         :font-weight "bold"}
                 :disabled (not valid?)
                 :on-click #(rf/dispatch [::events/confirm-ability-target selected-target])}
        "Confirm"]]]]))


(defn- ability-object-target-modal
  "Modal for selecting an object as target for an activated ability (e.g., Seal of Cleansing).
   Uses ::confirm-ability-target event."
  [selection cards]
  (let [selected-target (:selection/selected-target selection)
        target-req (:selection/target-requirement selection)
        zone-name (name (or (:target/zone target-req) :battlefield))
        valid? (some? selected-target)]
    [:div {:style {:position "fixed"
                   :top 0
                   :left 0
                   :right 0
                   :bottom 0
                   :background "rgba(0, 0, 0, 0.8)"
                   :display "flex"
                   :align-items "center"
                   :justify-content "center"
                   :z-index 1000}}
     [:div {:style {:background "#1a1a2a"
                    :border "2px solid #4A9BD9"
                    :border-radius "12px"
                    :padding "24px"
                    :max-width "600px"
                    :width "90%"}}
      ;; Header
      [:h2 {:style {:color "#eee"
                    :margin "0 0 8px 0"
                    :font-size "18px"}}
       (str "Choose target from " zone-name)]
      ;; Instructions
      [:p {:style {:color (if valid? "#5CB85C" "#F0AD4E")
                   :margin "0 0 16px 0"
                   :font-size "14px"}}
       (if valid?
         "1 target selected"
         "Select a target")]
      ;; Card grid
      [:div {:style {:display "flex"
                     :flex-wrap "wrap"
                     :gap "10px"
                     :margin-bottom "20px"
                     :min-height "60px"}}
       (if (seq cards)
         (for [obj cards]
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
                    :on-click #(rf/dispatch [::events/select-ability-object-target object-id])}
              card-name]))
         [:div {:style {:color "#666"}}
          "No valid targets"])]
      ;; Confirm button
      [:div {:style {:display "flex"
                     :justify-content "flex-end"}}
       [:button {:style {:padding "8px 20px"
                         :border "none"
                         :border-radius "4px"
                         :cursor (if valid? "pointer" "not-allowed")
                         :background (if valid? "#2a6a2a" "#333")
                         :color (if valid? "#fff" "#666")
                         :font-size "14px"
                         :font-weight "bold"}
                 :disabled (not valid?)
                 :on-click #(rf/dispatch [::events/confirm-ability-target selected-target])}
        "Confirm"]]]]))


(defn- cast-time-player-target-modal
  "Modal for selecting a player as target when casting a spell (e.g., Deep Analysis).
   Uses ::confirm-cast-time-target event."
  [selection]
  (let [selected-target (:selection/selected-target selection)
        valid? (some? selected-target)]
    [:div {:style {:position "fixed"
                   :top 0
                   :left 0
                   :right 0
                   :bottom 0
                   :background "rgba(0, 0, 0, 0.8)"
                   :display "flex"
                   :align-items "center"
                   :justify-content "center"
                   :z-index 1000}}
     [:div {:style {:background "#1a1a2a"
                    :border "2px solid #4A9BD9"
                    :border-radius "12px"
                    :padding "24px"
                    :max-width "400px"
                    :width "90%"
                    :text-align "center"}}
      ;; Header
      [:h2 {:style {:color "#eee"
                    :margin "0 0 16px 0"
                    :font-size "18px"}}
       "Choose target player"]
      ;; Player buttons
      [:div {:style {:display "flex"
                     :justify-content "center"
                     :margin-bottom "20px"}}
       [player-target-button :player-1 "You" (= selected-target :player-1)]
       [player-target-button :opponent "Opponent" (= selected-target :opponent)]]
      ;; Confirm button
      [:div {:style {:display "flex"
                     :justify-content "center"}}
       [:button {:style {:padding "10px 32px"
                         :border "none"
                         :border-radius "4px"
                         :cursor (if valid? "pointer" "not-allowed")
                         :background (if valid? "#2a6a2a" "#333")
                         :color (if valid? "#fff" "#666")
                         :font-size "14px"
                         :font-weight "bold"}
                 :disabled (not valid?)
                 :on-click #(rf/dispatch [::events/confirm-cast-time-target selected-target])}
        "Confirm"]]]]))


(defn- cast-time-object-target-modal
  "Modal for selecting an object as target when casting a spell (e.g., Recoup targeting graveyard sorcery).
   Uses ::confirm-cast-time-target event."
  [selection cards]
  (let [selected-target (:selection/selected-target selection)
        target-req (:selection/target-requirement selection)
        zone-name (name (or (:target/zone target-req) :graveyard))
        valid? (some? selected-target)]
    [:div {:style {:position "fixed"
                   :top 0
                   :left 0
                   :right 0
                   :bottom 0
                   :background "rgba(0, 0, 0, 0.8)"
                   :display "flex"
                   :align-items "center"
                   :justify-content "center"
                   :z-index 1000}}
     [:div {:style {:background "#1a1a2a"
                    :border "2px solid #4A9BD9"
                    :border-radius "12px"
                    :padding "24px"
                    :max-width "600px"
                    :width "90%"}}
      ;; Header
      [:h2 {:style {:color "#eee"
                    :margin "0 0 8px 0"
                    :font-size "18px"}}
       (str "Choose target from " zone-name)]
      ;; Instructions
      [:p {:style {:color (if valid? "#5CB85C" "#F0AD4E")
                   :margin "0 0 16px 0"
                   :font-size "14px"}}
       (if valid?
         "1 card selected"
         "Select a card")]
      ;; Card grid
      [:div {:style {:display "flex"
                     :flex-wrap "wrap"
                     :gap "10px"
                     :margin-bottom "20px"
                     :min-height "60px"}}
       (if (seq cards)
         (for [obj cards]
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
                    :on-click #(rf/dispatch [::events/select-cast-time-object-target object-id])}
              card-name]))
         [:div {:style {:color "#666"}}
          "No valid targets"])]
      ;; Confirm button
      [:div {:style {:display "flex"
                     :justify-content "flex-end"}}
       [:button {:style {:padding "8px 20px"
                         :border "none"
                         :border-radius "4px"
                         :cursor (if valid? "pointer" "not-allowed")
                         :background (if valid? "#2a6a2a" "#333")
                         :color (if valid? "#fff" "#666")
                         :font-size "14px"
                         :font-weight "bold"}
                 :disabled (not valid?)
                 :on-click #(rf/dispatch [::events/confirm-cast-time-target selected-target])}
        "Confirm"]]]]))


(defn- card-selection-modal
  "Modal for selecting cards (discard or tutor)."
  [selection cards]
  (let [selected (:selection/selected selection)
        required-count (:selection/count selection)
        current-count (count selected)
        effect-type (:selection/effect-type selection)
        is-tutor? (= effect-type :tutor)
        ;; Multi-select tutor support (for Intuition-style cards)
        select-count (:selection/select-count selection)
        is-multi-select? (and is-tutor? select-count (> select-count 1))
        allow-fail-to-find? (:selection/allow-fail-to-find selection)
        ;; Validation logic:
        ;; - Multi-select tutor: exactly select-count OR 0 if fail-to-find allowed
        ;; - Single-select tutor: 0 (fail-to-find) or 1 card
        ;; - Discard: exactly required-count
        valid? (cond
                 is-multi-select?
                 (or (= current-count select-count)
                     (and allow-fail-to-find? (zero? current-count)))

                 is-tutor?
                 (<= current-count 1)

                 :else
                 (= current-count required-count))
        confirm-event (if is-tutor?
                        ::events/confirm-tutor-selection
                        ::events/confirm-selection)]
    [:div {:style {:position "fixed"
                   :top 0
                   :left 0
                   :right 0
                   :bottom 0
                   :background "rgba(0, 0, 0, 0.8)"
                   :display "flex"
                   :align-items "center"
                   :justify-content "center"
                   :z-index 1000}}
     [:div {:style {:background "#1a1a2a"
                    :border "2px solid #4A9BD9"
                    :border-radius "12px"
                    :padding "24px"
                    :max-width "600px"
                    :width "90%"}}
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
         (str current-count " / " select-count " cards selected")

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
         [:button {:style {:padding "8px 20px"
                           :border "1px solid #555"
                           :border-radius "4px"
                           :cursor "pointer"
                           :background "#333"
                           :color "#ccc"
                           :font-size "14px"}
                   :on-click #(do
                                (rf/dispatch [::events/cancel-selection])
                                (rf/dispatch [confirm-event]))}
          "Find Nothing"])
       ;; For discard: Clear button
       (when (not is-tutor?)
         [:button {:style {:padding "8px 20px"
                           :border "1px solid #555"
                           :border-radius "4px"
                           :cursor "pointer"
                           :background "#333"
                           :color "#ccc"
                           :font-size "14px"}
                   :on-click #(rf/dispatch [::events/cancel-selection])}
          "Clear"])
       ;; Confirm button
       [:button {:style {:padding "8px 20px"
                         :border "none"
                         :border-radius "4px"
                         :cursor (if valid? "pointer" "not-allowed")
                         :background (if valid? "#2a6a2a" "#333")
                         :color (if valid? "#fff" "#666")
                         :font-size "14px"
                         :font-weight "bold"}
                 :disabled (not valid?)
                 :on-click #(rf/dispatch [confirm-event])}
        (cond
          is-multi-select? "Select Cards"
          is-tutor? "Select Card"
          :else "Confirm")]]]]))


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


(defn- scry-modal
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
    [:div {:style {:position "fixed" :top 0 :left 0 :right 0 :bottom 0
                   :background "rgba(0, 0, 0, 0.8)"
                   :display "flex" :align-items "center" :justify-content "center"
                   :z-index 1000}}
     [:div {:style {:background "#1a1a2a" :border "2px solid #4A9BD9"
                    :border-radius "12px" :padding "24px"
                    :max-width "600px" :width "90%"}}
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
          [:div {:on-click #(rf/dispatch [::events/scry-unassign (:object/id obj)])}
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
                      :on-click #(rf/dispatch [::events/scry-assign-top (:object/id obj)])}
             "Top"]
            [:button {:style {:padding "4px 8px" :background "#4a2a2a" :border "1px solid #D9534F"
                              :border-radius "4px" :color "#eee" :cursor "pointer" :font-size "10px"}
                      :on-click #(rf/dispatch [::events/scry-assign-bottom (:object/id obj)])}
             "Bottom"]]])]]

      ;; Bottom Pile section
      [:div {:style {:margin-bottom "20px"}}
       [:h3 {:style {:color "#D9534F" :font-size "14px" :margin "0 0 8px 0"}}
        "Bottom of Library (last)"]
       [:div {:style {:display "flex" :flex-wrap "wrap" :gap "8px" :min-height "40px"}}
        (for [obj bottom-cards]
          ^{:key (:object/id obj)}
          [:div {:on-click #(rf/dispatch [::events/scry-unassign (:object/id obj)])}
           [scry-card-view obj :bottom]])]]

      ;; Confirm button
      [:button {:style {:width "100%" :padding "12px" :background "#4A9BD9"
                        :border "none" :border-radius "8px" :color "#fff"
                        :font-size "16px" :cursor "pointer"}
                :on-click #(rf/dispatch [::events/confirm-scry-selection])}
       "Confirm"]]]))


(defn- pile-choice-modal
  "Modal for pile choice selection (Intuition-style).
   Player chooses which card(s) go to hand, rest go to graveyard."
  [selection cards]
  (let [selected (:selection/selected selection)
        hand-count (:selection/hand-count selection)
        current-count (count selected)
        valid? (= current-count hand-count)]
    [:div {:style {:position "fixed"
                   :top 0
                   :left 0
                   :right 0
                   :bottom 0
                   :background "rgba(0, 0, 0, 0.8)"
                   :display "flex"
                   :align-items "center"
                   :justify-content "center"
                   :z-index 1000}}
     [:div {:style {:background "#1a1a2a"
                    :border "2px solid #4A9BD9"
                    :border-radius "12px"
                    :padding "24px"
                    :max-width "600px"
                    :width "90%"}}
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
       [:button {:style {:padding "8px 20px"
                         :border "1px solid #555"
                         :border-radius "4px"
                         :cursor "pointer"
                         :background "#333"
                         :color "#ccc"
                         :font-size "14px"}
                 :on-click #(rf/dispatch [::events/select-random-pile-choice])}
        "Random"]
       ;; Confirm button
       [:button {:style {:padding "8px 20px"
                         :border "none"
                         :border-radius "4px"
                         :cursor (if valid? "pointer" "not-allowed")
                         :background (if valid? "#2a6a2a" "#333")
                         :color (if valid? "#fff" "#666")
                         :font-size "14px"
                         :font-weight "bold"}
                 :disabled (not valid?)
                 :on-click #(rf/dispatch [::events/confirm-pile-choice-selection])}
        "Confirm"]]]]))


(defn- graveyard-selection-modal
  "Modal for returning cards from graveyard to hand (Ill-Gotten Gains style).
   Player can select 0 to max-count cards (not exact count like discard)."
  [selection cards]
  (let [selected (:selection/selected selection)
        max-count (:selection/count selection)
        current-count (count selected)
        ;; Valid if 0 to max-count cards selected
        valid? (<= current-count max-count)]
    [:div {:style {:position "fixed"
                   :top 0
                   :left 0
                   :right 0
                   :bottom 0
                   :background "rgba(0, 0, 0, 0.8)"
                   :display "flex"
                   :align-items "center"
                   :justify-content "center"
                   :z-index 1000}}
     [:div {:style {:background "#1a1a2a"
                    :border "2px solid #6a4a8a"
                    :border-radius "12px"
                    :padding "24px"
                    :max-width "600px"
                    :width "90%"}}
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
       [:button {:style {:padding "8px 20px"
                         :border "1px solid #555"
                         :border-radius "4px"
                         :cursor "pointer"
                         :background "#333"
                         :color "#ccc"
                         :font-size "14px"}
                 :on-click #(rf/dispatch [::events/cancel-selection])}
        "Clear"]
       ;; Confirm button (always valid since 0 is allowed)
       [:button {:style {:padding "8px 20px"
                         :border "none"
                         :border-radius "4px"
                         :cursor (if valid? "pointer" "not-allowed")
                         :background (if valid? "#6a4a8a" "#333")
                         :color (if valid? "#fff" "#666")
                         :font-size "14px"
                         :font-weight "bold"}
                 :disabled (not valid?)
                 :on-click #(rf/dispatch [::events/confirm-graveyard-selection])}
        "Confirm"]]]]))


(defn- selection-modal
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
          [cast-time-player-target-modal selection]

          ;; Cast-time targeting an object (e.g., Recoup targeting graveyard sorcery)
          (and (= selection-type :cast-time-targeting) (not targets-player?))
          [cast-time-object-target-modal selection cards]

          ;; Ability targeting a player (e.g., Cephalid Coliseum threshold)
          (and (= selection-type :ability-targeting) targets-player?)
          [ability-player-target-modal selection]

          ;; Ability targeting an object (e.g., Seal of Cleansing targeting artifact/enchantment)
          (and (= selection-type :ability-targeting) (not targets-player?))
          [ability-object-target-modal selection cards]

          ;; Player target selection (for spell effects during resolution)
          (= effect-type :player-target)
          [player-target-modal selection]

          ;; Graveyard return selection (Ill-Gotten Gains style)
          (= selection-type :graveyard-return)
          [graveyard-selection-modal selection cards]

          ;; Card selection (discard, tutor)
          :else
          [card-selection-modal selection cards])))))


;; === Mode Selection Modal ===
;; Modal for selecting alternate casting modes (e.g., normal vs. Gush-style alternate cost)

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


(defn- mode-selector-modal
  "Modal for selecting casting mode when multiple modes available."
  []
  (let [pending @(rf/subscribe [::subs/pending-mode-selection])]
    (when pending
      (let [modes (:modes pending)]
        [:div {:style {:position "fixed"
                       :top 0
                       :left 0
                       :right 0
                       :bottom 0
                       :background "rgba(0, 0, 0, 0.8)"
                       :display "flex"
                       :align-items "center"
                       :justify-content "center"
                       :z-index 1000}
               :on-click #(rf/dispatch [::events/cancel-mode-selection])}
         [:div {:style {:background "#1a1a2a"
                        :border "2px solid #4A9BD9"
                        :border-radius "12px"
                        :padding "24px"
                        :max-width "400px"
                        :width "90%"}
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


(defn app
  []
  [:div {:style {:padding "20px"
                 :font-family "monospace"
                 :background "#0d0d1a"
                 :color "#eee"
                 :min-height "100vh"}}
   [:h1 {:style {:margin-bottom "20px" :color "#eee"}} "Fizzle"]
   [turn-bar]
   [life-view]
   [battlefield-view]
   [hand-view]
   [controls-view]
   [graveyard-view]
   [mana-pool-view]
   [storm-count-view]
   [stack-view]
   ;; Selection modal (shows when pending selection exists)
   [selection-modal]
   ;; Mode selection modal (shows when multiple casting modes available)
   [mode-selector-modal]])


(defn ^:dev/after-load mount-root
  []
  (rf/clear-subscription-cache!)
  (rdom/render [app] (.getElementById js/document "app")))


(defn init
  []
  (rf/dispatch-sync [::events/init-game])
  (mount-root))
