(ns fizzle.views.opponent
  (:require
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]))


(defn player-health-class
  "Returns Tailwind class for player life color.
   Player: low life = critical, high = good."
  [life]
  (cond
    (<= life 0) "text-health-critical"
    (<= life 5) "text-health-danger"
    :else "text-health-good"))


(defn opponent-health-class
  "Returns Tailwind class for opponent life color.
   Opponent: low life = good (for player), high = critical."
  [life]
  (cond
    (<= life 0) "text-health-good"
    (<= life 5) "text-health-danger"
    :else "text-health-critical"))


(defn life-view
  []
  (let [player-life @(rf/subscribe [::subs/player-life])
        opponent-life @(rf/subscribe [::subs/opponent-life])]
    [:div {:class "mb-4"}
     [:div {:class "text-text-label mb-1.5 text-xs"} "LIFE TOTALS"]
     [:div {:class "flex gap-8"}
      [:div
       [:span {:class "text-text-dim text-sm"} "You: "]
       [:span {:class (str "text-xl font-bold " (player-health-class player-life))}
        player-life]]
      [:div
       [:span {:class "text-text-dim text-sm"} "Opponent: "]
       [:span {:class (str "text-xl font-bold " (opponent-health-class opponent-life))}
        opponent-life]]]]))
