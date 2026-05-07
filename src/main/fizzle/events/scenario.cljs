(ns fizzle.events.scenario
  "Re-frame event handlers for scenario CRUD and scenario play.

   Scenarios are saved as a map {id → scenario} in localStorage and in
   app-db under :scenario/library.

   init-from-scenario builds a playable Datascript game from a scenario config,
   reusing the same building blocks as init-game-state and restore-game-state
   (ADR-035: parallel paths, shared callees)."
  (:require
    [datascript.core :as d]
    [fizzle.bots.protocol :as bot-protocol]
    [fizzle.db.game-state :as game-state]
    [fizzle.db.schema :refer [schema]]
    [fizzle.db.storage :as storage]
    [fizzle.engine.cards :as cards]
    [fizzle.engine.objects :as objects]
    [re-frame.core :as rf]))


;; === Persistence interceptor ===

(def ^:private save-scenarios-interceptor
  (rf/after
    (fn [db]
      (storage/save-scenarios! (:scenario/library db)))))


;; === init-from-scenario helpers ===

(defn- get-card-eid-in-db
  "Look up a card's entity ID by its :card/id keyword."
  [db card-id]
  (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id))


(defn- build-zone-objects!
  "Transact objects for each card-id in zone-card-ids into the given zone.
   Returns sequence of resolved card-ids actually transacted (skips unknown cards).
   position is used only for :library zone (0 for all others)."
  [conn player-eid zone card-ids start-position]
  (let [db @conn
        indexed (map-indexed vector card-ids)]
    (reduce
      (fn [transacted [i card-id]]
        (let [card-eid (get-card-eid-in-db db card-id)]
          (if card-eid
            (let [position (if (= zone :library) (+ start-position i) 0)
                  card-data (d/pull db [:card/types :card/power :card/toughness
                                        :card/triggers :card/replacement-effects] card-eid)
                  obj-tx (objects/build-object-tx db card-eid card-data zone player-eid position)]
              (d/transact! conn [obj-tx])
              (conj transacted card-id))
            (do
              (js/console.warn "init-from-scenario: unknown card-id, skipping" (str card-id))
              transacted))))
      []
      indexed)))


(defn- expand-deck-card-ids
  "Expand a deck list [{:card/id kw :count n}] into a flat vector of card-id keywords."
  [deck]
  (into []
        (mapcat (fn [{:keys [card/id count]}] (repeat count id)))
        deck))


(defn- compute-library-card-ids
  "Given the full deck card-ids, zone-assigned card-ids, and library-top card-ids,
   return [ordered-top remaining-shuffled] where:
   - ordered-top: first N library-top ids that are available in the remaining pool
   - remaining-shuffled: the rest of the pool, shuffled, positioned after ordered-top

   A card is 'available' for library-top if it is present in the remaining pool
   (i.e. not assigned to a zone). We consume one copy per library-top entry."
  [deck-ids zone-ids library-top-ids]
  (let [;; Remove zone-assigned cards from deck to get remaining pool
        remaining (reduce
                    (fn [pool zone-card-id]
                      (let [idx (.indexOf pool zone-card-id)]
                        (if (neg? idx)
                          pool
                          (into (subvec pool 0 idx) (subvec pool (inc idx))))))
                    (vec deck-ids)
                    zone-ids)
        ;; Consume library-top entries from remaining pool
        [top-ids after-top] (reduce
                              (fn [[tops pool] lib-top-id]
                                (let [idx (.indexOf pool lib-top-id)]
                                  (if (neg? idx)
                                    ;; Not available in pool — clamp: skip it
                                    [tops pool]
                                    [(conj tops lib-top-id)
                                     (into (subvec pool 0 idx) (subvec pool (inc idx)))])))
                              [[] (vec remaining)]
                              library-top-ids)]
    [top-ids (shuffle after-top)]))


