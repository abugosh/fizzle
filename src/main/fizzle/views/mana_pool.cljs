(ns fizzle.views.mana-pool
  (:require
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]))


(def ^:private mana-symbols
  {:white "W" :blue "U" :black "B" :red "R" :green "G" :colorless "C"})


(defn mana-color-class
  "Returns Tailwind text color class for a mana color keyword."
  [color]
  (case color
    :white "text-mana-white"
    :blue "text-mana-blue"
    :black "text-mana-black"
    :red "text-mana-red"
    :green "text-mana-green"
    :colorless "text-mana-colorless"
    "text-mana-colorless"))


(defn mana-pool-view
  []
  (let [mana @(rf/subscribe [::subs/mana-pool])]
    [:div {:class "mb-4"}
     [:div {:class "text-text-label mb-1.5 text-xs"} "MANA POOL"]
     [:div {:class "flex gap-2.5"}
      (for [[color amount] mana
            :when (pos? amount)]
        ^{:key color}
        [:span {:class (str "font-bold text-lg " (mana-color-class color))}
         (str (get mana-symbols color (name color)) ": " amount)])]]))


(defn storm-count-view
  []
  (let [storm @(rf/subscribe [::subs/storm-count])]
    [:div {:class "mb-4"}
     [:div {:class "text-text-label mb-1.5 text-xs"} "STORM COUNT"]
     [:div {:class "text-2xl font-bold text-gold"} storm]]))
