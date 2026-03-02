(ns fizzle.engine.effects
  "Effect interpreter for Fizzle.

   Dispatches on :effect/type to execute card effects.
   All functions are pure: (db, args) -> db

   Interactive effects (those requiring player selection) return tagged
   values from execute-effect-impl: {:db db :needs-selection effect}.
   Use execute-effect-checked to get the full tagged result, or
   execute-effect for backward-compatible plain db return."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.conditions :as conditions]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.zones :as zones]))


;; === Dynamic Amount Resolution ===


(defmulti resolve-dynamic-impl
  "Resolve a dynamic amount descriptor to an integer.
   Dispatches on :dynamic/type."
  (fn [_db _player-id dynamic _object-id]
    (:dynamic/type dynamic)))


(defmethod resolve-dynamic-impl :count-named-in-zone
  [db _player-id dynamic object-id]
  (let [source-card (q/get-card db object-id)
        card-name (:card/name source-card)
        zone (:dynamic/zone dynamic)
        plus (or (:dynamic/plus dynamic) 0)]
    (+ (q/count-cards-named-in-zone db card-name zone) plus)))


(defmethod resolve-dynamic-impl :chosen-x
  [db _player-id _dynamic object-id]
  (let [obj-eid (q/get-object-eid db object-id)
        chosen-x (when obj-eid
                   (d/q '[:find ?x .
                          :in $ ?obj-eid
                          :where [?e :stack-item/object-ref ?obj-eid]
                          [?e :stack-item/chosen-x ?x]]
                        db obj-eid))]
    (or chosen-x 0)))


(defn resolve-dynamic-value
  "Resolve a potentially dynamic value to an integer.
   Static integers pass through. Maps with :dynamic/type are computed from game state."
  [db player-id value object-id]
  (cond
    (integer? value) value
    (map? value) (resolve-dynamic-impl db player-id value object-id)
    :else 0))


;; === Effect Execution ===


(defmulti execute-effect-impl
  "Internal effect executor. Dispatches on :effect/type.
   Use execute-effect or execute-effect-checked instead.

   Arguments:
     db - Datascript database value
     player-id - The player who controls this effect
     effect - Map with :effect/type and effect-specific keys
     object-id - The object that is the source of this effect (may be nil)

   Returns one of:
     db - New db with effect applied (non-interactive effects)
     {:db db :needs-selection effect} - Tagged value for interactive effects
       that require player selection before they can execute.

   Unknown effect types return db unchanged (no-op)."
  (fn [_db _player-id effect _object-id] (:effect/type effect)))


(defmethod execute-effect-impl :default
  [db _player-id _effect _object-id]
  ;; Unknown effect types are no-ops
  db)


(defn- normalize-effect-result
  "Normalize execute-effect-impl return value to a tagged map.
   Plain db -> {:db db}, tagged map -> passed through."
  [result]
  (if (and (map? result) (contains? result :db))
    result
    {:db result}))


(defn execute-effect
  "Execute an effect, checking condition first if present.
   Returns plain db (extracts :db from tagged results for backward compat).

   Arguments:
     db - Datascript database value
     player-id - The player who controls this effect
     effect - Map with :effect/type and effect-specific keys
     object-id - (Optional) The object that is the source of this effect.

   Note: :self targets MUST be resolved to object-id by caller before
   calling this function. See events/game.cljs play-land for pattern."
  ([db player-id effect]
   (execute-effect db player-id effect nil))
  ([db player-id effect object-id]
   (if-let [condition (:effect/condition effect)]
     (let [enriched (cond-> condition
                      (:effect/target effect)
                      (assoc :condition/target (:effect/target effect)))]
       (if (conditions/check-condition db player-id enriched)
         (:db (normalize-effect-result (execute-effect-impl db player-id effect object-id)))
         db))
     (:db (normalize-effect-result (execute-effect-impl db player-id effect object-id))))))


