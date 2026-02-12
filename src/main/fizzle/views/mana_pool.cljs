(ns fizzle.views.mana-pool
  (:require
    [fizzle.events.selection.costs :as cost-events]
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]))


(def ^:private mana-symbols
  {:white "W" :blue "U" :black "B" :red "R" :green "G" :colorless "C"})


(def ^:private color-order
  [:white :blue :black :red :green :colorless])


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


(defn- allocation-mana-button
  "Render a single mana button during allocation mode."
  [color amount allocated]
  (let [available? (pos? amount)]
    ^{:key color}
    [:button
     {:class (str "rounded-md px-3 py-2 border-2 border-border font-bold text-lg "
                  (mana-color-class color) " "
                  (if available?
                    "cursor-pointer hover:bg-btn-enabled-bg"
                    "opacity-30 cursor-not-allowed"))
      :on-click (when available?
                  #(rf/dispatch [::cost-events/allocate-mana-color color]))
      :disabled (not available?)}
     (str (get mana-symbols color (name color)) ": " amount
          (when (pos? allocated) (str " (" allocated " used)")))]))


(defn- allocation-view
  "Render interactive mana pool during allocation mode."
  [alloc-state]
  (let [remaining-pool (:selection/remaining-pool alloc-state)
        allocation (:selection/allocation alloc-state)
        generic-remaining (:selection/generic-remaining alloc-state)
        generic-total (:selection/generic-total alloc-state)
        has-allocation? (seq allocation)]
    [:div {:class "mb-4"}
     [:div {:class "text-text-label mb-1.5 text-xs"} "ALLOCATE MANA"]
     [:div {:class "text-sm text-gold mb-2"}
      (str generic-remaining " of " generic-total " generic remaining")]
     [:div {:class "flex gap-2.5 flex-wrap"}
      (for [color color-order
            :let [amount (get remaining-pool color)]
            :when (some? amount)]
        (allocation-mana-button color amount (get allocation color 0)))]
     [:div {:class "flex gap-2 mt-2"}
      [:button
       {:class (str "py-1.5 px-3 border border-border rounded text-sm font-bold "
                    (if has-allocation?
                      "cursor-pointer text-white"
                      "cursor-default text-border"))
        :on-click (when has-allocation?
                    #(rf/dispatch [::cost-events/reset-mana-allocation]))
        :disabled (not has-allocation?)}
       "Reset"]
      [:button
       {:class "py-1.5 px-3 border border-border rounded text-sm font-bold cursor-pointer text-white"
        :on-click #(rf/dispatch [::cost-events/cancel-mana-allocation])}
       "Cancel"]]]))


(defn mana-pool-view
  []
  (let [mana @(rf/subscribe [::subs/mana-pool])
        alloc-state @(rf/subscribe [::subs/mana-allocation-state])]
    (if alloc-state
      [allocation-view alloc-state]
      [:div {:class "mb-4"}
       [:div {:class "text-text-label mb-1.5 text-xs"} "MANA POOL"]
       [:div {:class "flex gap-2.5"}
        (for [[color amount] mana
              :when (pos? amount)]
          ^{:key color}
          [:span {:class (str "font-bold text-lg " (mana-color-class color))}
           (str (get mana-symbols color (name color)) ": " amount)])]])))


(defn storm-count-view
  []
  (let [storm @(rf/subscribe [::subs/storm-count])]
    [:div {:class "mb-4"}
     [:div {:class "text-text-label mb-1.5 text-xs"} "STORM COUNT"]
     [:div {:class "text-2xl font-bold text-gold"} storm]]))
