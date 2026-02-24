(ns fizzle.views.mana-pool
  (:require
    [fizzle.events.selection :as selection-events]
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


(defn- format-mana-cost
  "Format a mana cost map as a short string (e.g., {3}{U})."
  [mana-cost]
  (if (or (nil? mana-cost) (empty? mana-cost))
    "{0}"
    (str
      (when-let [c (:colorless mana-cost)]
        (when (pos? c) (str "{" c "}")))
      (when-let [w (:white mana-cost)]
        (apply str (repeat w "{W}")))
      (when-let [u (:blue mana-cost)]
        (apply str (repeat u "{U}")))
      (when-let [b (:black mana-cost)]
        (apply str (repeat b "{B}")))
      (when-let [r (:red mana-cost)]
        (apply str (repeat r "{R}")))
      (when-let [g (:green mana-cost)]
        (apply str (repeat g "{G}"))))))


(defn unless-pay-view
  "Inline prompt for unless-they-pay counter choice.
   Renders below the mana pool so the player can tap lands first,
   then choose to pay or decline."
  []
  (when-let [state @(rf/subscribe [::subs/unless-pay-state])]
    (let [selected (or (:selection/selected state) #{})
          can-pay? @(rf/subscribe [::subs/unless-pay-can-afford?])
          unless-pay-cost (:selection/unless-pay-cost state)
          formatted-cost (format-mana-cost unless-pay-cost)
          valid? @(rf/subscribe [::subs/selection-valid?])]
      [:div {:class "mb-4 border-2 border-border-accent rounded-lg p-3"}
       [:div {:class "text-text-label mb-1.5 text-xs"} "COUNTER — PAY OR DECLINE?"]
       [:div {:class "text-sm text-text-muted mb-2"}
        (str "Pay " formatted-cost " to prevent your spell from being countered.")]
       [:div {:class "flex gap-2 mb-2"}
        [:button
         {:class (str "py-2 px-4 rounded font-bold text-sm "
                      (if can-pay?
                        (str "cursor-pointer text-text border-2 "
                             (if (contains? selected :pay)
                               "border-border-accent bg-modal-selected-bg"
                               "border-border bg-surface-raised"))
                        "cursor-not-allowed border-2 border-border bg-surface-dim text-perm-text-tapped"))
          :disabled (not can-pay?)
          :on-click (when can-pay?
                      #(rf/dispatch [::selection-events/toggle-selection :pay]))}
         (str "Pay " formatted-cost)]
        [:button
         {:class (str "py-2 px-4 rounded cursor-pointer font-bold text-sm text-text border-2 "
                      (if (contains? selected :decline)
                        "border-border-accent bg-modal-selected-bg"
                        "border-border bg-surface-raised"))
          :on-click #(rf/dispatch [::selection-events/toggle-selection :decline])}
         "Decline"]]
       [:div
        [:button
         {:class (str "py-1.5 px-4 border-none rounded font-bold text-sm "
                      (if valid?
                        "cursor-pointer bg-btn-enabled-bg text-white"
                        "cursor-not-allowed bg-surface-dim text-perm-text-tapped"))
          :disabled (not valid?)
          :on-click (when valid?
                      #(rf/dispatch [::selection-events/confirm-selection]))}
         "Confirm"]]])))


(defn storm-count-view
  []
  (let [storm @(rf/subscribe [::subs/storm-count])]
    [:div {:class "mb-4"}
     [:div {:class "text-text-label mb-1.5 text-xs"} "STORM COUNT"]
     [:div {:class "text-2xl font-bold text-gold"} storm]]))
