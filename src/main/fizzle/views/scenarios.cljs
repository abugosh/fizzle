(ns fizzle.views.scenarios
  "Scenario builder and library screens."
  (:require
    [fizzle.bots.definitions :as bot-definitions]
    [fizzle.engine.cards :as cards]
    [fizzle.events.scenario :as scenario-events]
    [fizzle.events.setup :as setup-events]
    [fizzle.subs.scenario :as subs-scenario]
    [fizzle.subs.setup :as subs-setup]
    [re-frame.core :as rf]
    [reagent.core :as r]))


(defn library-view
  "Display saved scenarios or a prompt to create one."
  []
  (let [scenarios @(rf/subscribe [::subs-scenario/all-scenarios])]
    [:div {:class "p-4"}
     (if (empty? scenarios)
       [:div
        [:p {:class "text-text-muted mb-4"} "No scenarios yet"]
        [:button {:class "px-4 py-2 bg-accent text-surface rounded font-bold cursor-pointer"
                  :on-click #(rf/dispatch [::scenario-events/show-builder])}
         "New Scenario"]]
       [:div
        [:p "Scenarios: " (count scenarios)]
        [:button {:class "px-4 py-2 bg-accent text-surface rounded font-bold cursor-pointer"
                  :on-click #(rf/dispatch [::scenario-events/show-builder])}
         "New Scenario"]])]))


;; === Deck display helpers ===

(def ^:private type-order [:land :instant :sorcery :artifact :creature :enchantment :other])


(def ^:private type-labels
  {:land "Lands" :instant "Instants" :sorcery "Sorceries"
   :artifact "Artifacts" :creature "Creatures" :enchantment "Enchantments"
   :other "Other"})


(defn- deck-card-row
  "Single card entry in a deck panel with a remove button."
  [{:keys [card/id card/name count]} side]
  [:div {:key id :class "flex justify-between items-center text-sm py-0.5"}
   [:span {:class "text-text"} (str count "x " name)]
   [:button {:class "px-1 text-text-muted hover:text-error cursor-pointer"
             :on-click #(rf/dispatch [::scenario-events/remove-card {:side side :card-id id}])}
    "−"]])


(defn- deck-panel
  "Grouped deck display with per-card remove buttons and an add-card dropdown."
  [grouped side available-cards-list]
  (let [total (reduce + 0 (map :count (apply concat (vals grouped))))]
    [:div {:class "space-y-2"}
     (if (empty? grouped)
       [:p {:class "text-text-muted text-sm"} "Deck empty"]
       [:<>
        [:div {:class "text-xs text-text-muted"} (str total " cards")]
        (for [card-type type-order
              :let [entries (get grouped card-type)]
              :when (seq entries)]
          ^{:key card-type}
          [:div {:class "mb-1"}
           [:div {:class "text-text-label text-xs uppercase font-semibold"}
            (get type-labels card-type)]
           (for [entry entries]
             ^{:key (:card/id entry)}
             [deck-card-row entry side])])])
     ;; Add-card dropdown
     [:div {:class "mt-2"}
      [:select {:class "bg-surface-raised border border-border rounded px-2 py-1 text-xs text-text w-full"
                :value ""
                :on-change #(let [v (.. % -target -value)]
                              (when (seq v)
                                (rf/dispatch [::scenario-events/add-card
                                              {:side side :card-id (keyword v)}]))
                              (set! (.. % -target -value) ""))}
       [:option {:value ""} "Add card..."]
       (for [{:keys [card/id card/name]} (sort-by :card/name available-cards-list)]
         ^{:key id}
         [:option {:value (cljs.core/name id)} name])]]]))


;; === Zone distribution UI ===

(def ^:private zone-labels
  {:hand "Hand" :graveyard "Graveyard" :battlefield "Battlefield"})


(defn- zone-card-pill
  "Card pill inside a zone with a click-to-return button."
  [card-id zone side]
  (let [card-def  (get cards/card-by-id card-id)
        card-name (or (:card/name card-def) (cljs.core/name card-id))]
    [:span {:class "inline-flex items-center gap-0.5 text-xs px-1.5 py-0.5 bg-surface-raised border border-border rounded cursor-pointer hover:bg-error hover:text-white"
            :title "Click to return to pool"
            :on-click #(rf/dispatch [::scenario-events/remove-from-zone
                                     {:side side :card-id card-id :zone zone}])}
     card-name
     [:span {:class "text-text-muted"} "×"]]))


