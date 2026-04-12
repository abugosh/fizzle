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
    [fizzle.engine.objects :as objects]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.validation :as validation]
    [fizzle.events.casting :as casting]
    [fizzle.events.resolution :as resolution]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.events.selection.targeting :as sel-targeting]
    [fizzle.history.core :as history]))


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
     (let [player-eid (game-state/create-complete-player conn game-state/human-player-id overrides)]
       (d/transact! conn (game-state/create-game-entity-tx player-eid {}))
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
        card-data (d/pull db [:card/types :card/power :card/toughness :card/triggers] card-eid)
        creature? (contains? (set (:card/types card-data)) :creature)
        obj (cond-> (objects/build-object-tx db card-eid card-data zone player-eid 0 :id obj-id)
              ;; Helpers place objects directly on battlefield (no zone transition),
              ;; so set battlefield-specific fields here — same responsibility as move-to-zone
              (and creature? (= zone :battlefield))
              (assoc :object/summoning-sick true
                     :object/damage-marked 0))]
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
          lookup-card-eid (fn [card-id]
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
          (let [card-id   (first remaining)
                obj-id    (random-uuid)
                card-eid  (lookup-card-eid card-id)
                card-data (d/pull @conn [:card/types :card/power :card/toughness :card/triggers] card-eid)
                obj       (objects/build-object-tx @conn card-eid card-data :library player-eid position :id obj-id)]
            (d/transact! conn [obj])
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
          (let [card-id  (first remaining)
                obj-id   (random-uuid)
                card-eid (d/q '[:find ?e .
                                :in $ ?cid
                                :where [?e :card/id ?cid]]
                              @conn card-id)]
            (when (nil? card-eid)
              (throw (ex-info (str "Unknown card-id: " card-id)
                              {:card-id card-id})))
            (let [card-data (d/pull @conn [:card/types :card/power :card/toughness :card/triggers] card-eid)
                  obj       (objects/build-object-tx @conn card-eid card-data :graveyard player-eid 0 :id obj-id)]
              (d/transact! conn [obj]))
            (recur (rest remaining)
                   (conj object-ids obj-id))))))))


(defn add-test-creature
  "Create a synthetic creature with custom P/T on the battlefield.
   Used when tests need a creature with specific stats not matching any registered card.
   Uses build-object-tx for the object — same chokepoint as production code.

   Parameters:
     db      - Datascript database value
     owner   - player keyword (:player-1, :player-2)
     power   - integer power value
     toughness - integer toughness value
   Options (keyword args):
     :colors - set of color keywords, default #{}

   Returns [db obj-id]."
  [db owner power toughness & {:keys [colors] :or {colors #{}}}]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db owner)
        card-id (keyword (str "test-creature-" (random-uuid)))
        _ (d/transact! conn [{:card/id card-id
                              :card/name "Test Creature"
                              :card/cmc 1
                              :card/mana-cost {:colorless 1}
                              :card/colors colors
                              :card/types #{:creature}
                              :card/text ""
                              :card/power power
                              :card/toughness toughness}])
        card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] @conn card-id)
        ;; Synthetic card has no :card/triggers — no triggers will be embedded by build-object-tx.
        card-data {:card/types #{:creature} :card/power power :card/toughness toughness}
        obj-id (random-uuid)
        obj (-> (objects/build-object-tx @conn card-eid card-data :battlefield player-eid 0 :id obj-id)
                (assoc :object/summoning-sick true
                       :object/damage-marked 0))]
    (d/transact! conn [obj])
    [@conn obj-id]))


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
     (game-state/create-complete-player conn game-state/opponent-player-id overrides)
     @conn)))


