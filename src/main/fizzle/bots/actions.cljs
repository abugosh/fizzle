(ns fizzle.bots.actions
  "Bot action execution.

   Pure functions: takes game-db, returns game-db.

   auto-tap-for-cost: taps untapped lands to pay a mana cost"
  (:require
    [fizzle.db.queries :as queries]
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
