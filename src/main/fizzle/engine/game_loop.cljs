(ns fizzle.engine.game-loop
  "Priority negotiation for the game loop.

   negotiate-priority: handle priority passing between players.

   All functions are pure — no re-frame dispatch, no side effects."
  (:require
    [fizzle.bots.protocol :as bot]
    [fizzle.db.queries :as queries]
    [fizzle.engine.priority :as priority]))


(def priority-phases
  "Phases where players receive priority per MTG rules.
   Untap and cleanup do not grant priority."
  #{:upkeep :draw :main1 :combat :main2 :end})


(defn negotiate-priority
  "Handle priority passing between players.
   Returns {:app-db updated-app-db, :all-passed? bool}.

   When all-passed? is true, passes are reset in the returned app-db.
   When all-passed? is false, priority has been transferred to the opponent.

   Pure function: (app-db) -> {:app-db app-db', :all-passed? bool}"
  [app-db]
  (let [game-db (:game/db app-db)
        auto-mode (priority/get-auto-mode game-db)
        is-bot-turn (boolean (bot/get-bot-archetype game-db (queries/get-active-player-id game-db)))
        holder-eid (priority/get-priority-holder-eid game-db)
        ;; Step 1: current player passes
        gdb (priority/yield-priority game-db holder-eid)
        ;; Step 2: auto-pass non-active player
        active-player-id (queries/get-active-player-id gdb)
        active-eid (queries/get-player-eid gdb active-player-id)
        opponent-player-id (queries/get-other-player-id gdb active-player-id)
        gdb (if opponent-player-id
              (let [opp-eid (queries/get-player-eid gdb opponent-player-id)]
                (if (or auto-mode (and is-bot-turn (queries/stack-empty? gdb)))
                  ;; Auto-pass both players (bot turn with empty stack, or auto-mode)
                  (-> gdb
                      (priority/yield-priority active-eid)
                      (priority/yield-priority opp-eid))
                  (let [archetype (bot/get-bot-archetype gdb opponent-player-id)]
                    (if (and archetype
                             (= :pass (bot/bot-priority-decision
                                        archetype {:db gdb :player-id opponent-player-id})))
                      (priority/yield-priority gdb opp-eid)
                      gdb))))
              gdb)
        all-passed (or (not opponent-player-id)
                       (priority/both-passed? gdb))]
    (if all-passed
      {:app-db (assoc app-db :game/db (priority/reset-passes gdb))
       :all-passed? true}
      {:app-db (assoc app-db :game/db (priority/transfer-priority gdb holder-eid))
       :all-passed? false})))
