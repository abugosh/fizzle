(ns fizzle.views.scenarios
  "Scenario builder and library screens."
  (:require
    [fizzle.bots.definitions :as bot-definitions]
    [fizzle.engine.cards :as cards]
    [fizzle.events.scenario :as scenario-events]
    [fizzle.events.setup :as setup-events]
    [fizzle.snapshot.events :as snapshot-events]
    [fizzle.subs.scenario :as subs-scenario]
    [fizzle.subs.setup :as subs-setup]
    [re-frame.core :as rf]
    [reagent.core :as r]))


(defn auto-summary
  "Pure function: generate a one-line summary string from a scenario map.
   Format: 'Player: [deck-size] cards, [N] in hand, [M] lands | Opponent: [archetype], [life] life'"
  [scenario]
  (let [player    (:scenario/player scenario)
        opponent  (:scenario/opponent scenario)
        deck      (or (:deck player) [])
        deck-size (reduce + 0 (map :count deck))
        zones     (or (:zones player) {})
        hand-ids  (or (:hand zones) [])
        bf-ids    (or (:battlefield zones) [])
        ;; Count lands on battlefield by looking up card types from registry
        land-count (count (filter (fn [cid]
                                    (let [card-def (get cards/card-by-id cid)]
                                      (contains? (or (:card/types card-def) #{}) :land)))
                                  bf-ids))
        archetype  (or (:archetype opponent) :goldfish)
        opp-life   (or (:life opponent) 20)]
    (str "Player: " deck-size " cards, " (count hand-ids) " in hand, " land-count " lands"
         " | Opponent: " (name archetype) ", " opp-life " life")))


(defn- scenario-card
  "Render a single scenario row with title, summary, and action buttons."
  [scenario]
  (let [id    (:scenario/id scenario)
        title (or (:scenario/title scenario) "Untitled")
        summary (auto-summary scenario)]
    [:div {:key (str id)
           :class "border border-border rounded bg-surface p-3 mb-2"}
     [:div {:class "flex items-start justify-between gap-2"}
      [:div {:class "flex-1 min-w-0"}
       [:div {:class "font-semibold text-text text-sm mb-0.5"} title]
       [:div {:class "text-text-muted text-xs truncate"} summary]]
      [:div {:class "flex gap-1 flex-shrink-0"}
       [:button {:class "px-2 py-1 text-xs rounded bg-btn-enabled-bg text-white font-bold cursor-pointer"
                 :title "Play this scenario"
                 :on-click #(rf/dispatch [::scenario-events/quick-play scenario])}
        "Play"]
       [:button {:class "px-2 py-1 text-xs rounded bg-surface-raised border border-border text-text cursor-pointer"
                 :title "Edit this scenario"
                 :on-click #(rf/dispatch [::scenario-events/edit-existing scenario])}
        "Edit"]
       [:button {:class "px-2 py-1 text-xs rounded bg-surface-raised border border-border text-text cursor-pointer"
                 :title "Copy share link"
                 :on-click #(rf/dispatch [::snapshot-events/copy-scenario-share-link scenario])}
        "Share"]
       [:button {:class "px-2 py-1 text-xs rounded border border-error text-error cursor-pointer hover:bg-error hover:text-white"
                 :title "Delete this scenario"
                 :on-click #(when (js/confirm (str "Delete \"" title "\"?"))
                              (rf/dispatch [::scenario-events/delete id]))}
        "Delete"]]]]))


(defn library-view
  "Display saved scenarios with play, edit, share, and delete actions."
  []
  (let [scenarios @(rf/subscribe [::subs-scenario/scenario-list])]
    [:div {:class "p-4 max-w-4xl mx-auto"}
     [:div {:class "flex items-center justify-between mb-4"}
      [:h2 {:class "text-lg font-bold text-text"} "Scenarios"]
      [:button {:class "px-4 py-2 bg-accent text-surface rounded font-bold cursor-pointer text-sm"
                :on-click #(do
                             (rf/dispatch [::scenario-events/set-editing nil])
                             (rf/dispatch [::scenario-events/show-builder]))}
       "New Scenario"]]
     (if (empty? scenarios)
       [:div {:class "text-center py-12"}
        [:p {:class "text-text-muted mb-4"}
         "No scenarios saved yet. Create one or load from a shared link."]
        [:button {:class "px-4 py-2 bg-accent text-surface rounded font-bold cursor-pointer"
                  :on-click #(do
                               (rf/dispatch [::scenario-events/set-editing nil])
                               (rf/dispatch [::scenario-events/show-builder]))}
         "New Scenario"]]
       [:div
        (for [scenario scenarios]
          ^{:key (str (:scenario/id scenario))}
          [scenario-card scenario])])]))


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


(defn- library-top-card-pill
  "Card pill in the library-top sequence with remove and reorder buttons."
  [card-id index count side]
  (let [card-def  (get cards/card-by-id card-id)
        card-name (or (:card/name card-def) (cljs.core/name card-id))]
    [:div {:key (str "lib-" index) :class "inline-flex items-center gap-0.5 text-xs px-1.5 py-0.5 bg-accent text-surface rounded border border-border"}
     [:span {:class "font-bold"} (str (inc index) ".")] ; 1-based for display
     [:span card-name]
     ;; Up button
     (when (pos? index)
       [:button {:class "text-text hover:text-error cursor-pointer"
                 :title "Move up (earlier draw)"
                 :on-click #(rf/dispatch [::scenario-events/reorder-library-top
                                          {:side side :from-index index :to-index (dec index)}])}
        "↑"])
     ;; Down button
     (when (< index (dec count))
       [:button {:class "text-text hover:text-error cursor-pointer"
                 :title "Move down (later draw)"
                 :on-click #(rf/dispatch [::scenario-events/reorder-library-top
                                          {:side side :from-index index :to-index (inc index)}])}
        "↓"])
     ;; Remove button
     [:button {:class "text-text-muted hover:text-error cursor-pointer"
               :title "Return to pool"
               :on-click #(rf/dispatch [::scenario-events/remove-from-library-top
                                        {:side side :index index}])}
      "×"]]))


