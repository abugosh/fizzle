(ns fizzle.events.phases
  "Turn structure: phase advancement, untapping, turn transitions.
   Pure functions and re-frame event handlers."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.events :as game-events]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.engine.turn-based :as turn-based]
    [fizzle.events.cleanup :as cleanup]
    [re-frame.core :as rf]))


(def phases
  "MTG turn phases in order. Delegates to engine/rules."
  rules/phases)


(defn next-phase
  "Get the next phase in the turn sequence.
   Returns the same phase if at cleanup (requires explicit start-turn for new turn)."
  [current-phase]
  (let [idx (.indexOf phases current-phase)]
    (if (or (neg? idx) (= idx (dec (count phases))))
      current-phase  ; Stay at cleanup or unknown phase
      (nth phases (inc idx)))))


(defn advance-phase
  "Advance to the next phase, clear mana pool, and dispatch phase-entered event.
   Pure function: (db, player-id) -> db

   Returns db unchanged if the stack is non-empty.
   At cleanup phase, stays at cleanup (user must call start-turn for new turn).
   Dispatches :phase-entered event to fire turn-based actions (draw, etc.).
   Fires delayed-effect grants when entering upkeep."
  [db player-id]
  (if-not (queries/stack-empty? db)
    db
    (let [game-state (queries/get-game-state db)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          current-phase (:game/phase game-state)
          new-phase (next-phase current-phase)
          turn (:game/turn game-state)]
      (-> db
          (mana/empty-pool player-id)
          (d/db-with [[:db/add game-eid :game/phase new-phase]])
          ;; Fire delayed-effect grants when entering upkeep (for both players)
          ;; Delayed triggers fire based on game timing, not turn ownership.
          (cond-> (= new-phase :upkeep)
            (as-> db'
              (let [other-id (queries/get-other-player-id db' player-id)]
                (cond-> (turn-based/fire-delayed-effects db' player-id)
                  other-id (turn-based/fire-delayed-effects other-id)))))
          ;; Dispatch phase-entered event to fire turn-based actions
          (dispatch/dispatch-event (game-events/phase-entered-event new-phase turn player-id))))))


(defn untap-all-permanents
  "Untap all permanents controlled by a player on the battlefield.
   Pure function: (db, player-id) -> db"
  [db player-id]
  (let [player-eid (queries/get-player-eid db player-id)
        ;; Find all tapped permanents controlled by player on battlefield
        tapped-permanents (d/q '[:find ?e
                                 :in $ ?controller
                                 :where [?e :object/controller ?controller]
                                 [?e :object/zone :battlefield]
                                 [?e :object/tapped true]]
                               db player-eid)]
    (if (seq tapped-permanents)
      (d/db-with db (mapv (fn [[eid]] [:db/add eid :object/tapped false])
                          tapped-permanents))
      db)))


(defn start-turn
  "Start a new turn: switch active player, increment turn counter,
   set phase to untap, reset storm count and land plays, clear mana pool.
   Pure function: (db, player-id) -> db

   player-id is the player whose turn just ended (active player before switch).
   The next player is determined by alternating: player-id's opponent becomes
   the new active player.

   Returns db unchanged if the stack is non-empty.
   Untap happens via :untap-step trigger when phase changes to :untap.
   Draw happens via :draw-step trigger when phase changes to :draw."
  [db player-id]
  (if-not (queries/stack-empty? db)
    db
    (let [game-state (queries/get-game-state db)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          ;; Switch active player to the other player
          next-player-id (or (queries/get-other-player-id db player-id) player-id)
          next-player-eid (queries/get-player-eid db next-player-id)
          current-turn (or (:game/turn game-state) 0)
          new-turn (inc current-turn)]
      (-> db
          (mana/empty-pool next-player-id)
          (d/db-with [[:db/add game-eid :game/turn new-turn]
                      [:db/add game-eid :game/phase :untap]
                      [:db/add game-eid :game/active-player next-player-eid]
                      [:db/add game-eid :game/priority next-player-eid]
                      [:db/add next-player-eid :player/storm-count 0]
                      [:db/add next-player-eid :player/land-plays-left 1]])
          ;; Dispatch untap phase event to fire turn-based actions
          (dispatch/dispatch-event (game-events/phase-entered-event :untap new-turn next-player-id))))))


(rf/reg-event-db
  :fizzle.events.game/advance-phase
  (fn [db _]
    (let [game-db (:game/db db)]
      ;; Block advance if pending selection exists (e.g., cleanup discard in progress)
      (if (:game/pending-selection db)
        db
        (let [active-player-id (queries/get-active-player-id game-db)
              game-state (queries/get-game-state game-db)
              current-phase (:game/phase game-state)
              new-phase (next-phase current-phase)]
          (if (= new-phase :cleanup)
            ;; Special cleanup handling: advance phase first, then begin cleanup
            (let [advanced-db (advance-phase game-db active-player-id)
                  result (cleanup/begin-cleanup advanced-db active-player-id)]
              (if (:pending-selection result)
                (-> db
                    (assoc :game/db (:db result))
                    (assoc :game/pending-selection (:pending-selection result)))
                (assoc db :game/db (:db result))))
            ;; Normal phase advancement
            (assoc db :game/db (advance-phase game-db active-player-id))))))))


(rf/reg-event-db
  :fizzle.events.game/start-turn
  (fn [db _]
    (let [game-db (:game/db db)]
      ;; Block start-turn if pending selection exists (e.g., cleanup discard)
      (if (:game/pending-selection db)
        db
        (let [active-player-id (queries/get-active-player-id game-db)]
          (assoc db :game/db (start-turn game-db active-player-id)))))))
