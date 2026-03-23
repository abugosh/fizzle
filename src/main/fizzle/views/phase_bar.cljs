(ns fizzle.views.phase-bar
  (:require
    [fizzle.engine.rules :as rules]
    [fizzle.events.ui :as ui-events]
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]))


(defn- stop-dot
  "Clickable stop indicator dot for a phase."
  [phase stops role]
  (let [has-stop? (contains? stops phase)]
    [:div {:class "flex justify-center cursor-pointer py-0.5"
           :on-click #(rf/dispatch [::ui-events/toggle-stop role phase])}
     [:div {:class (str "w-1.5 h-1.5 rounded-full "
                        (if has-stop? "bg-accent" "bg-surface-dim"))}]]))


(defn- phase-column
  "A single phase column: opponent dot, phase label, player dot — vertically stacked."
  [phase current-phase player-stops opponent-stops]
  [:div {:class "flex flex-col items-center"}
   [stop-dot phase opponent-stops :opponent]
   [:span {:class (str "py-1 px-2 rounded text-[11px] "
                       (if (= phase current-phase)
                         "bg-accent text-white"
                         "bg-transparent text-perm-text-tapped"))}
    (name phase)]
   [stop-dot phase player-stops :player]])


(defn phase-bar-view
  []
  (let [current-phase (or @(rf/subscribe [::subs/current-phase]) :main1)
        current-turn (or @(rf/subscribe [::subs/current-turn]) 1)
        player-stops (or @(rf/subscribe [::subs/player-stops]) #{})
        opponent-stops (or @(rf/subscribe [::subs/opponent-stops]) #{})]
    [:div {:class "flex items-center gap-4 py-2.5 px-4 mb-4 bg-surface-raised border-b border-surface-dim"}
     [:span {:class "font-bold text-text text-sm"}
      (str "Turn " current-turn)]
     [:div {:class "flex items-center gap-1"}
      ;; Labels column
      [:div {:class "flex flex-col items-end mr-1"}
       [:span {:class "text-[10px] text-text-dim leading-[14px]"} "Opp"]
       [:span {:class "py-1 text-[11px] text-transparent"} "\u00a0"]
       [:span {:class "text-[10px] text-text-dim leading-[14px]"} "You"]]
      ;; Phase columns
      (for [phase rules/phases]
        ^{:key (name phase)}
        [phase-column phase current-phase player-stops opponent-stops])]]))
