(ns fizzle.bots.actions
  "Bot action execution.

   Handles executing bot priority actions (e.g., casting spells).
   Pure functions: takes game-db, returns game-db.

   auto-tap-for-cost: taps untapped lands to pay a mana cost
   execute-bot-priority-action: dispatches on action type"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.events.abilities :as abilities]))


(defn auto-tap-for-cost
  "Tap untapped lands to pay a mana cost.
   Taps one land per colored mana needed.
   Returns updated db with mana added to pool.
   Pure function: (db, player-id, mana-cost) -> db

   For now, only handles single-color costs (e.g., {:red 1}).
   Taps Mountains for red, etc."
  [db player-id mana-cost]
  (let [battlefield (queries/get-objects-in-zone db player-id :battlefield)]
    (reduce
      (fn [db' [color amount]]
        (let [produces-key (case color
                             :red :red
                             :black :black
                             :blue :blue
                             :white :white
                             :green :green
                             nil)
              untapped-lands (->> battlefield
                                  (filter (fn [obj]
                                            (and (not (:object/tapped obj))
                                                 (some (fn [ability]
                                                         (and (= :mana (:ability/type ability))
                                                              (get (:ability/produces ability) produces-key)))
                                                       (get-in obj [:object/card :card/abilities])))))
                                  ;; Re-query to get current tapped state
                                  (map :object/id)
                                  (filter (fn [oid]
                                            (let [obj (queries/get-object db' oid)]
                                              (not (:object/tapped obj))))))]
          (loop [remaining amount
                 current-db db'
                 lands untapped-lands]
            (if (or (<= remaining 0) (empty? lands))
              current-db
              (recur (dec remaining)
                     (abilities/activate-mana-ability current-db player-id (first lands) produces-key)
                     (rest lands))))))
      db
      mana-cost)))


(defn execute-bot-priority-action
  "Execute a bot's priority action (e.g., cast a spell).
   Returns updated game-db.
   Pure function: (db, action) -> db

   Supported actions:
     {:action :cast-spell :object-id oid :target player-id}
       - Auto-taps lands to pay mana cost
       - Casts spell via rules/cast-spell-mode
       - Stores target on stack-item"
  [db action]
  (case (:action action)
    :cast-spell
    (let [object-id (:object-id action)
          target (:target action)
          obj (queries/get-object db object-id)
          card (:object/card obj)
          controller-eid (:db/id (:object/controller obj))
          player-id (:player/id (d/pull db [:player/id] controller-eid))
          mana-cost (:card/mana-cost card)
          ;; Auto-tap lands to pay cost
          db-tapped (auto-tap-for-cost db player-id mana-cost)
          ;; Get casting mode
          modes (rules/get-casting-modes db-tapped player-id object-id)
          mode (or (first (filter #(= :primary (:mode/id %)) modes))
                   (first modes))]
      (if (and mode (rules/can-cast-mode? db-tapped player-id object-id mode))
        (let [;; Cast spell (pays mana, moves to stack, increments storm)
              db-cast (rules/cast-spell-mode db-tapped player-id object-id mode)
              ;; Store target on stack-item
              obj-eid (d/q '[:find ?e .
                             :in $ ?oid
                             :where [?e :object/id ?oid]]
                           db-cast object-id)
              stack-item (stack/get-stack-item-by-object-ref db-cast obj-eid)
              target-id (-> card :card/targeting first :target/id)]
          (if (:db/id stack-item)
            (d/db-with db-cast
                       [[:db/add (:db/id stack-item) :stack-item/targets {target-id target}]])
            db-cast))
        ;; Can't cast — return unchanged
        db))
    ;; Unknown action — return unchanged
    db))
