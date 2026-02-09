(ns fizzle.views.graveyard
  (:require
    [fizzle.events.game :as events]
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]))


(defn- graveyard-card-view
  [obj flashback-castable? selected?]
  (let [card-name (get-in obj [:object/card :card/name])
        object-id (:object/id obj)]
    [:div {:class (str "py-1 px-2 rounded text-xs select-none "
                       (cond
                         selected?
                         "bg-gy-selected-bg border-2 border-border-selected text-gy-flashback-text cursor-pointer"
                         flashback-castable?
                         "bg-surface-hover border border-gy-flashback-border text-gy-flashback-text cursor-pointer"
                         :else
                         "bg-gy-card-bg border border-perm-border-tapped text-text-muted cursor-default"))
           :on-click (when flashback-castable?
                       #(rf/dispatch [::events/select-card object-id]))}
     card-name
     (when flashback-castable?
       [:span {:class "ml-1.5 py-px px-1 bg-flashback-badge-bg rounded-sm text-[9px] text-text"}
        "FB"])]))


(defn graveyard-view
  []
  (let [graveyard @(rf/subscribe [::subs/graveyard])
        flashback-ids @(rf/subscribe [::subs/flashback-castable-ids])
        selected @(rf/subscribe [::subs/selected-card])
        card-count (count graveyard)
        threshold? (>= card-count 7)]
    [:div {:class "mb-4"}
     [:div {:class "text-text-label mb-1.5 text-xs"}
      (str "GRAVEYARD (" card-count ")")
      (when threshold?
        [:span {:class "ml-2 py-0.5 px-1.5 bg-flashback-badge-bg rounded text-[10px] text-text"}
         "THRESHOLD"])]
     (if (seq graveyard)
       [:div {:class "flex flex-col gap-0.5 max-h-[200px] overflow-y-auto"}
        (for [obj graveyard]
          ^{:key (:object/id obj)}
          [graveyard-card-view obj
           (contains? flashback-ids (:object/id obj))
           (= (:object/id obj) selected)])]
       [:div {:class "text-border text-[13px]"} "Empty"])]))
