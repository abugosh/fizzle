(ns fizzle.events.cleanup
  "Cleanup step (MTG Rule 514): discard to hand size, expire grants.
   Pure functions used by phase advancement and resolution."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.zones :as zones]))


(defn- clear-damage-marks
  "Clear :object/damage-marked on all creatures on the battlefield."
  [db]
  (let [damaged (queries/q-safe '[:find ?e ?dmg
                                  :where [?e :object/zone :battlefield]
                                  [?e :object/damage-marked ?dmg]
                                  [(> ?dmg 0)]]
                                db)]
    (if (seq damaged)
      (d/db-with db (mapv (fn [[eid _]] [:db/add eid :object/damage-marked 0])
                          damaged))
      db)))


;; === Cleanup Step (MTG Rule 514) ===
;; Cleanup is a multi-step process:
;;   1. Discard to hand size (514.1) - interactive, may require player selection
;;   2. Expire grants / "until end of turn" effects (514.2)
;; Grant expiration is NOT an auto-trigger; it's called explicitly after discard.

(defn- build-cleanup-discard-selection
  "Build pending selection state for cleanup discard-to-hand-size.
   Uses unified :discard type with :selection/cleanup? flag."
  [player-id discard-count]
  {:selection/zone :hand
   :selection/card-source :hand
   :selection/select-count discard-count
   :selection/player-id player-id
   :selection/selected #{}
   :selection/type :discard
   :selection/lifecycle :finalized
   :selection/cleanup? true
   :selection/validation :exact
   :selection/auto-confirm? false})


(defn begin-cleanup
  "Begin the cleanup step on a db already at :cleanup phase.
   Checks hand size against max and either:
   - Returns {:db db'} if no discard needed (grants already expired)
   - Returns {:db db' :pending-selection selection} if discard needed

   Caller is responsible for advancing phase to :cleanup first.
   Grant expiration is only done if no discard is needed;
   otherwise it happens after discard confirmation.

   Pure function: (db, player-id) -> {:db db, :pending-selection ...}"
  [db player-id]
  (let [hand (queries/get-hand db player-id)
        hand-count (count hand)
        max-hand-size (queries/get-max-hand-size db player-id)
        discard-count (- hand-count max-hand-size)]
    (if (pos? discard-count)
      ;; Need to discard - don't expire grants yet (Rule 514.1 before 514.2)
      {:db db
       :pending-selection (build-cleanup-discard-selection player-id discard-count)}
      ;; No discard needed - expire grants immediately (Rule 514.2), clear damage
      (let [game-state (queries/get-game-state db)
            current-turn (:game/turn game-state)]
        {:db (-> db
                 (grants/expire-grants current-turn :cleanup)
                 (clear-damage-marks))}))))


(defn complete-cleanup-discard
  "Complete the cleanup discard step: move selected cards to graveyard,
   then expire grants.
   Pure function: (db, player-id, selected-ids) -> {:db db}"
  [db _player-id selected-ids]
  (let [;; Move selected cards to graveyard
        db-after-discard (reduce (fn [d obj-id]
                                   (zones/move-to-zone d obj-id :graveyard))
                                 db
                                 selected-ids)
        ;; Now expire grants (Rule 514.2 - after discard) and clear damage
        game-state (queries/get-game-state db-after-discard)
        current-turn (:game/turn game-state)]
    {:db (-> db-after-discard
             (grants/expire-grants current-turn :cleanup)
             (clear-damage-marks))}))


(defn maybe-continue-cleanup
  "After stack resolution during cleanup, check if cleanup should restart.
   If stack is now empty, phase is :cleanup, and no pending selection,
   re-runs begin-cleanup to recheck hand size and re-expire grants.
   Pure function: (app-db) -> app-db"
  [app-db]
  (let [game-db (:game/db app-db)]
    (if (and (= :cleanup (:game/phase (queries/get-game-state game-db)))
             (queries/stack-empty? game-db)
             (not (:game/pending-selection app-db)))
      (let [active-player-id (queries/get-active-player-id game-db)
            result (begin-cleanup game-db active-player-id)]
        (if (:pending-selection result)
          (-> app-db
              (assoc :game/db (:db result))
              (assoc :game/pending-selection (:pending-selection result)))
          (assoc app-db :game/db (:db result))))
      app-db)))