(defn- library-top-panel
  "Display library-top cards in order with position numbers and reorder buttons."
  [side lib-top-sub unordered-pool-sub]
  (fn []
    (let [lib-top @(rf/subscribe [lib-top-sub])
          unord-pool @(rf/subscribe [unordered-pool-sub])]
      [:div {:class "mt-3 pt-3 border-t border-border"}
       [:div {:class "text-xs font-bold uppercase text-text-label mb-2"} "Library Top Cards"]
       (if (empty? lib-top)
         [:p {:class "text-text-muted text-xs italic mb-2"} "No cards ordered yet"]
         [:div {:class "flex flex-wrap gap-1 mb-2"}
          (map-indexed
            (fn [i card-id]
              ^{:key (str "lib-" i)}
              [library-top-card-pill card-id i (count lib-top) side])
            lib-top)])
       ;; Unordered pool (clickable to add)
       [:div {:class "text-xs font-semibold uppercase text-text-label mb-1"} "Available"]
       (if (empty? unord-pool)
         [:p {:class "text-text-muted text-xs italic"} "All assigned or ordered"]
         [:div {:class "flex flex-wrap gap-1"}
          (for [{:keys [card/id card/name count]} unord-pool]
            ^{:key id}
            [:button {:class "text-xs px-2 py-0.5 rounded bg-surface-raised border border-border text-text hover:bg-accent hover:text-surface cursor-pointer"
                      :on-click #(rf/dispatch [::scenario-events/add-to-library-top
                                               {:side side :card-id id}])}
             (str count "x " name)])])])))


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
  "Player deck selector, deck contents panel, zone distribution, and library-top."
  []
  (let [grouped   @(rf/subscribe [::subs-scenario/player-deck-grouped])
        available @(rf/subscribe [::subs-scenario/available-cards])
        player    @(rf/subscribe [::subs-scenario/editing-player])]
    [:div {:class "p-3 border border-border rounded bg-surface"}
     [:h3 {:class "text-sm font-bold uppercase tracking-wider text-text-label mb-2"} "Player"]
     [player-deck-selector]
     [deck-panel (or grouped {}) :player available]
     (when (seq (:deck player))
       [:<>
        [zone-distribution-panel :player
         ::subs-scenario/player-zone-pool
         ::subs-scenario/player-zones]
        [library-top-panel :player
         ::subs-scenario/player-library-top
         ::subs-scenario/player-unordered-pool]])]))


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


(defn- opponent-side
  "Opponent archetype selector, deck contents panel, zone distribution, and library-top."
  []
  (let [opp-grouped @(rf/subscribe [::subs-scenario/opponent-deck-grouped])
        opp-data    @(rf/subscribe [::subs-scenario/editing-opponent])
        available   @(rf/subscribe [::subs-scenario/opponent-available-cards])]
    [:div {:class "p-3 border border-border rounded bg-surface"}
     [:h3 {:class "text-sm font-bold uppercase tracking-wider text-text-label mb-2"} "Opponent"]
     [bot-archetype-selector]
     [deck-panel (or opp-grouped {}) :opponent available]
     (when (seq (:deck opp-data))
       [:<>
        [zone-distribution-panel :opponent
         ::subs-scenario/opponent-zone-pool
         ::subs-scenario/opponent-zones]
        [library-top-panel :opponent
         ::subs-scenario/opponent-library-top
         ::subs-scenario/opponent-unordered-pool]])]))


