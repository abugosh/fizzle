(ns fizzle.views.calculator
  "Reagent view components for the live hypergeometric draw calculator dashboard.
   Read-only: subscribes to calculator state and dispatches CRUD events only.
   No probability math, no Datascript access, no game state mutation."
  (:require
    [clojure.string :as str]
    [fizzle.engine.cards :as cards]
    [fizzle.events.calculator :as calc-events]
    [fizzle.subs.calculator :as calc-subs]
    [re-frame.core :as rf]))


;; === Helper functions ===

(defn- format-probability
  "Format a probability float (0.0–1.0) as a percentage string with 1 decimal place.
   0.3995 → \"39.9%\""
  [p]
  (str (.toFixed (* p 100) 1) "%"))


(defn- card-display-name
  "Return a human-readable display name for a card-id keyword.
   Uses :card/name from card registry; falls back to humanizing the keyword."
  [card-id]
  (or (get-in (get cards/card-by-id card-id) [:card/name])
      (-> (name card-id)
          (str/replace "-" " ")
          (str/replace #"\b\w" str/upper-case))))


;; === Sub-components ===

(defn- card-selection-grid
  "Clickable grid of cards from the library. Cards in selected-cards set are highlighted."
  [library-card-counts selected-cards query-id step-id target-id]
  (let [sorted-cards (sort-by (fn [[card-id _]] (card-display-name card-id))
                              library-card-counts)]
    [:div {:class "flex flex-wrap gap-1 mt-1"}
     (for [[card-id count] sorted-cards]
       (let [selected? (contains? selected-cards card-id)]
         ^{:key card-id}
         [:div {:class    (str "px-2 py-1 rounded text-xs cursor-pointer select-none border "
                               (if selected?
                                 "bg-accent/20 border-accent text-accent"
                                 "bg-surface border-border text-text-muted hover:border-text-muted"))
                :on-click #(rf/dispatch [::calc-events/toggle-target-card
                                         query-id step-id target-id card-id])}
          (str (card-display-name card-id) " ×" count)]))]))


(defn- target-group-view
  "Renders a single target group: min-count input, card selection grid, probability, delete."
  [target query-id step-id library-card-counts]
  (let [target-id   (:target/id target)
        cards-set   (:target/cards target)
        min-count   (:target/min-count target)
        target-prob (:target/probability target)
        card-count  (:target/count target)]
    [:div {:class "border border-border rounded p-2 bg-surface mt-1"}
     [:div {:class "flex items-center justify-between gap-2"}
      [:div {:class "flex items-center gap-2 text-xs text-text-muted"}
       [:span "Need"]
       [:input {:type      "number"
                :min       "0"
                :value     min-count
                :class     "w-12 bg-surface-raised border border-border rounded px-1 py-0.5 text-text text-xs text-center"
                :on-change #(let [v (js/parseInt (.. % -target -value) 10)]
                              (when-not (js/isNaN v)
                                (rf/dispatch [::calc-events/set-target-min-count
                                              query-id step-id target-id v])))}]
       [:span (str "of " (or card-count 0) " cards")]
       (when (some? target-prob)
         [:span {:class "text-accent font-bold ml-2"} (format-probability target-prob)])]
      [:button {:class    "text-text-muted hover:text-text text-xs cursor-pointer"
                :on-click #(rf/dispatch [::calc-events/remove-target-group
                                         query-id step-id target-id])}
       "✕"]]
     [card-selection-grid library-card-counts cards-set query-id step-id target-id]]))


