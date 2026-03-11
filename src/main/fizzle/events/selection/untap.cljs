(ns fizzle.events.selection.untap
  "Untap-lands selection domain.

   Handles player selection of tapped lands to untap. Used by:
   - Frantic Search (untap up to 3 lands)
   - Cloud of Faeries ETB trigger (untap up to 2 lands)

   Uses custom builder (not zone-pick) because candidates are filtered
   by :object/tapped state, not just zone membership. Uses custom
   executor because untap changes :object/tapped in place rather than
   moving cards between zones."
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.events.lands :as lands]
    [fizzle.events.selection.core :as core]))


(defn build-untap-lands-selection
  "Build pending selection state for an untap-lands effect.
   Finds all tapped lands controlled by the casting player on the battlefield.

   Uses :at-most validation — player may select 0 to N lands."
  [game-db player-id object-id effect remaining-effects]
  (let [count-limit (:effect/count effect)
        ;; Find tapped lands controlled by the casting player
        bf-objects (or (queries/get-objects-in-zone game-db player-id :battlefield) [])
        tapped-lands (filterv (fn [obj]
                                (and (:object/tapped obj)
                                     (contains? (set (get-in obj [:object/card :card/types] #{}))
                                                :land)))
                              bf-objects)
        candidate-ids (set (mapv :object/id tapped-lands))]
    {:selection/type :untap-lands
     :selection/zone :battlefield
     :selection/card-source :zone
     :selection/select-count count-limit
     :selection/player-id player-id
     :selection/selected #{}
     :selection/spell-id object-id
     :selection/remaining-effects remaining-effects
     :selection/candidate-ids candidate-ids
     :selection/min-count 0
     :selection/validation :at-most
     :selection/auto-confirm? false}))


(defmethod core/build-selection-for-effect :untap-lands
  [db player-id object-id effect remaining]
  (build-untap-lands-selection db player-id object-id effect remaining))


(defmethod core/execute-confirmed-selection :untap-lands
  [game-db selection]
  (let [selected (:selection/selected selection)]
    {:db (reduce lands/untap-permanent game-db selected)}))
