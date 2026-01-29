(ns fizzle.events.game
  (:require
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as queries]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.triggers :as triggers]
    [fizzle.engine.zones :as zones]
    [re-frame.core :as rf]))


(defn make-test-deck
  "Create a test deck as a vector of card-ids.
   12x each of: dark-ritual, cabal-ritual, brain-freeze, city-of-brass, gemstone-mine
   Returns shuffled vector of 60 card-ids."
  []
  (shuffle
    (into []
          (concat
            (repeat 12 :dark-ritual)
            (repeat 12 :cabal-ritual)
            (repeat 12 :brain-freeze)
            (repeat 12 :city-of-brass)
            (repeat 12 :gemstone-mine)))))


(defn init-game-state
  "Initialize a fresh game state with:
   - Card definitions loaded
   - Player 1 with 20 life, empty mana pool
   - Player 1's 60-card shuffled library
   - 7-card opening hand drawn from library
   - Opponent with 40-card library for mill targets

   Returns app-db map with :game/db key containing Datascript db."
  []
  (let [conn (d/create-conn schema)]
    ;; Transact card definitions
    (d/transact! conn cards/all-cards)
    ;; Transact player (start with empty mana pool - lands generate mana now)
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])
    ;; Transact opponent (needed for Brain Freeze mill effect)
    (d/transact! conn [{:player/id :opponent
                        :player/name "Opponent"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 0
                        :player/is-opponent true}])
    ;; Get entity IDs for references
    (let [db @conn
          player-eid (d/q '[:find ?e .
                            :in $ ?pid
                            :where [?e :player/id ?pid]]
                          db :player-1)
          opp-eid (d/q '[:find ?e .
                         :in $ ?pid
                         :where [?e :player/id ?pid]]
                       db :opponent)
          ;; Helper to look up card entity ID
          get-card-eid (fn [db card-id]
                         (d/q '[:find ?e .
                                :in $ ?cid
                                :where [?e :card/id ?cid]]
                              db card-id))
          ;; Create player's 60-card shuffled library
          deck (make-test-deck)]
      ;; Transact player library (60 cards, shuffled, with positions)
      (d/transact! conn
                   (vec (map-indexed
                          (fn [i card-id]
                            {:object/id (random-uuid)
                             :object/card (get-card-eid @conn card-id)
                             :object/zone :library
                             :object/owner player-eid
                             :object/controller player-eid
                             :object/tapped false
                             :object/position i})
                          deck)))
      ;; Create opponent library (40 cards so mill has targets)
      (let [dr-eid (get-card-eid @conn :dark-ritual)]
        (d/transact! conn (vec (for [i (range 40)]
                                 {:object/id (random-uuid) :object/card dr-eid :object/zone :library
                                  :object/owner opp-eid :object/controller opp-eid
                                  :object/tapped false :object/position i}))))
      ;; Game state
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}])
      ;; Draw 7-card opening hand using draw effect
      (let [db-with-lib @conn
            db-after-draw (effects/execute-effect db-with-lib :player-1
                                                  {:effect/type :draw
                                                   :effect/amount 7})]
        {:game/db db-after-draw}))))


(rf/reg-event-db
  ::init-game
  (fn [_ _]
    (init-game-state)))


(rf/reg-event-db
  ::select-card
  (fn [db [_ object-id]]
    (let [currently-selected (:game/selected-card db)]
      (assoc db :game/selected-card
             (when (not= currently-selected object-id) object-id)))))


(rf/reg-event-db
  ::cast-spell
  (fn [db _]
    (let [game-db (:game/db db)
          selected (:game/selected-card db)]
      (if (and selected (rules/can-cast? game-db :player-1 selected))
        (-> db
            (assoc :game/db (rules/cast-spell game-db :player-1 selected))
            (dissoc :game/selected-card))
        db))))


