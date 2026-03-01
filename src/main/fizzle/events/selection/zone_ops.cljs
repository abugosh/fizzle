(ns fizzle.events.selection.zone-ops
  "Zone operation selection domains: discard (unified), graveyard-return,
   and chain-bounce (Chain of Vapor chain mechanic).

   Discard unification: :cleanup-discard is removed as a separate type.
   The :discard type now checks :selection/cleanup? flag:
     - false/nil → standard discard (wrapper handles remaining-effects)
     - true → cleanup discard (expire grants, return finalized)"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.triggers :as triggers]
    [fizzle.engine.zones :as zones]
    [fizzle.events.selection.core :as core]))


;; =====================================================
;; Selection Builders
;; =====================================================

(defn build-discard-selection
  "Build pending selection state for a discard effect."
  [player-id object-id discard-effect effects-after]
  {:selection/zone :hand
   :selection/card-source :hand
   :selection/select-count (:effect/count discard-effect)
   :selection/player-id player-id
   :selection/selected #{}
   :selection/spell-id object-id
   :selection/remaining-effects effects-after
   :selection/type :discard
   :selection/validation :exact
   :selection/auto-confirm? false})


(defn build-graveyard-selection
  "Build pending selection state for returning cards from graveyard.
   Unlike tutor (hidden zone), graveyard is public - no fail-to-find option.
   Player can select 0 to :effect/count cards."
  [game-db player-id object-id effect effects-after]
  (let [target (get effect :effect/target player-id)
        target-player (cond
                        (= target :opponent) (queries/get-opponent-id game-db player-id)
                        (= target :self) player-id
                        :else target)
        gy-cards (or (queries/get-objects-in-zone game-db target-player :graveyard) [])
        candidate-ids (set (map :object/id gy-cards))]
    {:selection/type :graveyard-return
     :selection/zone :graveyard
     :selection/card-source :zone
     :selection/select-count (:effect/count effect)
     :selection/min-count 0
     :selection/player-id target-player
     :selection/selected #{}
     :selection/spell-id object-id
     :selection/remaining-effects effects-after
     :selection/candidate-ids candidate-ids
     :selection/validation :at-most
     :selection/auto-confirm? false}))


