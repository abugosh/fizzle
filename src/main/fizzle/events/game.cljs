(ns fizzle.events.game
  (:require
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as queries]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.triggers :as triggers]
    [re-frame.core :as rf]))


(rf/reg-event-db
  ::init-game
  (fn [_ _]
    (let [conn (d/create-conn schema)]
      ;; Transact card definitions
      (d/transact! conn cards/all-cards)
      ;; Transact player with starting mana (2 black + 2 blue for storm combo)
      (d/transact! conn [{:player/id :player-1
                          :player/name "Player"
                          :player/life 20
                          :player/mana-pool {:white 0 :blue 2 :black 2
                                             :red 0 :green 0 :colorless 0}
                          :player/storm-count 0
                          :player/land-plays-left 1}])
      ;; Transact opponent (needed for Brain Freeze mill effect)
      (d/transact! conn [{:player/id :opponent
                          :player/name "Opponent"
                          :player/life 20
                          :player/mana-pool {:white 0 :blue 0 :black 0
                                             :red 0 :green 0 :colorless 0}
                          :player/storm-count 0
                          :player/land-plays-left 0
                          :player/is-opponent true}])
      ;; Get entity IDs for references
      (let [db @conn
            player-eid (d/q '[:find ?e .
                              :in $ ?pid
                              :where [?e :player/id ?pid]]
                            db :player-1)
            dr-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        db :dark-ritual)
            cr-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        db :cabal-ritual)
            bf-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        db :brain-freeze)
            opp-eid (d/q '[:find ?e .
                           :in $ ?pid
                           :where [?e :player/id ?pid]]
                         db :opponent)]
        ;; Create game objects (3 cards in hand)
        (d/transact! conn [{:object/id (random-uuid) :object/card dr-eid :object/zone :hand
                            :object/owner player-eid :object/controller player-eid :object/tapped false}
                           {:object/id (random-uuid) :object/card cr-eid :object/zone :hand
                            :object/owner player-eid :object/controller player-eid :object/tapped false}
                           {:object/id (random-uuid) :object/card bf-eid :object/zone :hand
                            :object/owner player-eid :object/controller player-eid :object/tapped false}])
        ;; Create opponent library (40 cards so mill has targets)
        (d/transact! conn (vec (for [i (range 40)]
                                 {:object/id (random-uuid) :object/card dr-eid :object/zone :library
                                  :object/owner opp-eid :object/controller opp-eid
                                  :object/tapped false :object/position i})))
        ;; Game state
        (d/transact! conn [{:game/id :game-1
                            :game/turn 1
                            :game/phase :main1
                            :game/active-player player-eid
                            :game/priority player-eid}]))
      {:game/db @conn})))


(rf/reg-event-db
  ::select-card
  (fn [db [_ object-id]]
    (let [currently-selected (:game/selected-card db)]
      (assoc db :game/selected-card
             (when (not= currently-selected object-id) object-id)))))


(rf/reg-event-db
  ::cast-spell
  (fn [db _]
    (let [game-db (:game/db db)
          selected (:game/selected-card db)]
      (if (and selected (rules/can-cast? game-db :player-1 selected))
        (-> db
            (assoc :game/db (rules/cast-spell game-db :player-1 selected))
            (dissoc :game/selected-card))
        db))))


(rf/reg-event-db
  ::resolve-top
  (fn [db _]
    (let [game-db (:game/db db)
          stack-triggers (queries/get-stack-items game-db)]
      (if (seq stack-triggers)
        ;; Resolve top trigger (first = highest stack-order = most recent)
        (let [top-trigger (first stack-triggers)
              game-db' (-> game-db
                           (triggers/resolve-trigger top-trigger)
                           (triggers/remove-trigger (:trigger/id top-trigger)))]
          (assoc db :game/db game-db'))
        ;; No triggers - resolve top spell object on stack (LIFO by position)
        (let [stack-objects (->> (queries/get-objects-in-zone game-db :player-1 :stack)
                                 (sort-by :object/position >))]
          (if (seq stack-objects)
            (let [top-spell (first stack-objects)
                  game-db' (rules/resolve-spell game-db :player-1 (:object/id top-spell))]
              (assoc db :game/db game-db'))
            db))))))