(defn- step-view
  "Renders a single step: draw-count input, target groups, step probability, add/delete buttons."
  [step query-id library-card-counts]
  (let [step-id   (:step/id step)
        draw-cnt  (:step/draw-count step)
        step-prob (:step/probability step)
        targets   (:step/targets step)]
    [:div {:class "border border-border rounded p-2 bg-surface-raised mt-2"}
     [:div {:class "flex items-center justify-between gap-2"}
      [:div {:class "flex items-center gap-2 text-xs"}
       [:span {:class "text-text-muted"} "Draw"]
       [:input {:type      "number"
                :min       "0"
                :value     draw-cnt
                :class     "w-14 bg-surface border border-border rounded px-1 py-0.5 text-text text-xs text-center"
                :on-change #(let [v (js/parseInt (.. % -target -value) 10)]
                              (when-not (js/isNaN v)
                                (rf/dispatch [::calc-events/set-step-draw-count
                                              query-id step-id v])))}]
       [:span {:class "text-text-muted"} "cards"]
       (when (some? step-prob)
         [:span {:class "text-accent font-bold ml-2"} (format-probability step-prob)])]
      [:button {:class    "text-text-muted hover:text-text text-xs cursor-pointer"
                :on-click #(rf/dispatch [::calc-events/remove-step query-id step-id])}
       "✕ step"]]
     [:div {:class "mt-1"}
      (for [target targets]
        ^{:key (:target/id target)}
        [target-group-view target query-id step-id library-card-counts])]
     [:button {:class    "mt-1 text-xs text-text-muted hover:text-text cursor-pointer"
               :on-click #(rf/dispatch [::calc-events/add-target-group query-id step-id])}
      "+ Target Group"]]))


(defn- query-row-collapsed
  "Collapsed query row: label, overall probability, expand button, delete button."
  [query]
  (let [query-id (:query/id query)
        label    (:query/label query)
        prob     (:query/probability query)]
    [:div {:class "flex items-center gap-2 p-2 border border-border rounded bg-surface-raised cursor-pointer"
           :on-click #(rf/dispatch [::calc-events/toggle-query-collapsed query-id])}
     [:span {:class "text-text text-sm flex-1 truncate"} label]
     (when (some? prob)
       [:span {:class "text-accent font-bold text-sm"} (format-probability prob)])
     [:span {:class "text-text-muted text-xs"} "\u25B6"]
     [:button {:class    "text-text-muted hover:text-text text-xs cursor-pointer ml-1"
               :on-click #(do
                            (.stopPropagation %)
                            (rf/dispatch [::calc-events/remove-query query-id]))}
      "✕"]]))


(defn- query-row-expanded
  "Expanded query row: editable label, steps, add step, overall probability, delete button."
  [query library-card-counts]
  (let [query-id (:query/id query)
        label    (:query/label query)
        prob     (:query/probability query)
        steps    (:query/steps query)]
    [:div {:class "border border-border rounded bg-surface-raised p-2"}
     [:div {:class "flex items-center gap-2 mb-2"}
      [:input {:type        "text"
               :value       label
               :class       "flex-1 bg-surface border border-border rounded px-2 py-1 text-text text-sm"
               :on-change   #(rf/dispatch [::calc-events/set-query-label
                                           query-id (.. % -target -value)])}]
      (when (some? prob)
        [:span {:class "text-accent font-bold text-sm"} (format-probability prob)])
      [:button {:class    "text-text-muted hover:text-text text-xs cursor-pointer"
                :on-click #(rf/dispatch [::calc-events/toggle-query-collapsed query-id])}
       "\u25BC"]
      [:button {:class    "text-text-muted hover:text-text text-xs cursor-pointer ml-1"
                :on-click #(rf/dispatch [::calc-events/remove-query query-id])}
       "✕"]]
     (for [step steps]
       ^{:key (:step/id step)}
       [step-view step query-id library-card-counts])
     [:button {:class    "mt-2 text-xs text-text-muted hover:text-text cursor-pointer block"
               :on-click #(rf/dispatch [::calc-events/add-step query-id])}
      "+ Step"]]))


(defn- query-view
  "Routes to collapsed or expanded query row based on :query/collapsed?."
  [query library-card-counts]
  ^{:key (:query/id query)}
  [:div {:class "mb-2"}
   (if (:query/collapsed? query)
     [query-row-collapsed query]
     [query-row-expanded query library-card-counts])])


;; === Top-level panel ===

(defn calculator-panel
  "Top-level calculator dashboard. Returns nil when not visible.
   Subscribes to visibility, results, and library card counts."
  []
  (let [visible?     @(rf/subscribe [::calc-subs/calculator-visible?])
        results      @(rf/subscribe [::calc-subs/calculator-results])
        card-counts  @(rf/subscribe [::calc-subs/library-card-counts])]
    (when visible?
      [:div {:class "border-t border-border bg-surface p-3"}
       ;; Header
       [:div {:class "flex items-center gap-3 mb-3"}
        [:span {:class "text-text-label text-xs font-bold uppercase tracking-wider flex-1"}
         "Draw Calculator"]
        [:button {:class    "px-2 py-1 text-xs bg-surface-raised border border-border rounded text-text cursor-pointer hover:border-text-muted"
                  :on-click #(rf/dispatch [::calc-events/add-query])}
         "+ Query"]
        [:button {:class    "text-text-muted hover:text-text text-xs cursor-pointer"
                  :on-click #(rf/dispatch [::calc-events/toggle-calculator])}
         "Close"]]
       ;; Query list
       (if (seq results)
         [:div
          (for [query results]
            ^{:key (:query/id query)}
            [query-view query card-counts])]
         [:div {:class "text-text-muted text-xs"} "No queries. Click \"+ Query\" to add one."])])))