(defn- zone-column
  "Display one zone (hand/graveyard/battlefield) with its cards."
  [zone cards side]
  [:div {:class "flex-1 min-w-0"}
   [:div {:class "text-xs font-semibold uppercase text-text-label mb-1"}
    (str (get zone-labels zone) " (" (count cards) ")")]
   (if (empty? cards)
     [:p {:class "text-text-muted text-xs italic"} "Empty"]
     [:div {:class "flex flex-wrap gap-1"}
      (map-indexed
        (fn [i card-id]
          ^{:key (str zone "-" i)}
          [zone-card-pill card-id zone side])
        cards)])])


(defn- pool-pill
  "Card pill in the pool. Clicking expands zone assignment buttons."
  [{:keys [card/id card/name count]} side selected-id set-selected!]
  (let [selected? (= id selected-id)]
    ^{:key id}
    [:div {:class "mb-1"}
     [:div {:class "flex items-center gap-1 flex-wrap"}
      [:button {:class (str "text-left text-xs px-2 py-0.5 rounded cursor-pointer "
                            (if selected?
                              "bg-accent text-surface font-bold"
                              "bg-surface-raised text-text hover:bg-surface-hover border border-border"))
                :on-click #(set-selected! (if selected? nil id))}
       (str count "x " name)]
      (when selected?
        (for [zone [:hand :graveyard :battlefield]]
          ^{:key zone}
          [:button {:class "text-xs px-2 py-0.5 rounded bg-surface-raised border border-border text-text hover:bg-accent hover:text-surface cursor-pointer"
                    :on-click (fn []
                                (rf/dispatch [::scenario-events/assign-to-zone
                                              {:side side :card-id id :zone zone}])
                                (set-selected! nil))}
           (get zone-labels zone)]))]]))


(defn- zone-distribution-panel
  "Full zone distribution section for one side.
   Shows card pool on the left and hand/graveyard/battlefield columns on the right."
  [side pool-sub zones-sub]
  (let [selected-id (r/atom nil)]
    (fn []
      (let [pool  @(rf/subscribe [pool-sub])
            zones @(rf/subscribe [zones-sub])]
        [:div {:class "mt-3 pt-3 border-t border-border"}
         [:div {:class "text-xs font-bold uppercase text-text-label mb-2"} "Zone Distribution"]
         [:div {:class "flex gap-3"}
          ;; Left: card pool
          [:div {:class "w-40 flex-shrink-0"}
           [:div {:class "text-xs font-semibold uppercase text-text-label mb-1"}
            "Pool"]
           (if (empty? pool)
             [:p {:class "text-text-muted text-xs italic"} "All assigned"]
             (for [entry pool]
               [pool-pill entry side @selected-id
                (fn [id] (reset! selected-id id))]))]
          ;; Right: zone columns
          [:div {:class "flex-1 flex gap-2"}
           (for [zone [:hand :graveyard :battlefield]]
             ^{:key zone}
             [zone-column zone (get zones zone []) side])]]]))))


;; === Player side ===

(defn- player-deck-selector
  "Dropdown of saved decks + built-in Iggy Pop for the player side."
  []
  (let [decks @(rf/subscribe [::subs-setup/available-decks])]
    [:div {:class "mb-3"}
     [:label {:class "text-text-label text-xs font-bold uppercase tracking-wider block mb-1"}
      "Select Deck"]
     [:select {:class "bg-surface-raised border border-border rounded px-2 py-1 text-sm text-text w-full"
               :value ""
               :on-change #(let [v (.. % -target -value)]
                             (when (seq v)
                               (let [deck-id (keyword v)
                                     deck    (if (= deck-id :iggy-pop)
                                               (:deck/main setup-events/iggy-pop-decklist)
                                               (let [imported @(rf/subscribe [::subs-setup/imported-decks])]
                                                 (:deck/main (get imported deck-id))))]
                                 (rf/dispatch [::scenario-events/select-player-deck deck])))
                             (set! (.. % -target -value) ""))}
      [:option {:value ""} "Load deck..."]
      (for [{:keys [deck/id deck/name]} decks]
        ^{:key id}
        [:option {:value (cljs.core/name id)} name])]]))


