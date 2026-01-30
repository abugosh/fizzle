(ns fizzle.core
  (:require
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


(defn- permanent-view
  [obj]
  (let [card-name (get-in obj [:object/card :card/name])
        object-id (:object/id obj)
        tapped? (:object/tapped obj)
        counters (:object/counters obj)]
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
     ;; Mana buttons for lands
     [:div {:style {:display "flex" :justify-content "center" :flex-wrap "wrap"}}
      (for [color [:white :blue :black :red :green]]
        ^{:key color}
        [mana-button object-id color tapped?])]]))


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


(defn- stack-item-view
  [item]
  (let [label (or (:trigger/type item)
                  (get-in item [:object/card :card/name])
                  "Unknown")]
    [:div {:style {:border "1px solid #444"
                   :border-radius "4px"
                   :padding "6px 10px"
                   :margin-bottom "4px"
                   :background "#1a1a2a"
                   :color "#ccc"
                   :font-size "13px"}}
     (if (:trigger/type item)
       (str "Trigger: " (name (:trigger/type item)))
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
   [life-view]
   [battlefield-view]
   [hand-view]
   [controls-view]
   [mana-pool-view]
   [storm-count-view]
   [stack-view]])


(defn ^:dev/after-load mount-root
  []
  (rf/clear-subscription-cache!)
  (rdom/render [app] (.getElementById js/document "app")))


(defn init
  []
  (rf/dispatch-sync [::events/init-game])
  (mount-root))
