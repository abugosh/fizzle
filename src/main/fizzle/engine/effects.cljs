(ns fizzle.engine.effects
  "Effect interpreter for Fizzle.

   Dispatches on :effect/type to execute card effects.
   All functions are pure: (db, args) -> db"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.zones :as zones]))


;; === Condition System ===
;; Effects may have an optional :effect/condition that gates execution.
;; Condition is checked BEFORE effect runs. If not met, effect is skipped.


(defmulti check-condition
  "Check if an effect's condition is met. Dispatches on :condition/type.
   Returns true if condition met, false otherwise.
   Unknown condition types return false (fail-safe: don't execute)."
  (fn [_db _player-id _effect condition] (:condition/type condition)))


(defmethod check-condition :default
  [_db _player-id _effect _condition]
  ;; Unknown condition types return false (fail-safe: skip effect)
  false)


(defmethod check-condition :no-counters
  [db _player-id effect condition]
  "Check if target has no counters of the specified type.
   Condition keys:
     :condition/counter-type - The counter type to check (e.g., :mining)
   Returns true if counter is 0, nil, or missing."
  (let [target-id (:effect/target effect)
        counter-type (:condition/counter-type condition)]
    (if-let [obj-eid (d/q '[:find ?e .
                            :in $ ?oid
                            :where [?e :object/id ?oid]]
                          db target-id)]
      (let [counters (or (d/q '[:find ?c .
                                :in $ ?e
                                :where [?e :object/counters ?c]]
                              db obj-eid)
                         {})
            count-value (get counters counter-type 0)]
        (<= count-value 0))
      ;; Object doesn't exist - condition met (vacuously true, effect will no-op anyway)
      true)))


;; === Effect Execution ===


(defmulti execute-effect-impl
  "Internal effect executor. Dispatches on :effect/type.
   Use execute-effect instead - it handles condition checking.

   Arguments:
     db - Datascript database value
     player-id - The player who controls this effect
     effect - Map with :effect/type and effect-specific keys

   Returns:
     New db with effect applied.

   Unknown effect types return db unchanged (no-op)."
  (fn [_db _player-id effect] (:effect/type effect)))


(defmethod execute-effect-impl :default
  [db _player-id _effect]
  ;; Unknown effect types are no-ops
  db)


(defn execute-effect
  "Execute an effect, checking condition first if present.
   If :effect/condition exists and is not met, returns db unchanged.
   If no condition or condition met, dispatches to execute-effect-impl.

   Note: :self targets MUST be resolved to object-id by caller before
   calling this function. See events/game.cljs play-land for pattern."
  [db player-id effect]
  (if-let [condition (:effect/condition effect)]
    (if (check-condition db player-id effect condition)
      (execute-effect-impl db player-id effect)
      db)
    (execute-effect-impl db player-id effect)))


(defmethod execute-effect-impl :add-mana
  [db player-id effect]
  (mana/add-mana db player-id (:effect/mana effect)))


