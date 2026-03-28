(ns fizzle.test-helpers
  "Shared test helpers for creating game state and managing zones.

   All helpers return immutable db values (not conn).
   Return convention: [db result] tuples (db first).

   Production-path helpers (cast-and-resolve, cast-with-target, resolve-top,
   confirm-selection) wrap the full production code paths for use in tests.
   Use these for happy-path cast-resolve tests instead of calling internal
   selection/effect functions directly."
  (:require
    [datascript.core :as d]
    [fizzle.bots.protocol :as bot-protocol]
    [fizzle.db.game-state :as game-state]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.cards :as cards]
    [fizzle.engine.effects-registry]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.turn-based :as turn-based]
    [fizzle.events.lands :as lands]
    [fizzle.events.resolution :as resolution]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.events.selection.targeting :as sel-targeting]))


(defn create-test-db
  "Create a game state with all card definitions loaded.
   No-arg version: all-zero mana pool, standard player.
   Opts map supports: {:mana {:blue 1 :black 3} :life 20 :storm-count 0 :land-plays 1}"
  ([]
   (create-test-db {}))
  ([opts]
   (let [conn (d/create-conn schema)
         mana-pool (merge game-state/empty-mana-pool (:mana opts))
         overrides (cond-> {:player/name "Player"
                            :player/mana-pool mana-pool}
                     (:life opts) (assoc :player/life (:life opts))
                     (:storm-count opts) (assoc :player/storm-count (:storm-count opts))
                     (:land-plays opts) (assoc :player/land-plays-left (:land-plays opts)))]
     (d/transact! conn cards/all-cards)
     (d/transact! conn (game-state/create-player-tx game-state/human-player-id overrides))
     (let [player-eid (q/get-player-eid @conn game-state/human-player-id)]
       (d/transact! conn (game-state/create-game-entity-tx player-eid {}))
       (d/transact! conn (turn-based/create-turn-based-triggers-tx player-eid game-state/human-player-id))
       (when-let [stops (:stops opts)]
         (d/transact! conn [[:db/add player-eid :player/stops stops]])))
     @conn)))


(defn add-card-to-zone
  "Add a card object to a zone for a player.
   card-id: keyword like :dark-ritual
   zone: keyword like :hand, :battlefield, :graveyard, :library, :exile
   owner: keyword like :player-1
   When zone is :library, adds :object/position 0 (top of library).
   Returns [db obj-id] tuple."
  [db card-id zone owner]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db owner)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db card-id)
        _ (when (nil? card-eid)
            (throw (ex-info (str "Unknown card-id: " card-id
                                 ". Card not found in database.")
                            {:card-id card-id})))
        obj-id (random-uuid)
        card-def (d/pull db [:card/types :card/power :card/toughness] card-eid)
        card-types (set (:card/types card-def))
        base-obj {:object/id obj-id
                  :object/card card-eid
                  :object/zone zone
                  :object/owner player-eid
                  :object/controller player-eid
                  :object/tapped false}
        obj (cond
              (= zone :library)
              (assoc base-obj :object/position 0)
              (and (= zone :battlefield) (contains? card-types :creature))
              (assoc base-obj
                     :object/power (:card/power card-def)
                     :object/toughness (:card/toughness card-def)
                     :object/summoning-sick true
                     :object/damage-marked 0)
              :else
              base-obj)]
    (d/transact! conn [obj])
    [@conn obj-id]))


