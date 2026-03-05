(ns fizzle.events.selection.combat
  "Combat selection types: attacker selection.

   Attacker selection is a finalized selection with :source-type :stack-item.
   The executor taps/marks attackers and pushes declare-blockers + combat-damage
   stack items. When no attackers are selected, no follow-up items are pushed."
  (:require
    [fizzle.engine.combat :as combat]
    [fizzle.engine.stack :as stack]
    [fizzle.events.selection.core :as sel-core]))


(defn build-attacker-selection
  "Build selection state for choosing attackers.
   eligible-attackers: vector of object-ids that can legally attack.
   controller: player-id who controls the attacking creatures.
   stack-item-eid: eid of the :declare-attackers stack-item to remove after."
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
        ;; Remove the :declare-attackers stack-item
        db (stack/remove-stack-item game-db si-eid)]
    (if (empty? attacker-ids)
      ;; No attackers — skip rest of combat
      {:db db}
      ;; Tap and mark attackers, push declare-blockers and combat-damage
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