(defn build-hand-reveal-discard-selection
  "Build pending selection state for a hand-reveal discard effect.
   Shows the FULL hand of the target player (all cards visible) but only
   cards matching :effect/criteria are selectable. The caster (player-id)
   is the one who chooses.

   When no valid cards exist (e.g., hand is all creatures/lands),
   uses :exact-or-zero validation so the caster can confirm with 0 selected."
  [game-db player-id object-id effect effects-after]
  (let [target-player (:effect/target effect)
        criteria (or (:effect/criteria effect) {})
        hand-cards (or (queries/get-objects-in-zone game-db target-player :hand) [])
        selectable (filterv #(queries/matches-criteria? % criteria) hand-cards)
        selectable-ids (set (map :object/id selectable))]
    {:selection/type :hand-reveal-discard
     :selection/card-source :opponent-hand
     :selection/target-player target-player
     :selection/select-count 1
     :selection/player-id player-id
     :selection/selected #{}
     :selection/spell-id object-id
     :selection/remaining-effects effects-after
     :selection/valid-targets (vec selectable-ids)
     :selection/validation :exact-or-zero
     :selection/auto-confirm? (empty? selectable-ids)}))


;; =====================================================
;; Builder Multimethod Registrations
;; =====================================================

(defmethod core/build-selection-for-effect :discard
  [_db player-id object-id effect remaining]
  (build-discard-selection player-id object-id effect remaining))


(defmethod core/build-selection-for-effect :return-from-graveyard
  [db player-id object-id effect remaining]
  (build-graveyard-selection db player-id object-id effect remaining))


(defmethod core/build-selection-for-effect :discard-from-revealed-hand
  [db player-id object-id effect remaining]
  (build-hand-reveal-discard-selection db player-id object-id effect remaining))


;; =====================================================
;; Confirm Selection Multimethod
;; =====================================================

(defmethod core/execute-confirmed-selection :discard
  [game-db selection]
  (let [selected (:selection/selected selection)
        db-after-discard (reduce (fn [gdb obj-id]
                                   (zones/move-to-zone gdb obj-id :graveyard))
                                 game-db
                                 selected)]
    (if (:selection/cleanup? selection)
      ;; Cleanup path: expire grants, lifecycle declared by builder
      (let [game-state (queries/get-game-state db-after-discard)
            current-turn (:game/turn game-state)
            db-final (grants/expire-grants db-after-discard current-turn :cleanup)]
        {:db db-final})
      ;; Standard path: wrapper handles remaining-effects
      {:db db-after-discard})))


(defmethod core/execute-confirmed-selection :graveyard-return
  [game-db selection]
  (let [selected (:selection/selected selection)]
    {:db (reduce (fn [gdb obj-id]
                   (zones/move-to-zone gdb obj-id :hand))
                 game-db
                 selected)}))


(defmethod core/execute-confirmed-selection :hand-reveal-discard
  [game-db selection]
  (let [selected (:selection/selected selection)]
    (if (empty? selected)
      ;; No valid card chosen (or no valid cards in hand) — no discard
      {:db game-db}
      ;; Discard the selected card to graveyard
      {:db (reduce (fn [gdb obj-id]
                     (zones/move-to-zone gdb obj-id :graveyard))
                   game-db
                   selected)})))


;; =====================================================
;; Chain Bounce Selection (Chain of Vapor chain mechanic)
;; =====================================================

(defn build-chain-bounce-selection
  "Build selection for Chain of Vapor's chain mechanic.
   The bounced permanent's controller may sacrifice a land to create a copy.
   Shows the controller's lands on the battlefield; they pick one (or decline).

   Uses :exact-or-zero validation: select 1 land (sacrifice) or 0 (decline).
   When controller has no lands or is nil, auto-confirms with 0 (chain ends)."
  [game-db _player-id object-id effect effects-after]
  (let [chain-controller (:chain/controller effect)
        chain-target-id (:chain/target-id effect)
        lands (when chain-controller
                (or (queries/query-zone-by-criteria
                      game-db chain-controller :battlefield
                      {:match/types #{:land}})
                    []))
        land-ids (set (mapv :object/id (or lands [])))]
    {:selection/type :chain-bounce
     :selection/zone :battlefield
     :selection/card-source :valid-targets
     :selection/select-count 1
     :selection/player-id chain-controller
     :selection/selected #{}
     :selection/spell-id object-id
     :selection/remaining-effects effects-after
     :selection/valid-targets (vec land-ids)
     :selection/chain-controller chain-controller
     :selection/chain-target-id chain-target-id
     :selection/validation :exact-or-zero
     :selection/auto-confirm? (empty? land-ids)}))


(defn- build-chain-bounce-target-selection
  "Build selection for choosing a new target for the chain copy.
   Shows all nonland permanents on the battlefield from any controller.
   The chain controller chooses the new target."
  [game-db chain-controller spell-id copy-object-id copy-stack-item-eid]
  (let [;; Find all nonland permanents on the battlefield (any controller)
        p1-permanents (or (queries/query-zone-by-criteria
                            game-db chain-controller :battlefield
                            {:match/not-types #{:land}})
                          [])
        opponent-id (queries/get-opponent-id game-db chain-controller)
        p2-permanents (if opponent-id
                        (or (queries/query-zone-by-criteria
                              game-db opponent-id :battlefield
                              {:match/not-types #{:land}})
                            [])
                        [])
        all-permanents (concat p1-permanents p2-permanents)
        target-ids (set (mapv :object/id all-permanents))]
    {:selection/type :chain-bounce-target
     :selection/zone :battlefield
     :selection/card-source :valid-targets
     :selection/select-count 1
     :selection/player-id chain-controller
     :selection/selected #{}
     :selection/spell-id spell-id
     :selection/chain-copy-object-id copy-object-id
     :selection/chain-copy-stack-item-eid copy-stack-item-eid
     :selection/valid-targets (vec target-ids)
     :selection/validation :exact-or-zero
     :selection/auto-confirm? (empty? target-ids)}))


(defmethod core/build-selection-for-effect :chain-bounce
  [db _player-id object-id effect remaining]
  (build-chain-bounce-selection db _player-id object-id effect remaining))


(defmethod core/execute-confirmed-selection :chain-bounce
  [game-db selection]
  (let [selected (:selection/selected selection)
        chain-controller (:selection/chain-controller selection)
        spell-id (:selection/spell-id selection)]
    (if (empty? selected)
      ;; Declined — chain ends, no copy
      {:db game-db}
      ;; Sacrifice the selected land
      (let [land-id (first selected)
            db-after-sac (zones/move-to-zone game-db land-id :graveyard)
            ;; Create a spell copy on the stack
            ;; Use nil target-override — we'll set the target via the next selection
            db-with-copy (triggers/create-spell-copy
                           db-after-sac spell-id chain-controller)
            ;; Find the copy we just created (topmost stack item)
            copy-stack-item (stack/get-top-stack-item db-with-copy)
            copy-obj-ref (when copy-stack-item
                           (let [raw (:stack-item/object-ref copy-stack-item)]
                             (if (map? raw) (:db/id raw) raw)))
            copy-object-id (when copy-obj-ref
                             (:object/id (d/pull db-with-copy [:object/id] copy-obj-ref)))]
        (if (nil? copy-stack-item)
          ;; Failed to create copy (source gone) — just return after sacrifice
          {:db db-after-sac}
          ;; Chain to target selection for the copy
          (let [target-sel (build-chain-bounce-target-selection
                             db-with-copy chain-controller spell-id
                             copy-object-id (:db/id copy-stack-item))]
            {:db db-with-copy
             :pending-selection target-sel}))))))


(defmethod core/execute-confirmed-selection :chain-bounce-target
  [game-db selection]
  (let [selected (:selection/selected selection)
        copy-si-eid (:selection/chain-copy-stack-item-eid selection)]
    (if (empty? selected)
      ;; No target chosen — remove the copy from the stack (fizzles)
      ;; Standard cleanup moves original spell to graveyard
      {:db (stack/remove-stack-item game-db copy-si-eid)}
      ;; Set the copy's target and leave it on the stack to resolve normally
      ;; Standard cleanup moves original spell to graveyard
      (let [target-id (first selected)
            db-with-target (d/db-with game-db
                                      [[:db/add copy-si-eid
                                        :stack-item/targets
                                        {:target-permanent target-id}]])]
        {:db db-with-target}))))


;; =====================================================
;; Unless-They-Pay Selection (Soft Counters)
;; =====================================================

(defn build-unless-pay-selection
  "Build selection for unless-they-pay counter choice.
   The targeted spell's controller chooses whether to pay mana to prevent
   the counter. Uses :pay or :decline as the selection options.

   The choosing player is the controller of the targeted spell,
   NOT the controller of the counterspell.

   Player ALWAYS sees the prompt (per anti-pattern: no auto-resolving).
   :selection/can-pay? tracks whether :pay is a valid choice."
  [_game-db _player-id object-id effect effects-after]
  (let [target-id (:effect/target effect)
        unless-pay (:effect/unless-pay effect)
        controller-id (:unless-pay/controller effect)]
    {:selection/type :unless-pay
     :selection/player-id controller-id
     :selection/selected #{}
     :selection/select-count 1
     :selection/valid-targets [:pay :decline]
     :selection/spell-id object-id
     :selection/remaining-effects effects-after
     :selection/counter-target-id target-id
     :selection/unless-pay-cost unless-pay
     :selection/validation :exact
     :selection/auto-confirm? true}))


(defmethod core/build-selection-for-effect :counter-spell
  [db player-id object-id effect remaining]
  (build-unless-pay-selection db player-id object-id effect remaining))


(defmethod core/execute-confirmed-selection :unless-pay
  [game-db selection]
  (let [selected (first (:selection/selected selection))
        target-id (:selection/counter-target-id selection)
        controller-id (:selection/player-id selection)
        unless-pay-cost (:selection/unless-pay-cost selection)]
    (if (= :pay selected)
      ;; Player chose to pay — spend mana, spell survives
      {:db (mana/pay-mana game-db controller-id unless-pay-cost)}
      ;; Player declined (or couldn't pay) — counter the spell
      {:db (effects/counter-target-spell game-db target-id)})))