(defn execute-effect-checked
  "Execute an effect with full tagged result.
   Returns {:db db'} for non-interactive effects, or
   {:db db :needs-selection effect} for interactive effects.

   When :effect/condition is present and not met, returns {:db db}
   (no :needs-selection, since the effect is skipped).

   Arguments:
     db - Datascript database value
     player-id - The player who controls this effect
     effect - Map with :effect/type and effect-specific keys
     object-id - (Optional) The object that is the source of this effect."
  ([db player-id effect]
   (execute-effect-checked db player-id effect nil))
  ([db player-id effect object-id]
   (if-let [condition (:effect/condition effect)]
     (let [enriched (cond-> condition
                      (:effect/target effect)
                      (assoc :condition/target (:effect/target effect)))]
       (if (conditions/check-condition db player-id enriched)
         (normalize-effect-result (execute-effect-impl db player-id effect object-id))
         {:db db}))
     (normalize-effect-result (execute-effect-impl db player-id effect object-id)))))


(defn reduce-effects
  "Execute effects sequentially, pausing when an interactive effect is encountered.

   Returns:
     {:db db'} - All effects executed successfully (no interactive effects)
     {:db db' :needs-selection effect :remaining-effects [...]} -
       Paused at an interactive effect. :db has all effects before
       the interactive one applied. :remaining-effects are the effects
       after the interactive one that still need execution.

   Arguments:
     db - Datascript database value
     player-id - The player who controls these effects
     effects - Sequence of effect maps
     object-id - (Optional) The object that is the source of these effects."
  ([db player-id effects]
   (reduce-effects db player-id effects nil))
  ([db player-id effects object-id]
   (loop [db db
          [effect & remaining] (seq effects)]
     (if-not effect
       {:db db}
       (let [result (execute-effect-checked db player-id effect object-id)]
         (if (:needs-selection result)
           (assoc result :remaining-effects (vec remaining))
           (recur (:db result) remaining)))))))


(defmethod execute-effect-impl :add-mana
  [db player-id effect _object-id]
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
  [db player-id effect _object-id]
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
  ;;   - Life reaching 0 or below: SBA interceptor detects and sets loss condition
  [db player-id effect _object-id]
  (let [amount (get effect :effect/amount 0)
        target (get effect :effect/target player-id)]
    ;; Guard: amount <= 0 is no-op
    (if (<= amount 0)
      db
      ;; Guard: invalid player is no-op
      (if-let [player-eid (q/get-player-eid db target)]
        (let [current-life (q/get-life-total db target)
              new-life (- current-life amount)]
          (d/db-with db [[:db/add player-eid :player/life new-life]]))
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
  [db player-id effect _object-id]
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
  ;;   - Life reaching 0 or below: SBA interceptor detects and sets loss condition
  ;;
  ;; Note: For Phase 1.5, behaves identically to :lose-life.
  ;; Kept separate for future damage prevention implementation.
  ;; Damage can be prevented; life loss cannot (MTG rules).
  [db _player-id effect _object-id]
  (let [amount (get effect :effect/amount 0)
        target (:effect/target effect)]
    ;; Guard: amount <= 0 is no-op
    (if (<= amount 0)
      db
      ;; Guard: invalid player is no-op
      (if-let [player-eid (q/get-player-eid db target)]
        (let [current-life (q/get-life-total db target)
              new-life (- current-life amount)]
          (d/db-with db [[:db/add player-eid :player/life new-life]]))
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
  [db _player-id effect _object-id]
  (let [target-id (:effect/target effect)
        counters-to-add (:effect/counters effect)]
    (if-let [obj-eid (q/get-object-eid db target-id)]
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
  ;;   :effect/target - Target player (:any-player, :opponent, or player-id)
  ;;                    Defaults to caster if not specified.
  ;;
  ;; Interactive effect: Returns {:db db :needs-selection effect} if:
  ;;   - :effect/target is :any-player (player chooses target)
  ;;
  ;; Handles edge cases:
  ;;   - Empty library: sets :player/drew-from-empty flag (SBA detects loss)
  ;;   - Partial library: draws all available, then sets flag
  ;;   - Draw 0 or negative: no-op, no flag set
  ;;   - Invalid player: no-op (returns db unchanged)
  [db player-id effect object-id]
  (let [amount (resolve-dynamic-value db player-id (get effect :effect/amount 1) object-id)
        target (get effect :effect/target player-id)]
    ;; Guard: draw 0 or negative is no-op
    (if (<= amount 0)
      db
      ;; Interactive: :any-player target requires selection
      (if (= target :any-player)
        {:db db :needs-selection effect}
        ;; Guard: invalid player is no-op
        (if-let [cards-to-draw (q/get-top-n-library db target amount)]
          (let [actual-drawn (count cards-to-draw)
                ;; Move cards from library to hand
                db-after-draw (reduce (fn [db' oid]
                                        (zones/move-to-zone db' oid :hand))
                                      db
                                      cards-to-draw)]
            ;; If we couldn't draw all requested cards, set drew-from-empty flag.
            ;; SBA interceptor detects and sets loss condition.
            (if (< actual-drawn amount)
              (let [target-eid (q/get-player-eid db-after-draw target)]
                (d/db-with db-after-draw [[:db/add target-eid :player/drew-from-empty true]]))
              db-after-draw))
          ;; get-top-n-library returns nil if player doesn't exist
          db)))))


(defmethod execute-effect-impl :exile-self
  ;; Exile the source object (the spell/permanent that produced this effect).
  ;;
  ;; Effect keys:
  ;;   None required - uses object-id parameter as the target.
  ;;
  ;; Used by cards like Ill-Gotten Gains that exile themselves as part
  ;; of their resolution (instead of going to graveyard).
  ;;
  ;; Handles edge cases:
  ;;   - Nil object-id: no-op (returns db unchanged)
  ;;   - Nonexistent object: no-op (returns db unchanged)
  [db _player-id _effect object-id]
  (if-not object-id
    db  ; No source object - no-op
    (if (q/get-object-eid db object-id)
      (zones/move-to-zone db object-id :exile)
      db)))  ; Object doesn't exist - no-op


(defmethod execute-effect-impl :discard-hand
  ;; Discard a player's entire hand to their graveyard.
  ;;
  ;; Effect keys:
  ;;   :effect/target - Target player (:self, :opponent, or player-id)
  ;;                    Defaults to caster if not specified.
  ;;
  ;; This is a mandatory discard (no player choice) - all cards in hand
  ;; are moved to graveyard.
  ;;
  ;; Handles edge cases:
  ;;   - Empty hand: returns db unchanged (no-op)
  ;;   - Invalid player: returns db unchanged (no-op)
  ;;   - :self target: resolves to caster's player-id
  [db player-id effect _object-id]
  (let [target (get effect :effect/target player-id)
        target-player (cond
                        (= target :opponent) (q/get-opponent-id db player-id)
                        (= target :self) player-id
                        :else target)
        hand-cards (q/get-hand db target-player)]
    (reduce (fn [db' obj]
              (zones/move-to-zone db' (:object/id obj) :graveyard))
            db
            (or hand-cards []))))


(defmethod execute-effect-impl :return-from-graveyard
  ;; Return cards from a player's graveyard to their hand.
  ;;
  ;; Effect keys:
  ;;   :effect/target - Target player (:self, :opponent, or player-id)
  ;;                    Defaults to caster if not specified.
  ;;   :effect/count - Maximum cards to return (0-N)
  ;;   :effect/selection - How to select cards:
  ;;     :player - Player chooses (returns tagged value for selection)
  ;;     :random - Random selection (moves random cards to hand)
  ;;
  ;; For :selection :random:
  ;;   Randomly selects up to :count cards from target's graveyard
  ;;   and moves them to target's hand. Returns plain db.
  ;;
  ;; For :selection :player (or default):
  ;;   Returns {:db db :needs-selection effect} — tagged value signaling
  ;;   that this effect requires player selection.
  [db player-id effect _object-id]
  (let [target (get effect :effect/target player-id)
        target-player (cond
                        (= target :opponent) (q/get-opponent-id db player-id)
                        (= target :self) player-id
                        :else target)
        selection (get effect :effect/selection :player)
        count-limit (get effect :effect/count 0)]
    (case selection
      :random (let [gy-cards (or (q/get-objects-in-zone db target-player :graveyard) [])
                    selected (take count-limit (shuffle gy-cards))]
                (reduce (fn [db' obj]
                          (zones/move-to-zone db' (:object/id obj) :hand))
                        db
                        selected))
      ;; :player and default - signal need for player selection
      {:db db :needs-selection effect})))


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
  [db _player-id effect _object-id]
  (let [target-id (:effect/target effect)]
    (if (q/get-object-eid db target-id)
      (zones/move-to-zone db target-id :graveyard)
      ;; Invalid target: no-op
      db)))


(defn counter-target-spell
  "Counter a spell on the stack — move it to the appropriate zone.

   Zone transitions:
   - Copies cease to exist (removed from db)
   - Flashback spells go to exile (MTG rule)
   - All other spells go to graveyard (including permanents)

   Stack-item cleanup is handled automatically by zones/move-to-zone
   and zones/remove-object when the object leaves the :stack zone.

   Returns db. No-op if target is nil, doesn't exist, or not on stack."
  [db target-id]
  (if-not target-id
    db
    (if-let [obj (q/get-object db target-id)]
      (if (not= :stack (:object/zone obj))
        db
        (if (:object/is-copy obj)
          (zones/remove-object db target-id)
          (let [cast-mode (:object/cast-mode obj)
                mode-destination (:mode/on-resolve cast-mode)
                destination (if (= :exile mode-destination) :exile :graveyard)]
            (zones/move-to-zone db target-id destination))))
      db)))


(defmethod execute-effect-impl :counter-spell
  ;; Counter target spell — remove it from the stack without resolving.
  ;;
  ;; Effect keys:
  ;;   :effect/target - Target object-id (pre-resolved by caller via
  ;;                    stack/resolve-effect-target from :effect/target-ref)
  ;;   :effect/unless-pay - (optional) Mana cost map the targeted spell's
  ;;                        controller may pay to prevent the counter.
  ;;                        When present, returns {:db db :needs-selection effect}
  ;;                        to trigger a payment choice for the target's controller.
  ;;
  ;; Zone transitions for countered spells:
  ;;   - Copies cease to exist (removed from db)
  ;;   - Flashback spells go to exile (MTG rule: flashback exiles on leave-stack)
  ;;   - All other spells go to graveyard (including permanents)
  ;;
  ;; Handles edge cases:
  ;;   - No target: no-op (returns db unchanged)
  ;;   - Target doesn't exist: no-op
  ;;   - Target not on stack: no-op
  [db _player-id effect _object-id]
  (let [target-id (:effect/target effect)
        unless-pay (:effect/unless-pay effect)]
    (if-not target-id
      db
      (if-let [target-obj (q/get-object db target-id)]
        (if (not= :stack (:object/zone target-obj))
          db
          (if unless-pay
            ;; Soft counter: enrich with target's controller and signal selection
            (let [controller-eid (:db/id (:object/controller target-obj))
                  controller-id (when controller-eid
                                  (:player/id (d/pull db [:player/id] controller-eid)))]
              {:db db :needs-selection (assoc effect :unless-pay/controller controller-id)})
            ;; Hard counter: counter immediately
            (counter-target-spell db target-id)))
        db))))


(defmethod execute-effect-impl :counter-ability
  ;; Counter target activated or triggered ability — remove it from the stack.
  ;;
  ;; Effect keys:
  ;;   :effect/target - Target stack-item EID (entity ID, not object-id)
  ;;                    Caller resolves from :effect/target-ref via targeting
  ;;
  ;; Excludes mana abilities:
  ;;   Mana abilities (with :stack-item/ability-type :mana) cannot be targeted.
  ;;   If target is a mana ability, this is a no-op.
  ;;
  ;; Handles edge cases:
  ;;   - No target: no-op (returns db unchanged)
  ;;   - Target doesn't exist: no-op
  ;;   - Target is not :activated-ability or :triggered-ability: no-op
  ;;   - Target is mana ability: no-op (mana abilities can't be countered)
  [db _player-id effect _object-id]
  (let [target-eid (:effect/target effect)]
    (if-not target-eid
      db
      ;; Pull the stack-item to check its type and ability-type
      (if-let [stack-item (d/pull db '[*] target-eid)]
        (let [item-type (:stack-item/type stack-item)
              ability-type (:stack-item/ability-type stack-item)]
          (cond
            ;; Only counter :activated-ability or :triggered-ability items
            (not (or (= :activated-ability item-type)
                     (= :triggered-ability item-type)))
            db
            ;; Mana abilities cannot be countered (MTG rule)
            (= :mana ability-type)
            db
            ;; Valid target: remove it from stack
            :else
            (stack/remove-stack-item db target-eid)))
        db))))


(defmethod execute-effect-impl :destroy
  ;; Destroy target permanent - move it to owner's graveyard.
  ;;
  ;; Effect keys:
  ;;   :effect/target - Target object-id (pre-resolved by caller via
  ;;                    stack/resolve-effect-target)
  ;;
  ;; Handles edge cases:
  ;;   - No target found: no-op (returns db unchanged)
  ;;   - Target object doesn't exist: no-op
  ;;
  ;; Note: Goes to OWNER's graveyard, not controller's. This matters
  ;; for stolen permanents (Control Magic effects). The zones system
  ;; handles this correctly since zones are queried by :object/owner.
  [db _player-id effect _object-id]
  (let [target-id (:effect/target effect)]
    (if-not target-id
      db  ; No target found - no-op
      (if-let [_target-obj (q/get-object db target-id)]
        (zones/move-to-zone db target-id :graveyard)
        db))))  ; Target doesn't exist - no-op


(defmethod execute-effect-impl :discard
  ;; Discard cards from a player's hand to their graveyard.
  ;;
  ;; Effect keys:
  ;;   :effect/count - Number of cards to discard
  ;;   :effect/selection - How to select cards:
  ;;     :player - Player chooses (interactive, returns tagged value)
  ;;     :random - Random selection (not implemented yet)
  ;;     nil - Defaults to :player for now
  ;;
  ;; When :selection is :player:
  ;;   Returns {:db db :needs-selection effect} — tagged value signaling
  ;;   that this effect requires player selection to complete. The
  ;;   resolution layer builds the selection UI from the effect data.
  [db _player-id effect _object-id]
  (let [selection (get effect :effect/selection :player)]
    (case selection
      :player {:db db :needs-selection effect}
      {:db db :needs-selection effect})))


(defmethod execute-effect-impl :tutor
  ;; Search library for a card matching criteria.
  ;;
  ;; Effect keys:
  ;;   :effect/criteria - Map of card attributes to match (types, colors, subtypes)
  ;;   :effect/target-zone - Zone to move found card to (:hand for Merchant Scroll)
  ;;   :effect/shuffle? - Whether to shuffle after (default true)
  ;;
  ;; Returns {:db db :needs-selection effect} — tagged value signaling
  ;; that this effect requires player selection (library search).
  ;; Player must always have fail-to-find option (rules: hidden information).
  [db _player-id effect _object-id]
  {:db db :needs-selection effect})


(defmethod execute-effect-impl :scry
  ;; Look at top N cards of library and rearrange them.
  ;;
  ;; Effect keys:
  ;;   :effect/amount - Number of cards to scry (default 0)
  ;;
  ;; Returns {:db db :needs-selection effect} for scry > 0,
  ;; or plain db for scry 0/negative (no-op, no selection needed).
  ;;
  ;; Edge cases:
  ;;   - amount <= 0: No-op (returns db unchanged, no selection needed)
  ;;   - amount > library size: Scry available cards only (handled in selection setup)
  ;;   - missing amount: Defaults to 0 (no-op)
  [db _player-id effect _object-id]
  (let [amount (or (:effect/amount effect) 0)]
    (if (<= amount 0)
      db  ; No-op for scry 0, negative, or missing amount
      {:db db :needs-selection effect})))


(defmethod execute-effect-impl :peek-and-select
  ;; Look at top N cards of library, select some for hand, rest to bottom.
  ;;
  ;; Effect keys:
  ;;   :effect/count - Number of cards to peek (default 0)
  ;;   :effect/select-count - Number to select for hand (default 1)
  ;;   :effect/selected-zone - Zone for selected cards (default :hand)
  ;;   :effect/remainder-zone - Zone for non-selected (default :bottom-of-library)
  ;;   :effect/shuffle-remainder? - Whether to shuffle remainder (default false)
  ;;
  ;; Returns {:db db :needs-selection effect} — tagged value signaling
  ;; that this effect requires player selection (choose from revealed cards).
  [db _player-id effect _object-id]
  {:db db :needs-selection effect})


(defmethod execute-effect-impl :peek-and-reorder
  ;; Look at top N cards of a player's library, put all back in any order.
  ;; Distinct from scry (top/bottom split) and peek-and-select (some to hand).
  ;;
  ;; Effect keys:
  ;;   :effect/count - Number of cards to peek (default 0)
  ;;   :effect/target - Target player-id (pre-resolved from :effect/target-ref)
  ;;
  ;; Returns {:db db :needs-selection effect} — tagged value signaling
  ;; that this effect requires player selection (reorder cards in library).
  [db _player-id effect _object-id]
  {:db db :needs-selection effect})


(defmethod execute-effect-impl :grant-flashback
  ;; Grant flashback to a target card.
  ;;
  ;; Effect keys:
  ;;   :effect/target - Target object-id (pre-resolved by caller via
  ;;                    stack/resolve-effect-target from :effect/target-ref)
  ;;
  ;; This effect:
  ;;   1. Gets the target from :effect/target (pre-resolved)
  ;;   2. Gets the target card's mana cost
  ;;   3. Creates a grant with :alternate-cost type
  ;;   4. Adds the grant to the target object via grants/add-grant
  ;;
  ;; The grant expires at cleanup phase of the current turn ("until end of turn").
  ;;
  ;; Handles edge cases:
  ;;   - No target: no-op (returns db unchanged)
  ;;   - Target doesn't exist: no-op
  [db _player-id effect object-id]
  (let [target-id (:effect/target effect)]
    (if-not target-id
      db  ; No target - no-op
      (if-let [target-obj (q/get-object db target-id)]
        (let [target-card (:object/card target-obj)
              target-mana-cost (:card/mana-cost target-card)
              game-state (q/get-game-state db)
              current-turn (or (:game/turn game-state) 1)
              grant {:grant/id (random-uuid)
                     :grant/type :alternate-cost
                     :grant/source object-id
                     :grant/expires {:expires/turn current-turn
                                     :expires/phase :cleanup}
                     :grant/data {:alternate/id :granted-flashback
                                  :alternate/zone :graveyard
                                  :alternate/mana-cost target-mana-cost
                                  :alternate/on-resolve :exile}}]
          (grants/add-grant db target-id grant))
        db))))


(defmethod execute-effect-impl :grant-delayed-draw
  ;; Grant a delayed draw effect to a player.
  ;; The draw happens at the beginning of the next turn's upkeep.
  ;;
  ;; Effect keys:
  ;;   :effect/target - Target player-id (symbolic: :controller, :self, or resolved player-id)
  ;;
  ;; Creates a :delayed-effect grant with:
  ;;   - :delayed/phase :upkeep (when to fire)
  ;;   - :delayed/effect {:effect/type :draw :effect/amount 1} (what to do)
  ;;   - Expires at the upkeep phase of next turn (turn N+1)
  ;;
  ;; Handles edge cases:
  ;;   - No target: defaults to :controller (the spell's controller)
  [db player-id effect object-id]
  (let [target (:effect/target effect)
        target-player (case target
                        :controller player-id
                        :self player-id
                        target)
        game-state (q/get-game-state db)
        current-turn (or (:game/turn game-state) 1)
        next-turn (inc current-turn)
        source-name (when object-id
                      (:card/name (:object/card (q/get-object db object-id))))
        grant {:grant/id (random-uuid)
               :grant/type :delayed-effect
               :grant/source object-id
               :grant/expires {:expires/turn next-turn
                               :expires/phase :upkeep}
               :grant/data {:delayed/phase :upkeep
                            :delayed/description (str (or source-name "Delayed trigger")
                                                      " — Draw a card")
                            :delayed/effect {:effect/type :draw
                                             :effect/amount 1}}}]
    (grants/add-player-grant db target-player grant)))


(defmethod execute-effect-impl :add-restriction
  ;; Add a restriction grant to a target player.
  ;;
  ;; Effect keys:
  ;;   :restriction/type - Type of restriction (:cannot-cast-spells, :cannot-attack)
  ;;   :effect/target - Target player (pre-resolved by caller for :targeted-player,
  ;;                    or symbolic: :self, :opponent, or direct player-id keyword)
  ;;
  ;; Grant expires at end of current turn (cleanup phase).
  ;;
  ;; Handles edge cases:
  ;;   - Invalid target: no-op (returns db unchanged)
  ;;   - Missing game turn: defaults to turn 1
  [db player-id effect source-object-id]
  (let [;; Resolve target player
        target-raw (:effect/target effect)
        target-player (case target-raw
                        :self player-id
                        :opponent (q/get-opponent-id db player-id)
                        ;; Default: use as-is or fall back to player-id
                        (or target-raw player-id))
        ;; Get current turn for expiration
        game (q/get-game-state db)
        current-turn (or (:game/turn game) 1)]
    ;; Guard: invalid player is no-op
    (if (q/get-player-eid db target-player)
      (let [grant {:grant/id (random-uuid)
                   :grant/type :restriction
                   :grant/source source-object-id
                   :grant/expires {:expires/turn current-turn :expires/phase :cleanup}
                   :grant/data {:restriction/type (:restriction/type effect)}}]
        (grants/add-player-grant db target-player grant))
      db)))


(defmethod execute-effect-impl :exile-zone
  ;; Exile all objects in a zone for a target player.
  ;;
  ;; Effect keys:
  ;;   :effect/target - Target player (pre-resolved by caller)
  ;;   :effect/zone - Zone to exile from (:graveyard, :hand, etc.)
  ;;
  ;; Moves every object the target player owns in the specified zone
  ;; to exile. Used by Tormod's Crypt (exile target player's graveyard).
  ;;
  ;; Handles edge cases:
  ;;   - Empty zone: no-op (returns db unchanged)
  ;;   - Invalid player: no-op (returns db unchanged)
  ;;   - Missing zone key: no-op (returns db unchanged)
  [db _player-id effect _object-id]
  (let [target-player (:effect/target effect)
        zone (:effect/zone effect)]
    (if-not (and target-player zone (q/get-player-eid db target-player))
      db
      (let [zone-objects (or (q/get-objects-in-zone db target-player zone) [])]
        (reduce (fn [db' obj]
                  (zones/move-to-zone db' (:object/id obj) :exile))
                db
                zone-objects)))))


(defmethod execute-effect-impl :gain-life-equal-to-cmc
  ;; Give a target object's controller life equal to its converted mana cost.
  ;;
  ;; Effect keys:
  ;;   :effect/target - Target object-id (pre-resolved by caller via
  ;;                    stack/resolve-effect-target from :effect/target-ref)
  ;;
  ;; Looks up the target object, reads its card's :card/cmc, and gives
  ;; that much life to the object's controller. Used by Crumble.
  ;;
  ;; Handles edge cases:
  ;;   - No target: no-op (returns db unchanged)
  ;;   - Target doesn't exist: no-op
  ;;   - CMC is 0: no-op (gain 0 life does nothing)
  ;;   - Target already destroyed (in graveyard): still works, object data persists
  [db _player-id effect _object-id]
  (let [target-id (:effect/target effect)]
    (if-not target-id
      db
      (if-let [target-obj (q/get-object db target-id)]
        (let [card (:object/card target-obj)
              cmc (or (:card/cmc card) 0)
              ;; Controller is a ref {:db/id N} from get-object pull;
              ;; resolve to player-id via d/pull
              controller-eid (:db/id (:object/controller target-obj))
              controller-id (when controller-eid
                              (:player/id (d/pull db [:player/id] controller-eid)))]
          (if (or (<= cmc 0) (nil? controller-id))
            db
            (let [current-life (q/get-life-total db controller-id)
                  new-life (+ current-life cmc)]
              (d/db-with db [[:db/add controller-eid :player/life new-life]]))))
        db))))


(defmethod execute-effect-impl :discard-from-revealed-hand
  ;; Reveal target player's hand and choose a card matching criteria to discard.
  ;;
  ;; Effect keys:
  ;;   :effect/target - Target player (pre-resolved by resolution layer)
  ;;   :effect/criteria - Match criteria for selectable cards
  ;;                      (e.g., {:match/not-types #{:creature :land}})
  ;;
  ;; Always returns tagged value for player selection. The selection builder
  ;; shows the FULL hand (all cards visible) but only cards matching criteria
  ;; are selectable. Used by Duress.
  ;;
  ;; Edge cases:
  ;;   - Empty hand: still returns needs-selection (selection resolves with no pick)
  ;;   - No valid cards: selection allows 0 picks (no discard)
  [db _player-id effect _object-id]
  {:db db :needs-selection effect})


(defmethod execute-effect-impl :bounce
  ;; Return target permanent to its owner's hand.
  ;;
  ;; Effect keys:
  ;;   :effect/target - Object ID (UUID) of the permanent to return
  ;;                    (pre-resolved by caller via stack/resolve-effect-target)
  ;;
  ;; Handles edge cases:
  ;;   - No target: no-op (returns db unchanged)
  ;;   - Target doesn't exist: no-op (returns db unchanged)
  ;;   - Target not on battlefield: still moves to hand (zones handles it)
  [db _player-id effect _object-id]
  (let [target-id (:effect/target effect)]
    (if-not target-id
      db
      (if (q/get-object-eid db target-id)
        (zones/move-to-zone db target-id :hand)
        db))))


(defmethod execute-effect-impl :peek-random-hand
  ;; Look at a card at random in target player's hand.
  ;; Picks a random card and stores the name on :game/peek-result for the
  ;; history log. No cards change zones — peek is informational only.
  ;;
  ;; Effect keys:
  ;;   :effect/target - Target player-id (pre-resolved from :effect/target-ref)
  ;;
  ;; Handles edge cases:
  ;;   - Empty hand: no-op (returns db unchanged, no peek-result stored)
  ;;   - Invalid player: no-op (returns db unchanged)
  [db _player-id effect _object-id]
  (let [target-player (:effect/target effect)]
    (if target-player
      (let [hand (q/get-hand db target-player)]
        (if (seq hand)
          (let [card (rand-nth hand)
                card-name (get-in card [:object/card :card/name])
                game-eid (d/q '[:find ?g . :where [?g :game/id _]] db)]
            (d/db-with db [[:db/add game-eid :game/peek-result card-name]]))
          db))
      db)))


(defmethod execute-effect-impl :chain-bounce
  ;; Chain mechanic for Chain of Vapor.
  ;; The bounced permanent's controller may sacrifice a land to create a copy.
  ;;
  ;; Effect keys:
  ;;   :effect/target - Object ID of the bounced permanent (pre-resolved)
  ;;
  ;; The target has already been bounced to hand by the :bounce effect.
  ;; This effect enriches the effect with chain data (controller identity)
  ;; and signals :needs-selection. The selection builder handles edge cases
  ;; (no lands = auto-decline, no controller = auto-decline).
  ;;
  ;; Always returns needs-selection when target exists, consistent with
  ;; other interactive effects. The selection layer handles all edge cases.
  ;;
  ;; Edge cases:
  ;;   - No target: no-op (returns db unchanged)
  ;;   - Target doesn't exist: signals needs-selection (builder handles)
  ;;   - Controller has no lands: selection auto-declines (builder handles)
  [db _player-id effect _object-id]
  (let [target-id (:effect/target effect)]
    (if-not target-id
      db
      (let [target-obj (q/get-object db target-id)
            controller-eid (when target-obj
                             (:db/id (:object/controller target-obj)))
            controller-id (when controller-eid
                            (:player/id (d/pull db [:player/id] controller-eid)))]
        {:db db :needs-selection (cond-> effect
                                   controller-id (assoc :chain/controller controller-id)
                                   target-id (assoc :chain/target-id target-id))}))))
