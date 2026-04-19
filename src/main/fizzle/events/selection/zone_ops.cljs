(ns fizzle.events.selection.zone-ops
  "Zone operation selection domains: graveyard-return, chain-bounce,
   shuffle-from-graveyard-to-library, and custom discard executor.

   Discard builder: handled by generic zone-pick builder in core.cljs
   via hierarchy (derive :discard :zone-pick). Custom executor remains
   here because cleanup discard checks :selection/cleanup? flag:
     - false/nil → standard discard (wrapper handles remaining-effects)
     - true → cleanup discard (expire grants, return finalized)"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.effects.stack :as effects-stack]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.triggers :as triggers]
    [fizzle.engine.zone-change-dispatch :as zone-change-dispatch]
    [fizzle.engine.zones :as zones]
    [fizzle.events.selection.core :as core]))


;; =====================================================
;; Selection Builders
;; =====================================================

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
                                   (zone-change-dispatch/move-to-zone gdb obj-id :graveyard))
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


(defmethod core/execute-confirmed-selection :hand-reveal-discard
  [game-db selection]
  (let [selected (:selection/selected selection)]
    (if (empty? selected)
      ;; No valid card chosen (or no valid cards in hand) — no discard
      {:db game-db}
      ;; Discard the selected card to graveyard
      {:db (reduce (fn [gdb obj-id]
                     (zone-change-dispatch/move-to-zone gdb obj-id :graveyard))
                   game-db
                   selected)})))


(defmethod core/execute-confirmed-selection :shuffle-from-graveyard-to-library
  [game-db selection]
  ;; Custom executor: moves selected graveyard cards to library, then shuffles.
  ;; The generic zone-pick executor only moves cards without shuffling.
  ;; 0 selected is valid ("up to N" — can choose none).
  (let [selected (:selection/selected selection)
        target-player (:selection/player-id selection)
        db-moved (reduce (fn [gdb obj-id]
                           (zone-change-dispatch/move-to-zone gdb obj-id :library))
                         game-db
                         selected)]
    {:db (zones/shuffle-library db-moved target-player)}))


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
     :selection/lifecycle :chaining
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


(defmethod core/build-chain-selection :chain-bounce
  [db selection]
  (let [selected (:selection/selected selection)]
    (when (seq selected)
      ;; Find the copy created by the executor (topmost stack item)
      (let [copy-stack-item (stack/get-top-stack-item db)]
        (when copy-stack-item
          (let [chain-controller (:selection/chain-controller selection)
                spell-id (:selection/spell-id selection)
                copy-obj-ref (let [raw (:stack-item/object-ref copy-stack-item)]
                               (if (map? raw) (:db/id raw) raw))
                copy-object-id (when copy-obj-ref
                                 (:object/id (d/pull db [:object/id] copy-obj-ref)))]
            (build-chain-bounce-target-selection
              db chain-controller spell-id
              copy-object-id (:db/id copy-stack-item))))))))


(defmethod core/execute-confirmed-selection :chain-bounce
  [game-db selection]
  (let [selected (:selection/selected selection)]
    (if (empty? selected)
      ;; Declined — chain ends, no copy
      {:db game-db}
      ;; Sacrifice the selected land and create a copy on the stack
      (let [land-id (first selected)
            chain-controller (:selection/chain-controller selection)
            spell-id (:selection/spell-id selection)
            db-after-sac (zone-change-dispatch/move-to-zone game-db land-id :graveyard)
            db-with-copy (triggers/create-spell-copy
                           db-after-sac spell-id chain-controller)]
        {:db db-with-copy}))))


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
      {:db (effects-stack/counter-target-spell game-db target-id)})))