(defn- player-side
  "Player deck selector, deck contents panel, and zone distribution."
  []
  (let [grouped   @(rf/subscribe [::subs-scenario/player-deck-grouped])
        available @(rf/subscribe [::subs-scenario/available-cards])
        player    @(rf/subscribe [::subs-scenario/editing-player])]
    [:div {:class "p-3 border border-border rounded bg-surface"}
     [:h3 {:class "text-sm font-bold uppercase tracking-wider text-text-label mb-2"} "Player"]
     [player-deck-selector]
     [deck-panel (or grouped {}) :player available]
     (when (seq (:deck player))
       [zone-distribution-panel :player
        ::subs-scenario/player-zone-pool
        ::subs-scenario/player-zones])]))


;; === Opponent side ===

(defn- bot-archetype-selector
  "Dropdown of available bot archetypes."
  []
  (let [current-opp @(rf/subscribe [::subs-scenario/editing-opponent])]
    [:div {:class "mb-3"}
     [:label {:class "text-text-label text-xs font-bold uppercase tracking-wider block mb-1"}
      "Bot Archetype"]
     [:select {:class "bg-surface-raised border border-border rounded px-2 py-1 text-sm text-text w-full"
               :value (if-let [arch (:archetype current-opp)]
                        (cljs.core/name arch)
                        "goldfish")
               :on-change #(let [v (.. % -target -value)]
                             (when (seq v)
                               (rf/dispatch [::scenario-events/select-bot-archetype (keyword v)])))}
      (for [arch (bot-definitions/list-archetypes)]
        (let [spec (bot-definitions/get-spec arch)]
          ^{:key arch}
          [:option {:value (cljs.core/name arch)} (:bot/name spec)]))]]))


(defn- opponent-available-cards
  "Return available cards for opponent deck (not filtered by player deck)."
  [opponent-deck]
  (scenario-events/available-cards (or opponent-deck [])))


(defn- opponent-side
  "Opponent archetype selector, deck contents panel, and zone distribution."
  []
  (let [opp-grouped @(rf/subscribe [::subs-scenario/opponent-deck-grouped])
        opp-data    @(rf/subscribe [::subs-scenario/editing-opponent])
        available   (opponent-available-cards (:deck opp-data))]
    [:div {:class "p-3 border border-border rounded bg-surface"}
     [:h3 {:class "text-sm font-bold uppercase tracking-wider text-text-label mb-2"} "Opponent"]
     [bot-archetype-selector]
     [deck-panel (or opp-grouped {}) :opponent available]
     (when (seq (:deck opp-data))
       [zone-distribution-panel :opponent
        ::subs-scenario/opponent-zone-pool
        ::subs-scenario/opponent-zones])]))


;; === Builder view ===

(defn- builder-header
  []
  [:div {:class "flex items-center justify-between mb-4"}
   [:h2 {:class "text-lg font-bold text-text"} "Scenario Builder"]
   [:button {:class "px-3 py-1 text-sm border border-border rounded cursor-pointer bg-surface-raised text-text"
             :on-click #(rf/dispatch [::scenario-events/show-library])}
    "Back to Library"]])


(defn- builder-actions
  []
  (let [player-deck @(rf/subscribe [::subs-scenario/editing-player])]
    [:div {:class "flex justify-end mt-4"}
     [:button {:class (str "py-2 px-5 border border-border rounded font-bold text-sm "
                           (if (seq (:deck player-deck))
                             "cursor-pointer bg-btn-enabled-bg text-white"
                             "cursor-default bg-btn-disabled-bg text-border opacity-50"))
               :disabled (empty? (:deck player-deck))
               :on-click #(rf/dispatch [::scenario-events/play])}
      "Play Scenario"]]))


(defn builder-view
  "Scenario builder: two-column deck selection and mutation UI."
  []
  [:div {:class "p-4 max-w-4xl mx-auto"}
   [builder-header]
   [:div {:class "grid grid-cols-2 gap-4"}
    [player-side]
    [opponent-side]]
   [builder-actions]])


(defn scenarios-screen
  "Main scenarios screen: dispatch to library-view or builder-view based on active-view."
  []
  (let [active-view @(rf/subscribe [::subs-scenario/active-view])]
    [:div {:class "h-full overflow-y-auto"}
     (case active-view
       :builder [builder-view]
       [library-view])]))
