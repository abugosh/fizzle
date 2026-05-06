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
                       :player/mana-pool (if (seq (:mana-pool player-cfg))
                                           (:mana-pool player-cfg)
                                           game-state/empty-mana-pool)})
        opp-eid     (game-state/create-complete-player
                      conn game-state/opponent-player-id
                      {:player/name          "Opponent"
                       :player/is-opponent   true
                       :player/bot-archetype bot-arch
                       :player/life          (or (:life opp-cfg) 20)
                       :player/mana-pool     (if (seq (:mana-pool opp-cfg))
                                               (:mana-pool opp-cfg)
                                               game-state/empty-mana-pool)})
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


(rf/reg-event-db
  ::show-library
  (fn [db _]
    (assoc db :scenario/active-view :library)))


(rf/reg-event-db
  ::show-builder
  (fn [db _]
    (assoc db :scenario/active-view :builder)))


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