;; === Game state configuration ===

(def ^:private phase-options
  "Available game phases for scenario start."
  [:upkeep :draw :main1 :combat :main2 :end])


(defn- game-state-config-panel
  "Configuration for title, life totals, mana pools, and starting phase."
  []
  (let [editing @(rf/subscribe [::subs-scenario/editing])]
    [:div {:class "p-3 border border-border rounded bg-surface"}
     [:h3 {:class "text-sm font-bold uppercase tracking-wider text-text-label mb-3"} "Game State"]

     ;; Title input
     [:div {:class "mb-3"}
      [:label {:class "text-text-label text-xs font-bold uppercase tracking-wider block mb-1"}
       "Scenario Title"]
      [:input {:type "text"
               :class "bg-surface-raised border border-border rounded px-2 py-1 text-sm text-text w-full"
               :placeholder "Required to save"
               :value (or (:scenario/title editing) "")
               :on-change #(rf/dispatch [::scenario-events/set-title (.. % -target -value)])}]]

     ;; Life totals
     [:div {:class "grid grid-cols-2 gap-3 mb-3"}
      [:div
       [:label {:class "text-text-label text-xs font-bold uppercase tracking-wider block mb-1"}
        "Player Life"]
       [:input {:type "number"
                :class "bg-surface-raised border border-border rounded px-2 py-1 text-sm text-text w-full"
                :value (or (get-in editing [:scenario/player :life]) 20)
                :on-change #(let [val (js/parseInt (.. % -target -value))]
                              (when (not (js/isNaN val))
                                (rf/dispatch [::scenario-events/set-life
                                              {:side :player :life (max 0 val)}])))}]]
      [:div
       [:label {:class "text-text-label text-xs font-bold uppercase tracking-wider block mb-1"}
        "Opponent Life"]
       [:input {:type "number"
                :class "bg-surface-raised border border-border rounded px-2 py-1 text-sm text-text w-full"
                :value (or (get-in editing [:scenario/opponent :life]) 20)
                :on-change #(let [val (js/parseInt (.. % -target -value))]
                              (when (not (js/isNaN val))
                                (rf/dispatch [::scenario-events/set-life
                                              {:side :opponent :life (max 0 val)}])))}]]]

     ;; Mana pools
     [:div {:class "mb-3"}
      [:h4 {:class "text-text-label text-xs font-bold uppercase tracking-wider mb-2"} "Mana Pools"]
      ;; Player mana
      [:div {:class "mb-2"}
       [:div {:class "text-xs font-semibold text-text-label mb-1"} "Player"]
       [:div {:class "grid grid-cols-6 gap-1"}
        (for [color [:white :blue :black :red :green :colorless]]
          ^{:key (str "player-" color)}
          [:div
           [:label {:class "text-text-label text-xs font-bold uppercase block mb-0.5"
                    :style {:font-size "0.65rem"}}
            (subs (str color) 0 1)]
           [:input {:type "number"
                    :class "bg-surface-raised border border-border rounded px-1 py-0.5 text-xs text-text w-full"
                    :value (or (get-in editing [:scenario/player :mana-pool color]) 0)
                    :on-change #(let [val (js/parseInt (.. % -target -value))]
                                  (when (not (js/isNaN val))
                                    (rf/dispatch [::scenario-events/set-mana
                                                  {:side :player :color color :amount (max 0 val)}])))}]])]]
      ;; Opponent mana
      [:div
       [:div {:class "text-xs font-semibold text-text-label mb-1"} "Opponent"]
       [:div {:class "grid grid-cols-6 gap-1"}
        (for [color [:white :blue :black :red :green :colorless]]
          ^{:key (str "opponent-" color)}
          [:div
           [:label {:class "text-text-label text-xs font-bold uppercase block mb-0.5"
                    :style {:font-size "0.65rem"}}
            (subs (str color) 0 1)]
           [:input {:type "number"
                    :class "bg-surface-raised border border-border rounded px-1 py-0.5 text-xs text-text w-full"
                    :value (or (get-in editing [:scenario/opponent :mana-pool color]) 0)
                    :on-change #(let [val (js/parseInt (.. % -target -value))]
                                  (when (not (js/isNaN val))
                                    (rf/dispatch [::scenario-events/set-mana
                                                  {:side :opponent :color color :amount (max 0 val)}])))}]])]]]

     ;; Phase selector
     [:div
      [:label {:class "text-text-label text-xs font-bold uppercase tracking-wider block mb-1"}
       "Starting Phase"]
      [:select {:class "bg-surface-raised border border-border rounded px-2 py-1 text-sm text-text w-full"
                :value (or (:scenario/phase editing) "main1")
                :on-change #(rf/dispatch [::scenario-events/set-phase (keyword (.. % -target -value))])}
       (for [phase phase-options]
         ^{:key phase}
         [:option {:value (cljs.core/name phase)}
          (str (cljs.core/name phase))])]]]))


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
  (let [editing @(rf/subscribe [::subs-scenario/editing])
        player-deck (:deck (:scenario/player editing))
        title (:scenario/title editing)
        has-deck? (seq player-deck)
        has-title? (seq title)]
    [:div {:class "flex gap-3 justify-end mt-4"}
     [:button {:class "px-3 py-1 text-sm border border-border rounded cursor-pointer bg-surface-raised text-text"
               :on-click #(rf/dispatch [::scenario-events/show-library])}
      "Back to Library"]
     [:button {:class (str "py-2 px-5 border border-border rounded font-bold text-sm "
                           (if has-title?
                             "cursor-pointer bg-accent text-surface"
                             "cursor-default bg-btn-disabled-bg text-border opacity-50"))
               :disabled (not has-title?)
               :on-click #(rf/dispatch [::scenario-events/save editing])}
      "Save"]
     [:button {:class (str "py-2 px-5 border border-border rounded font-bold text-sm "
                           (if has-deck?
                             "cursor-pointer bg-btn-enabled-bg text-white"
                             "cursor-default bg-btn-disabled-bg text-border opacity-50"))
               :disabled (not has-deck?)
               :on-click #(rf/dispatch [::scenario-events/play])}
      "Play Scenario"]]))


(defn builder-view
  "Scenario builder: two-column deck selection and mutation UI."
  []
  [:div {:class "p-4 max-w-6xl mx-auto"}
   [builder-header]
   [:div {:class "grid grid-cols-3 gap-4"}
    [:div {:class "col-span-2"}
     [:div {:class "grid grid-cols-2 gap-4"}
      [player-side]
      [opponent-side]]]
    [:div
     [game-state-config-panel]]]
   [builder-actions]])


(defn save-from-game-modal
  "Modal for saving the current game position as a scenario.
   Shows title input and Save/Cancel buttons."
  []
  (let [visible? @(rf/subscribe [::subs-scenario/save-modal-visible])
        title @(rf/subscribe [::subs-scenario/save-modal-title])]
    (when visible?
      [:div {:class "fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"}
       [:div {:class "bg-surface rounded border border-border p-6 max-w-md w-full mx-4"}
        [:h2 {:class "text-lg font-bold text-text mb-4"} "Save Position as Scenario"]
        [:div {:class "mb-4"}
         [:label {:class "text-sm text-text-label block mb-2"} "Scenario Title"]
         [:input {:class "w-full px-3 py-2 border border-border rounded bg-surface-raised text-text placeholder-text-muted"
                  :placeholder "Enter scenario title..."
                  :value title
                  :on-change #(rf/dispatch [::scenario-events/update-save-modal-title (.. % -target -value)])}]]
        [:div {:class "flex gap-3 justify-end"}
         [:button {:class "px-4 py-2 border border-border rounded cursor-pointer bg-surface-raised text-text hover:bg-surface-hover"
                   :on-click #(rf/dispatch [::scenario-events/show-save-modal])}
          "Cancel"]
         [:button {:class (str "px-4 py-2 rounded font-bold text-sm "
                               (if (seq title)
                                 "cursor-pointer bg-accent text-surface"
                                 "cursor-default bg-btn-disabled-bg text-border opacity-50"))
                   :disabled (not (seq title))
                   :on-click #(rf/dispatch [::scenario-events/save-from-game title])}
          "Save"]]]])))


(defn scenarios-screen
  "Main scenarios screen: dispatch to library-view or builder-view based on active-view.
   Also renders the save-from-game modal when visible."
  []
  (let [active-view @(rf/subscribe [::subs-scenario/active-view])]
    [:div {:class "h-full overflow-y-auto"}
     (case active-view
       :builder [builder-view]
       [library-view])
     [save-from-game-modal]]))