(defn- init-player-side!
  "Build all objects for one player side:
   - zone objects (hand, graveyard, battlefield) in specified positions
   - library: library-top (positions 0..N-1) then shuffled remainder

   Returns nothing — side effects only (d/transact! calls)."
  [conn player-eid player-config]
  (let [{:keys [deck zones library-top]} player-config
        zone-map (or zones {})
        hand-ids (or (:hand zone-map) [])
        graveyard-ids (or (:graveyard zone-map) [])
        battlefield-ids (or (:battlefield zone-map) [])
        library-top-ids (or library-top [])
        all-zone-ids (concat hand-ids graveyard-ids battlefield-ids)
        deck-ids (expand-deck-card-ids deck)
        [top-ids rest-shuffled] (compute-library-card-ids deck-ids all-zone-ids library-top-ids)
        library-ids (concat top-ids rest-shuffled)]
    ;; Transact each zone's objects
    (build-zone-objects! conn player-eid :hand hand-ids 0)
    (build-zone-objects! conn player-eid :graveyard graveyard-ids 0)
    (build-zone-objects! conn player-eid :battlefield battlefield-ids 0)
    ;; Transact library with correct ordering (top-ids first, then shuffled rest)
    (build-zone-objects! conn player-eid :library library-ids 0)))


;; === Public API ===

