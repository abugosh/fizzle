(ns fizzle.events.init
  "Game initialization: create fresh game state from deck config.
   Pure functions for building Datascript transaction data."
  (:require
    [datascript.core :as d]
    [fizzle.db.schema :refer [schema]]
    [fizzle.db.storage :as storage]
    [fizzle.engine.card-spec :as card-spec]
    [fizzle.engine.cards :as cards]
    [fizzle.engine.turn-based :as turn-based]
    [re-frame.core :as rf]))


(defn- deck-to-card-ids
  "Expand a deck main list [{:card/id :count}] into a flat shuffled vector of card-ids."
  [main-deck]
  (shuffle
    (into []
          (mapcat (fn [{:keys [card/id count]}]
                    (repeat count id))
                  main-deck))))


(defn- extract-sculpted-card-ids
  "Extract sculpted card-ids from a shuffled deck.
   Returns [sculpted-card-ids remaining-card-ids].
   For each {card-id count} in must-contain, removes count occurrences from deck.
   Takes min(requested, available) to handle edge cases defensively."
  [shuffled-deck must-contain]
  (if (empty? must-contain)
    [[] shuffled-deck]
    (reduce
      (fn [[sculpted remaining] [card-id cnt]]
        (loop [r remaining
               s sculpted
               n cnt]
          (if (or (zero? n) (empty? r))
            [s r]
            (let [idx (.indexOf r card-id)]
              (if (neg? idx)
                [s r]
                (recur (into (subvec r 0 idx) (subvec r (inc idx)))
                       (conj s card-id)
                       (dec n)))))))
      [[] (vec shuffled-deck)]
      must-contain)))


(defn- get-card-eid
  "Look up a card's entity ID by its :card/id keyword."
  [db card-id]
  (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id))


(defn- get-player-eid
  "Look up a player's entity ID by :player/id."
  [db player-id]
  (d/q '[:find ?e . :in $ ?pid :where [?e :player/id ?pid]] db player-id))


(def ^:private empty-mana-pool
  {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0})


(defn- player-tx
  "Return transaction data for a player entity."
  [player-id name life land-plays opts]
  [(merge {:player/id player-id
           :player/name name
           :player/life life
           :player/mana-pool empty-mana-pool
           :player/storm-count 0
           :player/land-plays-left land-plays}
          opts)])


(defn- objects-tx
  "Return transaction data for game objects in a zone.
   For :hand, uses provided UUIDs. For :library, generates UUIDs and sets position."
  [db card-ids zone owner-eid uuids]
  (vec (map-indexed
         (fn [i [uuid card-id]]
           {:object/id uuid
            :object/card (get-card-eid db card-id)
            :object/zone zone
            :object/owner owner-eid
            :object/controller owner-eid
            :object/tapped false
            :object/position (if (= zone :library) i 0)})
         (map vector uuids card-ids))))


(defn- opponent-deck-tx
  "Return transaction data for opponent's library and opening hand from a deck list.
   Shuffles the deck, draws 7 for hand, rest goes in library.
   deck-list: vector of {:card/id :count} maps (from bot-deck multimethod)."
  [db opp-eid deck-list]
  (let [card-ids (shuffle
                   (into []
                         (mapcat (fn [{:keys [card/id count]}]
                                   (repeat count id)))
                         deck-list))
        hand-ids (take 7 card-ids)
        library-ids (drop 7 card-ids)
        make-obj (fn [card-id zone position]
                   {:object/id (random-uuid)
                    :object/card (get-card-eid db card-id)
                    :object/zone zone
                    :object/owner opp-eid
                    :object/controller opp-eid
                    :object/tapped false
                    :object/position position})]
    (into (vec (map #(make-obj % :hand 0) hand-ids))
          (map-indexed (fn [i card-id] (make-obj card-id :library i)) library-ids))))


(defn init-game-state
  "Initialize a fresh game state from config.
   Config keys:
     :main-deck     - vec of {:card/id :count} maps (required)
     :must-contain  - map of {card-id count} for sculpted opening hand (default {})
     :sideboard     - vec of {:card/id :count} maps for sideboard zone (default [])

   Returns app-db map with :game/db, :active-screen :opening-hand,
   and opening-hand state keys."
  [{:keys [main-deck bot-archetype bot-deck must-contain sideboard]
    :or {bot-archetype :goldfish must-contain {} sideboard []}}]
  (card-spec/validate-cards! cards/all-cards)
  (let [conn (d/create-conn schema)
        _ (d/transact! conn cards/all-cards)
        _ (d/transact! conn (player-tx :player-1 "Player" 20 1 {:player/max-hand-size 7}))
        _ (d/transact! conn (player-tx :opponent "Opponent" 20 0
                                       {:player/is-opponent true
                                        :player/bot-archetype bot-archetype}))
        db @conn
        player-eid (get-player-eid db :player-1)
        opp-eid (get-player-eid db :opponent)
        stops (storage/load-stops)
        shuffled-deck (deck-to-card-ids main-deck)
        [sculpted-ids remaining] (extract-sculpted-card-ids shuffled-deck must-contain)
        draw-count (- 7 (count sculpted-ids))
        hand-ids (concat sculpted-ids (take draw-count remaining))
        library-ids (drop draw-count remaining)
        hand-uuids (repeatedly (count hand-ids) random-uuid)
        sculpted-id-set (set (take (count sculpted-ids) hand-uuids))]
    (d/transact! conn (objects-tx @conn hand-ids :hand player-eid hand-uuids))
    (d/transact! conn (objects-tx @conn library-ids :library player-eid
                                  (repeatedly (count library-ids) random-uuid)))
    (d/transact! conn (opponent-deck-tx @conn opp-eid (or bot-deck [])))
    (let [sb-card-ids (mapcat (fn [{:keys [card/id count]}] (repeat count id)) sideboard)]
      (when (seq sb-card-ids)
        (d/transact! conn (objects-tx @conn sb-card-ids :sideboard player-eid
                                      (repeatedly (count sb-card-ids) random-uuid)))))
    (d/transact! conn [{:game/id :game-1 :game/turn 1 :game/phase :main1
                        :game/active-player player-eid :game/priority player-eid
                        :game/human-player-id :player-1}])
    (d/transact! conn (turn-based/create-turn-based-triggers-tx player-eid :player-1))
    (d/transact! conn (turn-based/create-turn-based-triggers-tx opp-eid :opponent))
    (d/transact! conn [[:db/add player-eid :player/stops (:player stops)]
                       [:db/add opp-eid :player/stops (:opponent stops)]])
    (merge {:game/db @conn :active-screen :opening-hand
            :opening-hand/mulligan-count 0 :opening-hand/sculpted-ids sculpted-id-set
            :opening-hand/must-contain (or must-contain {})
            :opening-hand/phase :viewing :game/game-over-dismissed false
            :ui/stack-collapsed false
            :ui/gy-collapsed false
            :ui/history-collapsed false}
           {:history/main [] :history/forks {}
            :history/current-branch nil :history/position -1})))


(rf/reg-event-db
  ::init-game
  (fn [_ [_ config]]
    (init-game-state config)))