(defmethod execute-effect-impl :mill
  ;; Mill cards from a player's library to their graveyard.
  ;;
  ;; Effect keys:
  ;;   :effect/amount - Number of cards to mill
  ;;   :effect/target - Target player (:opponent for opponent, or player-id)
  ;;                    Defaults to caster if not specified.
  ;;
  ;; Handles edge cases:
  ;;   - Empty library: returns db unchanged (no-op)
  ;;   - Partial library: mills all available cards (no crash)
  [db player-id effect]
  (let [target (get effect :effect/target player-id)
        target-player (if (= target :opponent)
                        (q/get-opponent-id db player-id)
                        target)
        amount (:effect/amount effect)
        cards-to-mill (or (q/get-top-n-library db target-player amount) [])]
    (reduce (fn [db' oid]
              (zones/move-to-zone db' oid :graveyard))
            db
            cards-to-mill)))


(defn- set-loss-condition
  "Set the game loss condition. Returns db with :game/loss-condition set."
  [db condition]
  (let [game-eid (d/q '[:find ?e .
                        :where [?e :game/id _]]
                      db)]
    (d/db-with db [[:db/add game-eid :game/loss-condition condition]])))


(defmethod execute-effect-impl :lose-life
  ;; Reduce a player's life total.
  ;;
  ;; Effect keys:
  ;;   :effect/amount - Amount of life to lose
  ;;   :effect/target - Target player (defaults to caster)
  ;;
  ;; Handles edge cases:
  ;;   - Amount <= 0: no-op (negative amount is NOT treated as gain)
  ;;   - Invalid player: no-op (returns db unchanged)
  ;;   - Life can go negative (no clamping at 0)
  ;;   - Life reaching 0 or below sets :game/loss-condition :life-zero
  [db player-id effect]
  (let [amount (get effect :effect/amount 0)
        target (get effect :effect/target player-id)]
    ;; Guard: amount <= 0 is no-op
    (if (<= amount 0)
      db
      ;; Guard: invalid player is no-op
      (if-let [player-eid (q/get-player-eid db target)]
        (let [current-life (q/get-life-total db target)
              new-life (- current-life amount)
              db-after-life (d/db-with db [[:db/add player-eid :player/life new-life]])]
          ;; Check for loss condition
          (if (<= new-life 0)
            (set-loss-condition db-after-life :life-zero)
            db-after-life))
        db))))


(defmethod execute-effect-impl :gain-life
  ;; Increase a player's life total.
  ;;
  ;; Effect keys:
  ;;   :effect/amount - Amount of life to gain
  ;;   :effect/target - Target player (defaults to caster)
  ;;
  ;; Handles edge cases:
  ;;   - Amount <= 0: no-op (negative amount is NOT treated as lose)
  ;;   - Invalid player: no-op (returns db unchanged)
  ;;   - No maximum life cap
  [db player-id effect]
  (let [amount (get effect :effect/amount 0)
        target (get effect :effect/target player-id)]
    ;; Guard: amount <= 0 is no-op
    (if (<= amount 0)
      db
      ;; Guard: invalid player is no-op
      (if-let [player-eid (q/get-player-eid db target)]
        (let [current-life (q/get-life-total db target)
              new-life (+ current-life amount)]
          (d/db-with db [[:db/add player-eid :player/life new-life]]))
        db))))


(defmethod execute-effect-impl :deal-damage
  ;; Deal damage to a player.
  ;;
  ;; Effect keys:
  ;;   :effect/amount - Damage amount (integer)
  ;;   :effect/target - Player ID receiving damage
  ;;   :effect/source - Object ID dealing damage (optional, for future prevention)
  ;;
  ;; Handles edge cases:
  ;;   - Amount <= 0: no-op (negative amount is NOT treated as heal)
  ;;   - Invalid player: no-op (returns db unchanged)
  ;;   - Life can go negative (no clamping at 0)
  ;;   - Life reaching 0 or below sets :game/loss-condition :life-zero
  ;;
  ;; Note: For Phase 1.5, behaves identically to :lose-life.
  ;; Kept separate for future damage prevention implementation.
  ;; Damage can be prevented; life loss cannot (MTG rules).
  [db _player-id effect]
  (let [amount (get effect :effect/amount 0)
        target (:effect/target effect)]
    ;; Guard: amount <= 0 is no-op
    (if (<= amount 0)
      db
      ;; Guard: invalid player is no-op
      (if-let [player-eid (q/get-player-eid db target)]
        (let [current-life (q/get-life-total db target)
              new-life (- current-life amount)
              db-after-damage (d/db-with db [[:db/add player-eid :player/life new-life]])]
          ;; Check for loss condition
          (if (<= new-life 0)
            (set-loss-condition db-after-damage :life-zero)
            db-after-damage))
        db))))


(defmethod execute-effect-impl :add-counters
  ;; Add counters to a permanent on the battlefield.
  ;;
  ;; Effect keys:
  ;;   :effect/target - Object ID (UUID) of the permanent
  ;;   :effect/counters - Map of counter-type to amount {:mining 3}
  ;;
  ;; Handles edge cases:
  ;;   - Nil/missing counters: initializes to provided counters
  ;;   - Existing counters: merges, incrementing same types
  ;;   - Invalid target: no-op (returns db unchanged)
  [db _player-id effect]
  (let [target-id (:effect/target effect)
        counters-to-add (:effect/counters effect)]
    (if-let [obj-eid (d/q '[:find ?e .
                            :in $ ?oid
                            :where [?e :object/id ?oid]]
                          db target-id)]
      (let [existing (or (d/q '[:find ?c .
                                :in $ ?e
                                :where [?e :object/counters ?c]]
                              db obj-eid)
                         {})
            merged (merge-with + existing counters-to-add)]
        (d/db-with db [[:db/add obj-eid :object/counters merged]]))
      ;; Invalid target: no-op
      db)))


