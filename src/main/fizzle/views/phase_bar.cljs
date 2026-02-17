(ns fizzle.views.phase-bar
  (:require
    [fizzle.engine.rules :as rules]
    [fizzle.events.game :as events]
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]))


(defn- phase-indicator
  [phase current-phase stops]
  (let [has-stop? (contains? stops phase)]
    [:div {:class "flex flex-col items-center cursor-pointer"
           :on-click #(rf/dispatch [::events/toggle-stop phase])}
     [:span {:class (str "py-1 px-2 rounded text-[11px] "
                         (if (= phase current-phase)
                           "bg-accent text-white"
                           "bg-transparent text-perm-text-tapped"))}
      (name phase)]
     [:div {:class (str "w-1.5 h-1.5 rounded-full mt-0.5 "
                        (if has-stop? "bg-accent" "bg-transparent"))}]]))


(defn phase-bar-view
  []
  (let [current-phase (or @(rf/subscribe [::subs/current-phase]) :main1)
        current-turn (or @(rf/subscribe [::subs/current-turn]) 1)
        stops (or @(rf/subscribe [::subs/player-stops]) #{})]
    [:div {:class "flex items-center gap-4 py-2.5 px-4 mb-4 bg-surface-raised border-b border-surface-dim"}
     [:span {:class "font-bold text-text text-sm"}
      (str "Turn " current-turn)]
     [:div {:class "flex gap-1"}
      (for [phase rules/phases]
        ^{:key phase}
        [phase-indicator phase current-phase stops])]]))