(defn add-cards-to-library
  "Add multiple cards to the library with sequential positions.
   card-ids: vector of card-id keywords.
   Position 0 = top of library.
   Returns [db object-ids] tuple with object-ids in order."
  [db card-ids owner]
  (if (empty? card-ids)
    [db []]
    (let [conn (d/conn-from-db db)
          player-eid (q/get-player-eid db owner)
          get-card-eid (fn [card-id]
                         (let [eid (d/q '[:find ?e .
                                          :in $ ?cid
                                          :where [?e :card/id ?cid]]
                                        @conn card-id)]
                           (when (nil? eid)
                             (throw (ex-info (str "Unknown card-id: " card-id)
                                             {:card-id card-id})))
                           eid))]
      (loop [remaining card-ids
             position 0
             object-ids []]
        (if (empty? remaining)
          [@conn object-ids]
          (let [card-id (first remaining)
                obj-id (random-uuid)
                card-eid (get-card-eid card-id)]
            (d/transact! conn [{:object/id obj-id
                                :object/card card-eid
                                :object/zone :library
                                :object/owner player-eid
                                :object/controller player-eid
                                :object/tapped false
                                :object/position position}])
            (recur (rest remaining)
                   (inc position)
                   (conj object-ids obj-id))))))))


(defn add-cards-to-graveyard
  "Add multiple cards to the graveyard.
   card-ids: vector of card-id keywords.
   Returns [db object-ids] tuple."
  [db card-ids owner]
  (if (empty? card-ids)
    [db []]
    (let [conn (d/conn-from-db db)
          player-eid (q/get-player-eid db owner)]
      (loop [remaining card-ids
             object-ids []]
        (if (empty? remaining)
          [@conn object-ids]
          (let [card-id (first remaining)
                obj-id (random-uuid)
                card-eid (d/q '[:find ?e .
                                :in $ ?cid
                                :where [?e :card/id ?cid]]
                              @conn card-id)]
            (when (nil? card-eid)
              (throw (ex-info (str "Unknown card-id: " card-id)
                              {:card-id card-id})))
            (d/transact! conn [{:object/id obj-id
                                :object/card card-eid
                                :object/zone :graveyard
                                :object/owner player-eid
                                :object/controller player-eid
                                :object/tapped false}])
            (recur (rest remaining)
                   (conj object-ids obj-id))))))))


(defn get-zone-count
  "Count objects in a zone for a player."
  [db zone owner]
  (count (or (q/get-objects-in-zone db owner zone) [])))


(defn get-object-zone
  "Get the zone keyword for a given object."
  [db obj-id]
  (:object/zone (q/get-object db obj-id)))


(defn get-hand-count
  "Get the number of cards in a player's hand."
  [db owner]
  (get-zone-count db :hand owner))