(defmethod execute-effect-impl :draw
  ;; Draw cards from a player's library to their hand.
  ;;
  ;; Effect keys:
  ;;   :effect/amount - Number of cards to draw (default 1)
  ;;   :effect/target - Target player (defaults to caster)
  ;;
  ;; Handles edge cases:
  ;;   - Empty library: sets :game/loss-condition to :empty-library
  ;;   - Partial library: draws all available, then sets loss condition
  ;;   - Draw 0 or negative: no-op, no loss condition
  ;;   - Invalid player: no-op (returns db unchanged)
  [db player-id effect]
  (let [amount (get effect :effect/amount 1)
        target (get effect :effect/target player-id)]
    ;; Guard: draw 0 or negative is no-op
    (if (<= amount 0)
      db
      ;; Guard: invalid player is no-op
      (if-let [cards-to-draw (q/get-top-n-library db target amount)]
        (let [actual-drawn (count cards-to-draw)
              ;; Move cards from library to hand
              db-after-draw (reduce (fn [db' oid]
                                      (zones/move-to-zone db' oid :hand))
                                    db
                                    cards-to-draw)]
          ;; If we couldn't draw all requested cards, set loss condition
          (if (< actual-drawn amount)
            (set-loss-condition db-after-draw :empty-library)
            db-after-draw))
        ;; get-top-n-library returns nil if player doesn't exist
        db))))


(defmethod execute-effect-impl :sacrifice
  ;; Sacrifice a permanent - move it to the graveyard.
  ;;
  ;; Effect keys:
  ;;   :effect/target - Object ID (UUID) of the permanent to sacrifice
  ;;                    Caller must resolve :self to object-id before calling.
  ;;
  ;; Handles edge cases:
  ;;   - Invalid target: no-op (returns db unchanged)
  ;;   - Object not on battlefield: still moves to graveyard (zones handles it)
  [db _player-id effect]
  (let [target-id (:effect/target effect)]
    (if (d/q '[:find ?e .
               :in $ ?oid
               :where [?e :object/id ?oid]]
             db target-id)
      (zones/move-to-zone db target-id :graveyard)
      ;; Invalid target: no-op
      db)))


(defmethod execute-effect-impl :discard
  ;; Discard cards from a player's hand to their graveyard.
  ;;
  ;; Effect keys:
  ;;   :effect/count - Number of cards to discard
  ;;   :effect/selection - How to select cards:
  ;;     :player - Player chooses (requires UI interaction, returns db unchanged)
  ;;     :random - Random selection (not implemented yet)
  ;;     nil - Defaults to :player for now
  ;;
  ;; When :selection is :player:
  ;;   This effect returns db UNCHANGED. The actual discard happens at the
  ;;   app-db level via re-frame events when the player confirms their selection.
  ;;   The calling code (resolve-spell) checks for :selection :player and sets up
  ;;   pending selection state instead of expecting immediate resolution.
  ;;
  ;; Handles edge cases:
  ;;   - :selection :player: Returns db unchanged (UI handles selection)
  ;;   - Invalid player: no-op (returns db unchanged)
  [db _player-id effect]
  (let [selection (get effect :effect/selection :player)]
    (case selection
      ;; Player selection - return unchanged, UI will handle
      :player db
      ;; Default to player selection
      db)))
