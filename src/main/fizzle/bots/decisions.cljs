(ns fizzle.bots.decisions
  "Bot decision computation: pure functions called by the director.

   Exported functions:
   - bot-should-act?   — true if current priority holder is a bot
   - bot-decide-action — asks bot protocol for an action plan
   - find-tap-sequence — allocates mana-producing lands to pay a cost

   Architecture: all functions are pure (no side effects, no re-frame).
   The director (events/director.cljs) calls these inline during its loop
   and applies the resulting actions through the standard event pipeline."
  (:require
    [fizzle.bots.protocol :as bot]
    [fizzle.db.game-state :as game-state]
    [fizzle.db.queries :as queries]
    [fizzle.engine.priority :as priority]))


(defn bot-should-act?
  "Check if the current priority holder is a bot.
   Returns true if the player entity holding priority has a bot archetype.
   Pure function: (game-db) -> boolean"
  [game-db]
  (let [holder-eid (priority/get-priority-holder-eid game-db)]
    (if holder-eid
      (let [player-id (some (fn [pid]
                              (when (= holder-eid (queries/get-player-eid game-db pid))
                                pid))
                            [game-state/human-player-id game-state/opponent-player-id])]
        (boolean (and player-id (bot/get-bot-archetype game-db player-id))))
      false)))


(def ^:private color-keys
  "The five Magic colors recognized for mana production."
  #{:red :black :blue :white :green})


(defn find-tap-sequence
  "Find the lands that need to be tapped to pay a mana cost.
   Allocates color-specific mana first, then generic mana from any remaining
   untapped mana-producing land. Tracks already-allocated lands across colors
   to prevent double-allocation of dual lands.
   Returns a vector of {:object-id oid :mana-color color} maps.
   Pure function: (game-db, player-id, mana-cost) -> [{:object-id :mana-color}]"
  [game-db player-id mana-cost]
  (let [battlefield (queries/get-objects-in-zone game-db player-id :battlefield)
        allocated-ids (volatile! #{})
        find-lands (fn [pred amount]
                     (->> battlefield
                          (filter (fn [obj]
                                    (and (not (:object/tapped obj))
                                         (not (@allocated-ids (:object/id obj)))
                                         (pred obj))))
                          (take amount)
                          vec))
        ;; Process colored mana first, then generic
        colored-entries (filter (fn [[color _]] (color-keys color)) mana-cost)
        generic-amount (get mana-cost :generic 0)
        ;; Allocate color-specific lands
        colored-taps (reduce
                       (fn [taps [color amount]]
                         (let [lands (find-lands
                                       (fn [obj]
                                         (some (fn [ability]
                                                 (and (= :mana (:ability/type ability))
                                                      (get (:ability/produces ability) color)))
                                               (get-in obj [:object/card :card/abilities])))
                                       amount)]
                           (doseq [obj lands]
                             (vswap! allocated-ids conj (:object/id obj)))
                           (into taps (map (fn [obj]
                                             {:object-id (:object/id obj)
                                              :mana-color color})
                                           lands))))
                       []
                       colored-entries)]
    ;; Allocate generic mana from any remaining mana-producing land
    (if (pos? generic-amount)
      (let [generic-lands (find-lands
                            (fn [obj]
                              (some (fn [ability]
                                      (= :mana (:ability/type ability)))
                                    (get-in obj [:object/card :card/abilities])))
                            generic-amount)]
        (into colored-taps (map (fn [obj]
                                  (let [first-color (some (fn [ability]
                                                            (when (= :mana (:ability/type ability))
                                                              (first (keys (:ability/produces ability)))))
                                                          (get-in obj [:object/card :card/abilities]))]
                                    {:object-id (:object/id obj)
                                     :mana-color first-color})) generic-lands)))
      colored-taps)))


(defn bot-decide-action
  "Ask the bot protocol for a decision and build an action plan.
   Returns an action map:
     {:action :cast-spell :object-id oid :target tid :player-id pid :tap-sequence [...]}
     {:action :pass}
   Pure function: (game-db) -> action-map"
  [game-db]
  (let [holder-eid (priority/get-priority-holder-eid game-db)
        player-id (some (fn [pid]
                          (when (= holder-eid (queries/get-player-eid game-db pid))
                            pid))
                        [game-state/human-player-id game-state/opponent-player-id])
        archetype (when player-id (bot/get-bot-archetype game-db player-id))]
    (if-not archetype
      {:action :pass}
      (let [decision (bot/bot-priority-decision archetype {:db game-db :player-id player-id})]
        (if (= :pass decision)
          {:action :pass}
          (let [object-id (:object-id decision)
                target (:target decision)
                card (queries/get-card game-db object-id)
                mana-cost (or (:card/mana-cost card) {})
                tap-seq (find-tap-sequence game-db player-id mana-cost)
                can-pay? (every?
                           (fn [[color amount]]
                             (if (= :generic color)
                               (let [colored-need (reduce + 0 (vals (dissoc mana-cost :generic)))]
                                 (>= (- (count tap-seq) colored-need) amount))
                               (<= amount (count (filter #(= color (:mana-color %)) tap-seq)))))
                           mana-cost)]
            (if-not can-pay?
              {:action :pass}
              {:action :cast-spell
               :object-id object-id
               :target target
               :player-id player-id
               :tap-sequence tap-seq})))))))