(defn add-opponent
  "Add opponent with standard opponent settings and turn-based triggers.
   Opts map supports: {:bot-archetype :goldfish :stops #{:main1}}
   When :bot-archetype is set and :stops is not provided, derives stops
   from the bot spec's phase-actions keys (same as production init).
   Returns updated db."
  ([db]
   (add-opponent db {}))
  ([db opts]
   (let [conn (d/conn-from-db db)
         bot-stops (when (and (:bot-archetype opts) (not (contains? opts :stops)))
                     (bot-protocol/bot-stops (:bot-archetype opts)))
         overrides (cond-> {:player/name "Opponent"
                            :player/is-opponent true}
                     (:bot-archetype opts) (assoc :player/bot-archetype (:bot-archetype opts))
                     (:stops opts) (assoc :player/stops (:stops opts))
                     bot-stops (assoc :player/stops bot-stops))]
     (d/transact! conn (game-state/create-player-tx game-state/opponent-player-id overrides))
     (let [opp-eid (q/get-player-eid @conn game-state/opponent-player-id)]
       (d/transact! conn (turn-based/create-turn-based-triggers-tx opp-eid game-state/opponent-player-id)))
     @conn)))


(defn tap-permanent
  "Tap a permanent on the battlefield. Returns updated db."
  [db object-id]
  (lands/tap-permanent db object-id))


;; =====================================================
;; Production-Path Test Helpers
;; =====================================================

(defn cast-and-resolve
  "Casts a spell and resolves it through the full production path.
   Asserts can-cast? before casting. For non-interactive spells only.
   Returns resolved db."
  [db player-id obj-id]
  (assert (rules/can-cast? db player-id obj-id)
          (str "can-cast? returned false for " obj-id))
  (let [db-cast (rules/cast-spell db player-id obj-id)
        result (resolution/resolve-one-item db-cast)]
    (assert (nil? (:pending-selection result))
            "Spell has pending selection — use resolve-top instead")
    (:db result)))


(defn cast-with-target
  "Casts a targeted spell, selecting the given target through production
   targeting flow. Asserts can-cast? and target is valid.
   Returns db with spell on stack and target assigned."
  [db player-id obj-id target-id]
  (assert (rules/can-cast? db player-id obj-id)
          (str "can-cast? returned false for " obj-id))
  (let [result (sel-targeting/cast-spell-with-targeting db player-id obj-id)
        selection (:pending-target-selection result)]
    (assert selection "Expected targeting selection but got none")
    (assert (some #{target-id} (:selection/valid-targets selection))
            (str target-id " not in valid targets: " (:selection/valid-targets selection)))
    (sel-targeting/confirm-cast-time-target
      (:db result)
      (assoc selection :selection/selected #{target-id}))))


(defn- spell-mode-has-valid-targets?
  "Check if a spell mode's required targeting has valid targets.
   Mirrors get-valid-spell-modes in events/game.cljs."
  [db player-id spell-mode]
  (let [targeting-reqs (or (:mode/targeting spell-mode) [])]
    (every? (fn [req]
              (if (:target/required req)
                (seq (targeting/find-valid-targets db player-id req))
                true))
            targeting-reqs)))


(defn cast-mode-with-target
  "Casts a modal+targeted spell using the given spell mode and target through
   the production flow. For spells like BEB/REB/Hydroblast that require both
   mode selection and targeting.
   spell-mode: the card mode from (:card/modes card), e.g. counter or destroy
   Validates can-cast?, spell mode has valid targets, and target is valid.
   Returns db with spell on stack, chosen-mode set, and target assigned."
  [db player-id obj-id spell-mode target-id]
  (assert (rules/can-cast? db player-id obj-id)
          (str "can-cast? returned false for " obj-id))
  ;; Validate spell mode has valid targets (mirrors get-valid-spell-modes in cast-spell-handler)
  (assert (spell-mode-has-valid-targets? db player-id spell-mode)
          (str "Spell mode has no valid targets: " (:mode/label spell-mode)))
  ;; Store chosen spell mode on object (mirrors spell-mode executor)
  (let [obj-eid (q/get-object-eid db obj-id)
        db-with-mode (d/db-with db [[:db/add obj-eid :object/chosen-mode spell-mode]])
        ;; Get casting mode (primary) for mana payment
        modes (rules/get-casting-modes db-with-mode player-id obj-id)
        casting-mode (or (first (filter #(= :primary (:mode/id %)) modes))
                         (first modes))
        ;; Build targeting selection using the spell mode's targeting requirements
        target-req (first (:mode/targeting spell-mode))
        selection (sel-targeting/build-cast-time-target-selection
                    db-with-mode player-id obj-id casting-mode target-req)]
    (assert (some #{target-id} (:selection/valid-targets selection))
            (str target-id " not in valid targets: " (:selection/valid-targets selection)))
    (sel-targeting/confirm-cast-time-target
      db-with-mode
      (assoc selection :selection/selected #{target-id}))))


(defn resolve-top
  "Resolves the topmost stack item via production path.
   Returns {:db db} for non-interactive effects,
   or {:db db :selection sel} for interactive effects."
  [db]
  (let [result (resolution/resolve-one-item db)]
    (if-let [sel (:pending-selection result)]
      {:db (:db result) :selection sel}
      {:db (:db result)})))


(defn confirm-selection
  "Confirms a pending selection with the given selected items.
   Routes through confirm-selection-impl which handles lifecycle routing
   (standard, finalized, chaining) based on builder-declared metadata.
   Returns {:db db} or {:db db :selection sel} if chaining."
  [db selection selected-items]
  (let [sel (assoc selection :selection/selected selected-items)
        app-db {:game/db db :game/pending-selection sel}
        result (sel-core/confirm-selection-impl app-db)]
    (if-let [next-sel (:game/pending-selection result)]
      {:db (:game/db result) :selection next-sel}
      {:db (:game/db result)})))
