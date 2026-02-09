(ns fizzle.views.phase-bar
  (:require
    [fizzle.events.game :as events]
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]))


(defn- phase-indicator
  [phase current-phase]
  [:span {:class (str "py-1 px-2 rounded text-[11px] "
                      (if (= phase current-phase)
                        "bg-accent text-white"
                        "bg-transparent text-perm-text-tapped"))}
   (name phase)])


(defn phase-bar-view
  []
  (let [current-phase (or @(rf/subscribe [::subs/current-phase]) :main1)
        current-turn (or @(rf/subscribe [::subs/current-turn]) 1)
        at-cleanup? (= current-phase :cleanup)
        stack @(rf/subscribe [::subs/stack])
        stack-active? (boolean (seq stack))]
    [:div {:class "flex items-center gap-4 py-2.5 px-4 mb-4 bg-surface-raised border-b border-surface-dim"}
     [:span {:class "font-bold text-text text-sm"}
      (str "Turn " current-turn)]
     [:div {:class "flex gap-1"}
      (for [phase events/phases]
        ^{:key phase}
        [phase-indicator phase current-phase])]
     [:button {:class (str "py-1.5 px-3.5 border border-border rounded font-bold text-[13px] "
                           (if stack-active?
                             "cursor-not-allowed bg-surface-dim text-perm-text-tapped opacity-50"
                             "cursor-pointer bg-btn-enabled-bg text-white"))
               :disabled stack-active?
               :on-click #(when-not stack-active?
                            (rf/dispatch [(if at-cleanup?
                                            ::events/start-turn
                                            ::events/advance-phase)]))}
      (if at-cleanup? "New Turn" "Next Phase")]]))
