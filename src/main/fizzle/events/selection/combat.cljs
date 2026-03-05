(ns fizzle.events.selection.combat
  "Combat selection types: attacker and blocker selection."
  (:require
    [fizzle.engine.combat :as combat]
    [fizzle.engine.stack :as stack]
    [fizzle.events.selection.core :as sel-core]))


(defn build-attacker-selection
  [eligible-attackers controller stack-item-eid]
  {:selection/type :select-attackers
   :selection/lifecycle :finalized
   :selection/source-type :stack-item
   :selection/stack-item-eid stack-item-eid
   :selection/player-id controller
   :selection/selected #{}
   :selection/valid-targets (vec eligible-attackers)
   :selection/validation :at-most
   :selection/select-count (count eligible-attackers)
   :selection/auto-confirm? false})


(defmethod sel-core/execute-confirmed-selection :select-attackers
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
                                         :stack-item/description "Declare Blockers"})]
        {:db db}))))


(defn build-blocker-selection
  [db remaining-attackers defender-id stack-item-eid]
  (let [current-attacker (first remaining-attackers)
        eligible (combat/get-eligible-blockers db defender-id current-attacker)]
    {:selection/type :assign-blockers
     :selection/lifecycle :chaining
     :selection/source-type :stack-item
     :selection/stack-item-eid stack-item-eid
     :selection/player-id defender-id
     :selection/current-attacker current-attacker
     :selection/remaining-attackers (vec (rest remaining-attackers))
     :selection/selected #{}
     :selection/valid-targets (vec eligible)
     :selection/validation :at-most
     :selection/select-count (count eligible)
     :selection/auto-confirm? (empty? eligible)}))


(defmethod sel-core/execute-confirmed-selection :assign-blockers
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
