(ns fizzle.views.graveyard
  (:require
    [fizzle.events.game :as events]
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]))


(defn- graveyard-card-view
  [obj has-flashback? castable? selected?]
  (let [card-name (get-in obj [:object/card :card/name])
        object-id (:object/id obj)]
    [:div {:class (str "py-1 px-2 rounded text-xs select-none "
                       (cond
                         selected?
                         "bg-gy-selected-bg border-2 border-border-selected text-gy-flashback-text cursor-pointer"
                         castable?
                         "bg-surface-hover border border-gy-flashback-border text-gy-flashback-text cursor-pointer"
                         :else
                         "bg-gy-card-bg border border-perm-border-tapped text-text-muted cursor-default"))
           :on-click (when castable?
                       #(rf/dispatch [::events/select-card object-id]))}
     card-name
     (when has-flashback?
       [:span {:class "ml-1.5 py-px px-1 bg-flashback-badge-bg rounded-sm text-[9px] text-text"}
        "FB"])]))


(defn graveyard-view
  []
  (let [{:keys [castable remainder]} @(rf/subscribe [::subs/graveyard])
        flashback-ids @(rf/subscribe [::subs/flashback-ids])
        castable-ids @(rf/subscribe [::subs/flashback-castable-ids])
        sort-mode @(rf/subscribe [::subs/graveyard-sort-mode])
        selected @(rf/subscribe [::subs/selected-card])
        card-count (+ (count castable) (count remainder))
        threshold? (>= card-count 7)]
    [:div {:class "mb-4"}
     [:div {:class "text-text-label mb-1.5 text-xs"}
      (str "GRAVEYARD (" card-count ")")
      (when threshold?
        [:span {:class "ml-2 py-0.5 px-1.5 bg-flashback-badge-bg rounded text-[10px] text-text"}
         "THRESHOLD"])]
     (if (pos? card-count)
       [:div {:class "flex flex-col gap-0.5 max-h-[200px] overflow-y-auto"}
        (when (seq castable)
          (for [obj castable]
            ^{:key (:object/id obj)}
            [graveyard-card-view obj true
             (contains? castable-ids (:object/id obj))
             (= (:object/id obj) selected)]))
        (for [obj remainder]
          ^{:key (:object/id obj)}
          [graveyard-card-view obj
           (contains? flashback-ids (:object/id obj))
           (contains? castable-ids (:object/id obj))
           (= (:object/id obj) selected)])
        [:div {:class "flex justify-end mt-1"}
         [:button {:class "text-[10px] text-text-muted hover:text-text cursor-pointer"
                   :on-click #(rf/dispatch [::events/toggle-graveyard-sort])}
          (if (= sort-mode :sorted) "Entry Order" "Sort")]]]
       [:div {:class "text-border text-[13px]"} "Empty"])]))
