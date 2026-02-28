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
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.cards :as cards]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.turn-based :as turn-based]
    [fizzle.events.game :as game]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.events.selection.targeting :as sel-targeting]))


(def ^:private empty-mana-pool
  {:white 0 :blue 0 :black 0
   :red 0 :green 0 :colorless 0})


(defn create-test-db
  "Create a game state with all card definitions loaded.
   No-arg version: all-zero mana pool, standard player.
   Opts map supports: {:mana {:blue 1 :black 3} :life 20 :storm-count 0 :land-plays 1}"
  ([]
   (create-test-db {}))
  ([opts]
   (let [conn (d/create-conn schema)
         mana-pool (merge empty-mana-pool (:mana opts))
         life (or (:life opts) 20)
         storm-count (or (:storm-count opts) 0)
         land-plays (or (:land-plays opts) 1)]
     (d/transact! conn cards/all-cards)
     (d/transact! conn [{:player/id :player-1
                         :player/name "Player"
                         :player/life life
                         :player/mana-pool mana-pool
                         :player/storm-count storm-count
                         :player/land-plays-left land-plays}])
     (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
       (d/transact! conn [{:game/id :game-1
                           :game/turn 1
                           :game/phase :main1
                           :game/active-player player-eid
                           :game/priority player-eid
                           :game/human-player-id :player-1}])
       (d/transact! conn (turn-based/create-turn-based-triggers-tx player-eid :player-1))
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
        base-obj {:object/id obj-id
                  :object/card card-eid
                  :object/zone zone
                  :object/owner player-eid
                  :object/controller player-eid
                  :object/tapped false}
        obj (if (= zone :library)
              (assoc base-obj :object/position 0)
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
  "Add :player-2 with standard opponent settings and turn-based triggers.
   Opts map supports: {:bot-archetype :goldfish :stops #{:main1}}
   Returns updated db."
  ([db]
   (add-opponent db {}))
  ([db opts]
   (let [conn (d/conn-from-db db)
         base {:player/id :player-2
               :player/name "Opponent"
               :player/life 20
               :player/mana-pool empty-mana-pool
               :player/storm-count 0
               :player/land-plays-left 1
               :player/is-opponent true}
         tx (cond-> base
              (:bot-archetype opts) (assoc :player/bot-archetype (:bot-archetype opts))
              (:stops opts) (assoc :player/stops (:stops opts)))]
     (d/transact! conn [tx])
     (let [opp-eid (q/get-player-eid @conn :player-2)]
       (d/transact! conn (turn-based/create-turn-based-triggers-tx opp-eid :player-2)))
     @conn)))


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
        result (game/resolve-one-item db-cast)]
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
  ;; Store chosen spell mode on object (mirrors select-spell-mode-handler)
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
  (let [result (game/resolve-one-item db)]
    (if-let [sel (:pending-selection result)]
      {:db (:db result) :selection sel}
      {:db (:db result)})))


(defn confirm-selection
  "Confirms a pending selection with the given selected items.
   Returns {:db db} or {:db db :selection sel} if chaining."
  [db selection selected-items]
  (let [sel (assoc selection :selection/selected selected-items)
        result (sel-core/execute-confirmed-selection db sel)]
    (if-let [next-sel (:pending-selection result)]
      {:db (:db result) :selection next-sel}
      {:db (:db result)})))