(rf/reg-event-db
  ::resolve-top
  (fn [db _]
    (let [game-db (:game/db db)
          stack-triggers (queries/get-stack-items game-db)]
      (if (seq stack-triggers)
        ;; Resolve top trigger (first = highest stack-order = most recent)
        (let [top-trigger (first stack-triggers)
              game-db' (-> game-db
                           (triggers/resolve-trigger top-trigger)
                           (triggers/remove-trigger (:trigger/id top-trigger)))]
          (assoc db :game/db game-db'))
        ;; No triggers - resolve top spell object on stack (LIFO by position)
        (let [stack-objects (->> (queries/get-objects-in-zone game-db :player-1 :stack)
                                 (sort-by :object/position >))]
          (if (seq stack-objects)
            (let [top-spell (first stack-objects)
                  game-db' (rules/resolve-spell game-db :player-1 (:object/id top-spell))]
              (assoc db :game/db game-db'))
            db))))))


;; === Turn Structure ===

(def phases
  "MTG turn phases in order: untap → upkeep → draw → main1 → combat → main2 → end → cleanup"
  [:untap :upkeep :draw :main1 :combat :main2 :end :cleanup])


(defn next-phase
  "Get the next phase in the turn sequence.
   Returns the same phase if at cleanup (requires explicit start-turn for new turn)."
  [current-phase]
  (let [idx (.indexOf phases current-phase)]
    (if (or (neg? idx) (= idx (dec (count phases))))
      current-phase  ; Stay at cleanup or unknown phase
      (nth phases (inc idx)))))


(defn advance-phase
  "Advance to the next phase and clear mana pool.
   Pure function: (db, player-id) -> db

   At cleanup phase, stays at cleanup (user must call start-turn for new turn)."
  [db player-id]
  (let [game-state (queries/get-game-state db)
        game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
        current-phase (:game/phase game-state)
        new-phase (next-phase current-phase)]
    (-> db
        (mana/empty-pool player-id)
        (d/db-with [[:db/add game-eid :game/phase new-phase]]))))


(defn start-turn
  "Start a new turn: increment turn counter, set phase to untap,
   reset storm count and land plays to 1, clear mana pool.
   Pure function: (db, player-id) -> db"
  [db player-id]
  (let [game-state (queries/get-game-state db)
        game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
        player-eid (queries/get-player-eid db player-id)
        current-turn (or (:game/turn game-state) 0)]
    (-> db
        (mana/empty-pool player-id)
        (d/db-with [[:db/add game-eid :game/turn (inc current-turn)]
                    [:db/add game-eid :game/phase :untap]
                    [:db/add player-eid :player/storm-count 0]
                    [:db/add player-eid :player/land-plays-left 1]]))))


(rf/reg-event-db
  ::advance-phase
  (fn [db _]
    (let [game-db (:game/db db)]
      (assoc db :game/db (advance-phase game-db :player-1)))))


(rf/reg-event-db
  ::start-turn
  (fn [db _]
    (let [game-db (:game/db db)]
      (assoc db :game/db (start-turn game-db :player-1)))))


;; === Play Land ===

(defn land-card?
  "Check if an object's card has :land in its types.
   Returns false if object or card not found."
  [db object-id]
  (let [obj (queries/get-object db object-id)]
    (when obj
      ;; get-object pulls card data nested under :object/card
      (let [card (:object/card obj)
            types (:card/types card)]
        ;; types may be a set or vector depending on how it was stored
        (contains? (set types) :land)))))


(defn play-land
  "Play a land from hand to battlefield.
   Pure function: (db, player-id, object-id) -> db

   Validation:
   - Player must have land-plays-left > 0
   - Object must be in player's hand
   - Card must be a land type
   - Phase must be :main1 or :main2

   Returns unchanged db if any validation fails."
  [db player-id object-id]
  (let [game-state (queries/get-game-state db)
        phase (:game/phase game-state)
        player-eid (queries/get-player-eid db player-id)
        land-plays (d/q '[:find ?plays .
                          :in $ ?pid
                          :where [?e :player/id ?pid]
                          [?e :player/land-plays-left ?plays]]
                        db player-id)
        obj (queries/get-object db object-id)
        ;; get-object pulls refs as {:db/id N} maps, extract the eid
        owner-eid (:db/id (:object/owner obj))]
    (if (and player-eid
             obj
             (pos? (or land-plays 0))
             (= (:object/zone obj) :hand)
             (= owner-eid player-eid)
             (land-card? db object-id)
             (#{:main1 :main2} phase))
      (-> db
          (zones/move-to-zone object-id :battlefield)
          (d/db-with [[:db/add player-eid :player/land-plays-left (dec land-plays)]]))
      db)))


(rf/reg-event-db
  ::play-land
  (fn [db [_ object-id]]
    (let [game-db (:game/db db)]
      (assoc db :game/db (play-land game-db :player-1 object-id)))))
