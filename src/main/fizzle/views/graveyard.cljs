(ns fizzle.views.graveyard
  (:require
    [fizzle.events.ui :as ui-events]
    [fizzle.subs.game :as subs]
    [fizzle.views.card-styles :as card-styles]
    [re-frame.core :as rf]))


(defn- graveyard-card-view
  [obj has-flashback? castable? selected?]
  (let [card-name (get-in obj [:object/card :card/name])
        object-id (:object/id obj)
        card-types (get-in obj [:object/card :card/types])
        card-colors (get-in obj [:object/card :card/colors])
        border-class (cond
                       selected? "border-2 border-border-selected"
                       castable? "border border-gy-flashback-border"
                       :else (str "border " (card-styles/get-type-border-class card-types false)))
        bg-class (card-styles/get-color-identity-bg-class card-colors card-types)
        text-class (if (or selected? castable?)
                     "text-gy-flashback-text"
                     "text-text-muted")
        cursor-class (if castable? "cursor-pointer" "cursor-default")]
    [:div {:class (str "py-1 px-2 rounded text-xs select-none "
                       border-class " " bg-class " " text-class " " cursor-class)
           :on-click (when castable?
                       #(rf/dispatch [::ui-events/select-card object-id]))}
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
       [:div {:class "flex flex-col gap-0.5"}
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
                   :on-click #(rf/dispatch [::ui-events/toggle-graveyard-sort])}
          (if (= sort-mode :sorted) "Entry Order" "Sort")]]]
       [:div {:class "text-border text-[13px]"} "Empty"])]))