(defn init-from-scenario
  "Build a playable Datascript game from a scenario config map.

   Scenario map keys:
     :scenario/player   — map with :deck, :zones {:hand :graveyard :battlefield},
                          :library-top, :mana-pool, :life
     :scenario/opponent — map with :archetype, :deck, :zones, :library-top,
                          :mana-pool, :life
     :scenario/phase    — game phase keyword (e.g. :main1)

   Returns an app-db map with :game/db, :active-screen :game (skips opening-hand),
   and history/* keys. Parallel to init-game-state and restore-game-state (ADR-035)."
  [scenario]
  (let [player-cfg  (:scenario/player scenario)
        opp-cfg     (:scenario/opponent scenario)
        phase       (or (:scenario/phase scenario) :main1)
        bot-arch    (or (:archetype opp-cfg) :goldfish)
        ;; Build the opponent's deck using the bot's default deck when none specified
        opp-deck    (if (seq (:deck opp-cfg))
                      (:deck opp-cfg)
                      (bot-protocol/bot-deck bot-arch))
        opp-cfg'    (assoc opp-cfg :deck opp-deck)
        conn        (d/create-conn schema)
        _           (d/transact! conn cards/all-cards)
        ;; Create players (entity + turn-based triggers, via shared callee)
        player-eid  (game-state/create-complete-player
                      conn game-state/human-player-id
                      {:player/name      "Player"
                       :player/life      (or (:life player-cfg) 20)
                       :player/mana-pool (merge game-state/empty-mana-pool
                                                (:mana-pool player-cfg))})
        opp-eid     (game-state/create-complete-player
                      conn game-state/opponent-player-id
                      {:player/name          "Opponent"
                       :player/is-opponent   true
                       :player/bot-archetype bot-arch
                       :player/life          (or (:life opp-cfg) 20)
                       :player/mana-pool     (merge game-state/empty-mana-pool
                                                    (:mana-pool opp-cfg))})
        stops       (storage/load-stops)]
    ;; Build objects for each player side
    (init-player-side! conn player-eid player-cfg)
    (init-player-side! conn opp-eid opp-cfg')
    ;; Transact game entity (via shared callee)
    (d/transact! conn (game-state/create-game-entity-tx player-eid {:game/phase phase}))
    ;; Load stops from localStorage (same pattern as init-game-state)
    (d/transact! conn [[:db/add player-eid :player/stops (:player stops)]
                       [:db/add player-eid :player/opponent-stops (or (:opponent-stops stops) #{})]
                       [:db/add opp-eid    :player/stops (bot-protocol/bot-stops bot-arch)]])
    {:game/db               @conn
     :active-screen         :game
     :game/game-over-dismissed false
     :ui/stack-collapsed    false
     :ui/gy-collapsed       false
     :ui/history-collapsed  false
     :history/main          []
     :history/forks         {}
     :history/current-branch nil
     :history/position      -1}))


;; === Extract current position from game state ===

(defn- get-objects-in-zone
  "Query all objects in a given zone for a player.
   Returns a vector of card-ids."
  [db player-id zone]
  (let [player-eid (d/q '[:find ?e . :in $ ?pid :where [?e :player/id ?pid]] db player-id)]
    (if player-eid
      (into []
            (d/q '[:find [?cid ...]
                   :in $ ?owner ?zone
                   :where [?obj :object/zone ?zone]
                   [?obj :object/owner ?owner]
                   [?obj :object/card ?card]
                   [?card :card/id ?cid]]
                 db player-eid zone))
      [])))


(defn- count-card-ids-in-sequence
  "Given a sequence of card-id keywords, return a vector of
   {:card/id kw :count n} maps, sorted by card-id."
  [card-ids]
  (let [counts (reduce
                 (fn [acc id]
                   (update acc id (fnil inc 0)))
                 {}
                 card-ids)]
    (into []
          (map (fn [[id count]]
                 {:card/id id :count count}))
          (sort-by first counts))))


(defn- get-player-mana-pool
  "Query the mana pool for a player."
  [db player-id]
  (let [player-eid (d/q '[:find ?e . :in $ ?pid :where [?e :player/id ?pid]] db player-id)]
    (if player-eid
      (d/pull db [:player/mana-pool] player-eid)
      {})))


(defn- get-player-life
  "Query the life total for a player."
  [db player-id]
  (let [player-eid (d/q '[:find ?e . :in $ ?pid :where [?e :player/id ?pid]] db player-id)]
    (if player-eid
      (:player/life (d/pull db [:player/life] player-eid))
      20)))


(defn- get-game-phase
  "Query the current game phase."
  [db]
  (d/q '[:find ?phase . :where [?e :game/phase ?phase]] db))


(defn extract-scenario-from-game
  "Extract current board state from game-db and return a scenario config map.
   Captures: player and opponent decks (reconstructed from all zones),
   zones (hand, graveyard, battlefield), mana pools, life, phase.

   Library-top is left empty (can't reconstruct order from live game).
   Bot archetype comes from opponent's :player/bot-archetype."
  [game-db title]
  (let [player-id game-state/human-player-id
        opp-id game-state/opponent-player-id
        ;; Get zone contents for both players
        player-hand (get-objects-in-zone game-db player-id :hand)
        player-gy (get-objects-in-zone game-db player-id :graveyard)
        player-bf (get-objects-in-zone game-db player-id :battlefield)
        opp-hand (get-objects-in-zone game-db opp-id :hand)
        opp-gy (get-objects-in-zone game-db opp-id :graveyard)
        opp-bf (get-objects-in-zone game-db opp-id :battlefield)
        ;; Get stack items (cards that are on stack) for each player
        ;; Resolve player EIDs once to avoid per-zone lookups
        player-eid (d/q '[:find ?e . :in $ ?pid :where [?e :player/id ?pid]] game-db player-id)
        opp-eid    (d/q '[:find ?e . :in $ ?pid :where [?e :player/id ?pid]] game-db opp-id)
        player-stack-items (if player-eid
                             (d/q '[:find [?cid ...]
                                    :in $ ?owner
                                    :where [?obj :object/zone :stack]
                                    [?obj :object/owner ?owner]
                                    [?obj :object/card ?card]
                                    [?card :card/id ?cid]]
                                  game-db player-eid)
                             [])
        opp-stack-items (if opp-eid
                          (d/q '[:find [?cid ...]
                                 :in $ ?owner
                                 :where [?obj :object/zone :stack]
                                 [?obj :object/owner ?owner]
                                 [?obj :object/card ?card]
                                 [?card :card/id ?cid]]
                               game-db opp-eid)
                          [])
        ;; Get library and exile cards for deck reconstruction
        player-library (get-objects-in-zone game-db player-id :library)
        player-exile (get-objects-in-zone game-db player-id :exile)
        opp-library (get-objects-in-zone game-db opp-id :library)
        opp-exile (get-objects-in-zone game-db opp-id :exile)
        ;; Reconstruct deck from ALL cards in all zones
        all-player-cards (concat player-hand player-gy player-bf player-stack-items
                                 player-library player-exile)
        all-opp-cards (concat opp-hand opp-gy opp-bf opp-stack-items
                              opp-library opp-exile)
        ;; Get mana pools and life
        player-mana (:player/mana-pool (get-player-mana-pool game-db player-id))
        player-life (get-player-life game-db player-id)
        opp-mana (:player/mana-pool (get-player-mana-pool game-db opp-id))
        opp-life (get-player-life game-db opp-id)
        ;; Get phase
        phase (or (get-game-phase game-db) :main1)
        ;; Get opponent archetype (reuse opp-eid resolved above)
        opp-archetype (if opp-eid
                        (:player/bot-archetype (d/pull game-db [:player/bot-archetype] opp-eid))
                        :goldfish)]
    {:scenario/id (random-uuid)
     :scenario/title title
     :scenario/player {:deck (count-card-ids-in-sequence all-player-cards)
                       :zones {:hand player-hand
                               :graveyard player-gy
                               :battlefield player-bf}
                       :library-top []
                       :mana-pool (or player-mana {})
                       :life player-life}
     :scenario/opponent {:archetype opp-archetype
                         :deck (count-card-ids-in-sequence all-opp-cards)
                         :zones {:hand opp-hand
                                 :graveyard opp-gy
                                 :battlefield opp-bf}
                         :library-top []
                         :mana-pool (or opp-mana {})
                         :life opp-life}
     :scenario/phase phase}))


;; === Pure handler functions (exported for direct testing) ===

(defn load-all-handler
  "Populate :scenario/library from localStorage. Idempotent — always overwrites
   from storage so that a refresh picks up any persisted data."
  [db _]
  (assoc db :scenario/library (storage/load-scenarios)))


(defn save-handler
  "Upsert scenario into :scenario/library. Uses :scenario/id as the key."
  [db [_ scenario]]
  (let [id (:scenario/id scenario)]
    (assoc-in db [:scenario/library id] scenario)))


(defn delete-handler
  "Remove scenario with given id from :scenario/library."
  [db [_ id]]
  (update db :scenario/library dissoc id))


(defn update-field-handler
  "Update a nested field on :scenario/editing.
   path is a vector of keys relative to :scenario/editing."
  [db [_ path value]]
  (assoc-in db (into [:scenario/editing] path) value))


(defn set-editing-handler
  "Set :scenario/editing to scenario (a full scenario map, or nil to clear)."
  [db [_ scenario]]
  (assoc db :scenario/editing scenario))


;; === re-frame event registrations ===

(rf/reg-event-db
  ::load-all
  load-all-handler)


(rf/reg-event-db
  ::save
  [save-scenarios-interceptor]
  save-handler)


(rf/reg-event-db
  ::delete
  [save-scenarios-interceptor]
  delete-handler)


(rf/reg-event-db
  ::update-field
  update-field-handler)


(rf/reg-event-db
  ::set-editing
  set-editing-handler)


(defn edit-existing-handler
  "Load scenario into :scenario/editing and navigate to builder.
   Composite of set-editing + show-builder."
  [db [_ scenario]]
  (-> db
      (assoc :scenario/editing scenario)
      (assoc :scenario/active-view :builder)))


(defn quick-play-handler
  "Load scenario into :scenario/editing then play it immediately.
   Composite of set-editing + play."
  [db [_ scenario]]
  (let [db' (assoc db :scenario/editing scenario)]
    (merge db' (init-from-scenario scenario))))


(rf/reg-event-db
  ::edit-existing
  edit-existing-handler)


(rf/reg-event-db
  ::quick-play
  quick-play-handler)


(rf/reg-event-db
  ::show-library
  (fn [db _]
    (assoc db :scenario/active-view :library)))


(rf/reg-event-db
  ::show-builder
  (fn [db _]
    (let [db' (assoc db :scenario/active-view :builder)]
      (if (:scenario/editing db')
        db'
        (assoc db' :scenario/editing
               {:scenario/opponent {:archetype :goldfish
                                    :deck (bot-protocol/bot-deck :goldfish)}})))))


(rf/reg-event-db
  ::play
  (fn [db _]
    (let [scenario (:scenario/editing db)]
      (merge db (init-from-scenario scenario)))))


;; === Deck selection and mutation helpers ===

(defn- basic-land?
  "Return true if the card identified by card-id is a basic land.
   Uses the card registry to look up :card/supertypes."
  [card-id]
  (let [card-def (get cards/card-by-id card-id)]
    (contains? (or (:card/supertypes card-def) #{}) :basic)))


(defn- deck-count-for
  "Return the current count of card-id in deck, or 0 if absent."
  [deck card-id]
  (or (some #(when (= card-id (:card/id %)) (:count %)) deck) 0))


(defn- update-deck-count
  "Update the count of card-id in deck vector.
   delta is +1 or -1. Removes entry when count reaches 0.
   Creates entry with count 1 if not present and delta is positive."
  [deck card-id delta]
  (let [existing (some #(when (= card-id (:card/id %)) %) deck)]
    (if existing
      (let [new-count (+ (:count existing) delta)]
        (if (pos? new-count)
          (mapv (fn [entry]
                  (if (= card-id (:card/id entry))
                    (assoc entry :count new-count)
                    entry))
                deck)
          (vec (remove #(= card-id (:card/id %)) deck))))
      (if (pos? delta)
        (conj (or deck []) {:card/id card-id :count delta})
        (or deck [])))))


(defn- side-key
  "Convert :player/:opponent side to the scenario key."
  [side]
  (if (= side :player) :scenario/player :scenario/opponent))


;; === Pure handler functions for deck selection and mutation ===

(defn select-player-deck-handler
  "Set the player deck on :scenario/editing.
   deck is a vector of {:card/id kw :count n} maps."
  [db [_ deck]]
  (assoc-in db [:scenario/editing :scenario/player :deck] deck))


(defn select-bot-archetype-handler
  "Set the opponent archetype and populate its deck on :scenario/editing."
  [db [_ archetype]]
  (let [deck (bot-protocol/bot-deck archetype)]
    (-> db
        (assoc-in [:scenario/editing :scenario/opponent :archetype] archetype)
        (assoc-in [:scenario/editing :scenario/opponent :deck] deck))))


(defn add-card-handler
  "Increment count for card-id in the given side's deck.
   Respects the 4-copy limit for non-basic cards (basics are unlimited).
   {:side :player/:opponent :card-id keyword}"
  [db [_ {:keys [side card-id]}]]
  (let [sk    (side-key side)
        deck  (get-in db [:scenario/editing sk :deck] [])
        count (deck-count-for deck card-id)
        at-limit? (and (>= count 4) (not (basic-land? card-id)))]
    (if at-limit?
      db
      (assoc-in db [:scenario/editing sk :deck]
                (update-deck-count deck card-id 1)))))


(defn remove-card-handler
  "Decrement count for card-id in the given side's deck.
   Removes the entry when count reaches 0. No-op when card absent.
   {:side :player/:opponent :card-id keyword}"
  [db [_ {:keys [side card-id]}]]
  (let [sk   (side-key side)
        deck (get-in db [:scenario/editing sk :deck] [])]
    (assoc-in db [:scenario/editing sk :deck]
              (update-deck-count deck card-id -1))))


(defn available-cards
  "Return all registry cards that can still be added to a deck.
   Excludes non-basic cards already at 4 copies.
   deck is a vector of {:card/id kw :count n} maps.
   Returns a vector of {:card/id kw :card/name str} maps."
  [deck]
  (let [counts (into {} (map (juxt :card/id :count) deck))]
    (into []
          (keep (fn [card-def]
                  (let [id    (:card/id card-def)
                        cnt   (get counts id 0)
                        basic (contains? (or (:card/supertypes card-def) #{}) :basic)]
                    (when (or basic (< cnt 4))
                      {:card/id id :card/name (:card/name card-def)}))))
          cards/all-cards)))


;; === re-frame event registrations for deck selection/mutation ===

(rf/reg-event-db
  ::select-player-deck
  select-player-deck-handler)


(rf/reg-event-db
  ::select-bot-archetype
  select-bot-archetype-handler)


(rf/reg-event-db
  ::add-card
  add-card-handler)


(rf/reg-event-db
  ::remove-card
  remove-card-handler)


;; === Zone assignment handlers ===

(defn assign-to-zone-handler
  "Move one copy of card-id from the deck pool into zone for side.
   Pool = deck minus already-zone-assigned cards.
   Guards: no-op if no copies remain in pool.
   {:side :player/:opponent :card-id keyword :zone keyword}"
  [db [_ {:keys [side card-id zone]}]]
  (let [sk          (side-key side)
        deck        (get-in db [:scenario/editing sk :deck] [])
        zones       (get-in db [:scenario/editing sk :zones] {})
        ;; Count how many copies are already in any zone
        zone-counts (reduce
                      (fn [acc z-cards]
                        (+ acc (count (filter #(= card-id %) z-cards))))
                      0
                      (vals zones))
        deck-count  (deck-count-for deck card-id)
        available   (- deck-count zone-counts)]
    (if (pos? available)
      (update-in db [:scenario/editing sk :zones zone]
                 (fn [existing] (conj (or existing []) card-id)))
      db)))


(defn remove-from-zone-handler
  "Remove one copy of card-id from zone for side, returning it to pool.
   No-op if card not present in that zone.
   {:side :player/:opponent :card-id keyword :zone keyword}"
  [db [_ {:keys [side card-id zone]}]]
  (let [sk    (side-key side)
        cards (vec (get-in db [:scenario/editing sk :zones zone] []))
        idx   (reduce-kv (fn [found i v] (if (and (nil? found) (= v card-id)) i found))
                         nil
                         cards)]
    (if (nil? idx)
      db
      (update-in db [:scenario/editing sk :zones zone]
                 (fn [existing]
                   (let [v (vec existing)]
                     (into (subvec v 0 idx)
                           (subvec v (inc idx)))))))))


(rf/reg-event-db
  ::assign-to-zone
  assign-to-zone-handler)


(rf/reg-event-db
  ::remove-from-zone
  remove-from-zone-handler)


;; === Library-top handlers ===

(defn add-to-library-top-handler
  "Add a card-id to the end of library-top for the given side.
   Guards: no-op if card is not in the unordered pool (already in zones or already in library-top).
   {:side :player/:opponent :card-id keyword}"
  [db [_ {:keys [side card-id]}]]
  (let [sk         (side-key side)
        side-cfg   (get-in db [:scenario/editing sk] {})
        deck       (or (:deck side-cfg) [])
        zones      (or (:zones side-cfg) {})
        lib-top    (or (:library-top side-cfg) [])
        ;; Count how many of card-id are in zones
        zone-count (reduce
                     (fn [acc z-cards]
                       (+ acc (count (filter #(= card-id %) z-cards))))
                     0
                     (vals zones))
        ;; Count how many are already in library-top
        lib-top-count (count (filter #(= card-id %) lib-top))
        ;; Get deck count
        deck-count (deck-count-for deck card-id)
        ;; Remaining available = deck - zones - library-top
        available (- deck-count zone-count lib-top-count)]
    (if (pos? available)
      (assoc-in db [:scenario/editing sk :library-top]
                (conj lib-top card-id))
      db)))


(defn remove-from-library-top-handler
  "Remove the card at the given index from library-top for the given side.
   No-op if index is out of bounds.
   {:side :player/:opponent :index int}"
  [db [_ {:keys [side index]}]]
  (let [sk      (side-key side)
        lib-top (vec (get-in db [:scenario/editing sk :library-top] []))]
    (if (and (>= index 0) (< index (count lib-top)))
      (assoc-in db [:scenario/editing sk :library-top]
                (into (subvec lib-top 0 index)
                      (subvec lib-top (inc index))))
      db)))


(defn reorder-library-top-handler
  "Reorder library-top by moving card from from-index to to-index for the given side.
   No-op if from-index or to-index are invalid.
   Clamps to-index if beyond bounds.
   {:side :player/:opponent :from-index int :to-index int}"
  [db [_ {:keys [side from-index to-index]}]]
  (let [sk      (side-key side)
        lib-top (vec (get-in db [:scenario/editing sk :library-top] []))
        len     (count lib-top)]
    (if (and (>= from-index 0) (< from-index len) (= from-index to-index))
      ;; No-op if indices are the same
      db
      (if (and (>= from-index 0) (< from-index len) (>= to-index 0))
        ;; Valid from-index; clamp to-index to valid range
        (let [clamped-to (min to-index (dec len))
              card (nth lib-top from-index)
              without (into (subvec lib-top 0 from-index)
                            (subvec lib-top (inc from-index)))
              with-insert (into (subvec without 0 clamped-to)
                                (cons card (subvec without clamped-to)))]
          (assoc-in db [:scenario/editing sk :library-top] with-insert))
        ;; Invalid from-index, no-op
        db))))


;; === re-frame event registrations for library-top ===

(rf/reg-event-db
  ::add-to-library-top
  add-to-library-top-handler)


(rf/reg-event-db
  ::remove-from-library-top
  remove-from-library-top-handler)


(rf/reg-event-db
  ::reorder-library-top
  reorder-library-top-handler)


;; === Game state configuration handlers ===

(defn set-title-handler
  "Set the scenario title on :scenario/editing.
   title is a string."
  [db [_ title]]
  (assoc-in db [:scenario/editing :scenario/title] title))


(defn set-life-handler
  "Set life total for a player side.
   {:side :player/:opponent :life int}"
  [db [_ {:keys [side life]}]]
  (let [sk (side-key side)]
    (assoc-in db [:scenario/editing sk :life] life)))


(defn set-mana-handler
  "Set a specific mana color amount for a player side.
   {:side :player/:opponent :color :white/:blue/:black/:red/:green/:colorless :amount int}"
  [db [_ {:keys [side color amount]}]]
  (let [sk (side-key side)]
    (assoc-in db [:scenario/editing sk :mana-pool color] amount)))


(defn set-phase-handler
  "Set the starting game phase for the scenario.
   {:phase keyword}"
  [db [_ {:keys [phase]}]]
  (assoc-in db [:scenario/editing :scenario/phase] phase))


;; === re-frame event registrations for game state config ===

(rf/reg-event-db
  ::set-title
  set-title-handler)


(rf/reg-event-db
  ::set-life
  set-life-handler)


(rf/reg-event-db
  ::set-mana
  set-mana-handler)


(rf/reg-event-db
  ::set-phase
  set-phase-handler)


;; === Save from game handlers ===

(defn show-save-modal-handler
  "Toggle the save-from-game modal visibility."
  [db _]
  (update db :scenario/save-modal-visible not))


(defn save-from-game-handler
  "Extract current game position, wrap as scenario, and save it.
   Expects event: [::save-from-game title-string]"
  [db [_ title]]
  (if-let [game-db (:game/db db)]
    (let [scenario (extract-scenario-from-game game-db title)
          db' (-> db
                  (assoc-in [:scenario/library (:scenario/id scenario)] scenario)
                  (assoc :scenario/save-modal-visible false)
                  (assoc :scenario/save-modal-title ""))]
      db')
    db))


(rf/reg-event-db
  ::show-save-modal
  show-save-modal-handler)


(rf/reg-event-db
  ::save-from-game
  [save-scenarios-interceptor]
  save-from-game-handler)


(defn update-save-modal-title-handler
  "Update the title field in the save-from-game modal."
  [db [_ title]]
  (assoc db :scenario/save-modal-title title))


(rf/reg-event-db
  ::update-save-modal-title
  update-save-modal-title-handler)
