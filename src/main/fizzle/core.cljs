(ns fizzle.core
  (:require
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.game :as events]
    [fizzle.events.selection]
    [fizzle.history.interceptor :as history-interceptor]
    [fizzle.subs.game :as subs]
    [fizzle.views.modals :as modals]
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
              :on-click #(rf/dispatch [::ability-events/activate-mana-ability object-id mana-color])}
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
            :on-click #(rf/dispatch [::ability-events/activate-ability object-id ability-index])}
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
  (let [card-name (or (get-in item [:object/card :card/name])
                      (:stack-item/card-name item))
        desc (:stack-item/description item)
        label (cond
                (and card-name desc) (str card-name ": " desc)
                card-name card-name
                desc desc
                :else (when-let [t (:stack-item/type item)]
                        (str "Stack: " (name t))))]
    [:div {:style {:border "1px solid #444"
                   :border-radius "4px"
                   :padding "6px 10px"
                   :margin-bottom "4px"
                   :background "#1a1a2a"
                   :color "#ccc"
                   :font-size "13px"}}
     label]))


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
        at-cleanup? (= current-phase :cleanup)
        stack @(rf/subscribe [::subs/stack])
        stack-active? (boolean (seq stack))]
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
                       :cursor (if stack-active? "not-allowed" "pointer")
                       :background (if stack-active? "#333" "#2a6a2a")
                       :color (if stack-active? "#666" "#fff")
                       :font-size "13px"
                       :font-weight "bold"
                       :opacity (if stack-active? 0.5 1)}
               :disabled stack-active?
               :on-click #(when-not stack-active?
                            (rf/dispatch [(if at-cleanup?
                                            ::events/start-turn
                                            ::events/advance-phase)]))}
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
   [modals/selection-modal]
   ;; Mode selection modal (shows when multiple casting modes available)
   [modals/mode-selector-modal]])


(defn ^:dev/after-load mount-root
  []
  (rf/clear-subscription-cache!)
  (rdom/render [app] (.getElementById js/document "app")))


(defn init
  []
  (history-interceptor/register!)
  (rf/dispatch-sync [::events/init-game])
  (mount-root))
