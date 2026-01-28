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
