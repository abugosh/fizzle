(ns fizzle.views.scenarios
  "Scenario builder and library screens."
  (:require
    [fizzle.bots.definitions :as bot-definitions]
    [fizzle.events.scenario :as scenario-events]
    [fizzle.events.setup :as setup-events]
    [fizzle.subs.scenario :as subs-scenario]
    [fizzle.subs.setup :as subs-setup]
    [re-frame.core :as rf]))


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
  "Player deck selector and deck contents panel."
  []
  (let [grouped       @(rf/subscribe [::subs-scenario/player-deck-grouped])
        available     @(rf/subscribe [::subs-scenario/available-cards])]
    [:div {:class "p-3 border border-border rounded bg-surface"}
     [:h3 {:class "text-sm font-bold uppercase tracking-wider text-text-label mb-2"} "Player"]
     [player-deck-selector]
     [deck-panel (or grouped {}) :player available]]))


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
  "Opponent archetype selector and deck contents panel."
  []
  (let [opp-grouped @(rf/subscribe [::subs-scenario/opponent-deck-grouped])
        opp-data    @(rf/subscribe [::subs-scenario/editing-opponent])
        available   (opponent-available-cards (:deck opp-data))]
    [:div {:class "p-3 border border-border rounded bg-surface"}
     [:h3 {:class "text-sm font-bold uppercase tracking-wider text-text-label mb-2"} "Opponent"]
     [bot-archetype-selector]
     [deck-panel (or opp-grouped {}) :opponent available]]))


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