(defn create-game-scenario
  "Create valid game state for tests that run the game loop.
   Both players have libraries, stops, and turn-based triggers.
   Returns app-db (not just game-db) with history initialized.
   opts: {:bot-archetype :goldfish
          :stops #{:main1 :main2}
          :mana {:black 3}
          :life 20
          :human-library 10
          :bot-library 10}"
  ([opts]
   (let [bot-archetype (get opts :bot-archetype :goldfish)
         human-library-count (get opts :human-library 10)
         bot-library-count (get opts :bot-library 10)
         stops (get opts :stops #{:main1 :main2})
         db (create-test-db (-> opts
                                (assoc :stops stops)
                                (dissoc :bot-archetype :human-library :bot-library)))
         db (add-opponent db {:bot-archetype bot-archetype})
         island-ids (vec (repeat human-library-count :island))
         [db _] (add-cards-to-library db island-ids game-state/human-player-id)
         bot-island-ids (vec (repeat bot-library-count :island))
         [db _] (add-cards-to-library db bot-island-ids game-state/opponent-player-id)]
     (merge (history/init-history) {:game/db db}))))


(defn tap-permanent
  "Set a permanent's tapped state to true for test setup purposes.
   Pure function: (db, object-id) -> db

   This is a TEST HELPER for initializing game state in tests (e.g., setting up
   already-tapped permanents before testing untap behavior). It uses d/db-with
   directly and does NOT dispatch :permanent-tapped — it is NOT a game-action tap.

   For game-action taps that should fire :becomes-tapped triggers, use the
   production paths:
   - costs/pay-cost :tap — tap-as-cost (mana abilities, activated abilities)
   - combat/tap-and-mark-attackers — attack declaration
   - effects :tap-all — mass-tap effect"
  [db object-id]
  (let [obj (q/get-object db object-id)]
    (if obj
      (let [obj-eid (q/get-object-eid db object-id)]
        (d/db-with db [[:db/add obj-eid :object/tapped true]]))
      db)))


;; =====================================================
;; Production-Path Test Helpers
;; =====================================================

(defn cast-and-resolve
  "Casts a spell and resolves it through the full production path.
   Asserts can-cast? before casting. For non-interactive spells only.
   Throws if the spell has pre-cast costs (targeting, additional costs) —
   use cast-with-target or cast-mode-with-target instead.
   NOTE: :mana-allocation is not checked — simple spells with generic mana work fine.
   Returns resolved db."
  [db player-id obj-id]
  (assert (rules/can-cast? db player-id obj-id)
          (str "can-cast? returned false for " obj-id))
  ;; Defensive assert: reject spells with pre-cast cost requirements
  (let [mode (first (rules/get-casting-modes db player-id obj-id))
        ctx {:game-db db :player-id player-id :object-id obj-id :mode mode}
        cost-steps (remove #{:mana-allocation} casting/pre-cast-pipeline)
        failing-step (first (filter #(some? (casting/evaluate-pre-cast-step % ctx)) cost-steps))]
    (when failing-step
      (throw (ex-info
               (str "cast-and-resolve: spell has pre-cast requirements (step: " failing-step
                    "). Use cast-with-target or cast-mode-with-target for targeting, "
                    "or manually handle pre-cast costs before calling cast-and-resolve.")
               {:obj-id obj-id :failing-step failing-step}))))
  (let [db-cast (rules/cast-spell db player-id obj-id)
        result (resolution/resolve-one-item db-cast)]
    (assert (nil? (:pending-selection result))
            "Spell has pending selection — use resolve-top instead")
    (:db result)))


(defn cast-with-target
  "Casts a targeted spell through the full pre-cast pipeline via cast-spell-handler.
   Asserts can-cast? and that the target is valid before calling the pipeline.
   Throws fail-fast if the spell has pre-cast cost selections (use cast-and-resolve +
   manual confirm-selection for those) or requires mode selection (use cast-mode-with-target).
   Returns db with spell on stack and target assigned."
  [db player-id obj-id target-id]
  (assert (rules/can-cast? db player-id obj-id)
          (str "can-cast? returned false for " obj-id))
  ;; Fail fast for modal spells before target validation — clearer error than "not in valid targets"
  (let [obj (q/get-object db obj-id)
        card (:object/card obj)]
    (when (:card/modes card)
      (throw (ex-info "cast-with-target: spell requires mode selection, use cast-mode-with-target"
                      {:object-id obj-id}))))
  ;; Validate target before entering pipeline — clearer error message than pipeline failure
  (let [obj (q/get-object db obj-id)
        card (:object/card obj)
        first-req (first (targeting/get-targeting-requirements card))
        valid-targets (when first-req (targeting/find-valid-targets db player-id first-req))]
    (assert (some #{target-id} valid-targets)
            (str target-id " not in valid targets: " valid-targets)))
  ;; Route through cast-spell-handler with full pre-cast pipeline
  (let [result (casting/cast-spell-handler {:game/db db}
                                           {:player-id player-id
                                            :object-id obj-id
                                            :target target-id})]
    (cond
      (:game/pending-mode-selection result)
      (throw (ex-info "cast-with-target: spell requires mode selection, use cast-mode-with-target"
                      {:object-id obj-id}))

      (and (:game/pending-selection result)
           (= :spell-mode (:selection/type (:game/pending-selection result))))
      (throw (ex-info "cast-with-target: spell requires mode selection, use cast-mode-with-target"
                      {:object-id obj-id}))

      (:game/pending-selection result)
      (throw (ex-info "cast-with-target: spell has pre-cast costs requiring selection"
                      {:object-id obj-id
                       :selection-type (:selection/type (:game/pending-selection result))}))

      :else
      (:game/db result))))


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
   the full production selection pipeline. For spells like BEB/REB/Hydroblast
   that require both mode selection and targeting.

   Pipeline: build-spell-mode-selection → set :selection/selected → confirm-selection-impl
   (spell-mode executor sets :object/chosen-mode, cast-after-spell-mode continuation
   initiates casting with targeting selection).

   spell-mode: the card mode from (:card/modes card), e.g. counter or destroy
   Validates can-cast?, spell mode has valid targets, and target is valid.
   Returns db with spell on stack, chosen-mode set, and target assigned."
  [db player-id obj-id spell-mode target-id]
  (assert (rules/can-cast? db player-id obj-id)
          (str "can-cast? returned false for " obj-id))
  ;; Validate spell mode has valid targets (mirrors get-valid-spell-modes in cast-spell-handler)
  (assert (spell-mode-has-valid-targets? db player-id spell-mode)
          (str "Spell mode has no valid targets: " (:mode/label spell-mode)))
  ;; Build spell-mode selection via production builder, then confirm with chosen mode.
  ;; This exercises the full :spell-mode pipeline including the :object/chosen-mode executor.
  (let [card (q/get-card db obj-id)
        card-modes (:card/modes card)
        sel (casting/build-spell-mode-selection player-id obj-id card-modes)
        sel-with-selected (assoc sel :selection/selected #{spell-mode})
        app-db {:game/db db :game/pending-selection sel-with-selected}
        ;; confirm-selection-impl validates candidates + executes :spell-mode executor
        ;; which sets :object/chosen-mode on the object, then applies cast-after-spell-mode
        ;; continuation which initiates casting and sets up the targeting selection.
        after-mode (sel-core/confirm-selection-impl app-db)
        db-after-mode (:game/db after-mode)
        ;; Targeting selection is now pending (set by cast-after-spell-mode continuation)
        targeting-sel (:game/pending-selection after-mode)]
    (assert targeting-sel
            (str "Expected targeting selection after spell-mode confirm for " obj-id))
    (assert (some #{target-id} (:selection/valid-targets targeting-sel))
            (str target-id " not in valid targets: " (:selection/valid-targets targeting-sel)))
    ;; Confirm the targeting selection using the production path
    (sel-targeting/confirm-cast-time-target
      db-after-mode
      (assoc targeting-sel :selection/selected #{target-id}))))


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
   Returns {:db db} or {:db db :selection sel} if chaining.

   Throws ex-info when the selection fails validation — catches incorrect
   selected-items (wrong count, items not in candidates, nil validation type)
   before they silently pass through."
  [db selection selected-items]
  (let [sel (assoc selection :selection/selected selected-items)]
    (when-not (validation/validate-selection sel)
      (throw (ex-info "confirm-selection: validation failed"
                      {:selected selected-items
                       :validation (:selection/validation selection)
                       :select-count (:selection/select-count selection)
                       :candidates (or (:selection/candidates selection)
                                       (:selection/candidate-ids selection))})))
    (let [app-db {:game/db db :game/pending-selection sel}
          result (sel-core/confirm-selection-impl app-db)]
      (if-let [next-sel (:game/pending-selection result)]
        {:db (:game/db result) :selection next-sel}
        {:db (:game/db result)}))))
