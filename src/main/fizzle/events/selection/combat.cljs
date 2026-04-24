(ns fizzle.events.selection.combat
  "Combat selection types: attacker and blocker selection."
  (:require
    [fizzle.engine.combat :as combat]
    [fizzle.engine.events :as game-events]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.events.selection.core :as sel-core]))


(defn build-attacker-selection
  [eligible-attackers controller stack-item-eid]
  {:selection/mechanism :n-slot-targeting
   :selection/domain    :select-attackers
   :selection/lifecycle :finalized
   :selection/source-type :stack-item
   :selection/stack-item-eid stack-item-eid
   :selection/player-id controller
   :selection/selected #{}
   :selection/valid-targets (vec eligible-attackers)
   :selection/card-source :valid-targets
   :selection/validation :at-most
   :selection/select-count (count eligible-attackers)
   :selection/auto-confirm? false})


(defmethod sel-core/apply-domain-policy :select-attackers
  [game-db selection]
  (let [selected (:selection/selected selection)
        attacker-ids (vec selected)
        controller (:selection/player-id selection)
        si-eid (:selection/stack-item-eid selection)
        db (stack/remove-stack-item game-db si-eid)]
    (if (empty? attacker-ids)
      {:db db}
      (let [db (combat/tap-and-mark-attackers db attacker-ids)
            db (stack/create-stack-item db
                                        {:stack-item/type :combat-damage
                                         :stack-item/controller controller
                                         :stack-item/description "Combat Damage"})
            db (stack/create-stack-item db
                                        {:stack-item/type :declare-blockers
                                         :stack-item/controller controller
                                         :stack-item/description "Declare Blockers"})
            ;; Dispatch attack triggers AFTER creating combat stack items
            ;; so triggers go on top of stack (resolve before blockers)
            db (reduce (fn [d atk-id]
                         (dispatch/dispatch-event d
                                                  (game-events/creature-attacked-event atk-id controller)))
                       db attacker-ids)]
        {:db db}))))


(defn build-blocker-selection
  [db remaining-attackers defender-id stack-item-eid]
  (let [current-attacker (first remaining-attackers)
        eligible (combat/get-eligible-blockers db defender-id current-attacker)]
    {:selection/mechanism :n-slot-targeting
     :selection/domain    :assign-blockers
     :selection/lifecycle :chaining
     :selection/source-type :stack-item
     :selection/stack-item-eid stack-item-eid
     :selection/player-id defender-id
     :selection/current-attacker current-attacker
     :selection/remaining-attackers (vec (rest remaining-attackers))
     :selection/selected #{}
     :selection/valid-targets (vec eligible)
     :selection/card-source :valid-targets
     :selection/validation :at-most
     :selection/select-count (count eligible)
     :selection/auto-confirm? (empty? eligible)}))


(defmethod sel-core/apply-domain-policy :assign-blockers
  [game-db selection]
  (let [selected (:selection/selected selection)
        blocker-ids (vec selected)
        attacker-id (:selection/current-attacker selection)
        db (combat/mark-blockers game-db blocker-ids attacker-id)]
    {:db db}))


(defmethod sel-core/build-chain-selection :assign-blockers
  [db selection]
  (let [remaining (:selection/remaining-attackers selection)
        si-eid (:selection/stack-item-eid selection)
        defender-id (:selection/player-id selection)]
    (when (seq remaining)
      (build-blocker-selection db remaining defender-id si-eid))))
