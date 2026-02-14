(ns fizzle.test-helpers
  "Shared test helpers for creating game state and managing zones.

   All helpers return immutable db values (not conn).
   Return convention: [db result] tuples (db first)."
  (:require
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]))


(def ^:private empty-mana-pool
  {:white 0 :blue 0 :black 0
   :red 0 :green 0 :colorless 0})


(defn create-test-db
  "Create a game state with all card definitions loaded.
   No-arg version: all-zero mana pool, standard player.
   Opts map supports: {:mana {:blue 1 :black 3} :life 20 :storm-count 0 :land-plays 1}"
  ([]
   (create-test-db {}))
  ([opts]
   (let [conn (d/create-conn schema)
         mana-pool (merge empty-mana-pool (:mana opts))
         life (or (:life opts) 20)
         storm-count (or (:storm-count opts) 0)
         land-plays (or (:land-plays opts) 1)]
     (d/transact! conn cards/all-cards)
     (d/transact! conn [{:player/id :player-1
                         :player/name "Player"
                         :player/life life
                         :player/mana-pool mana-pool
                         :player/storm-count storm-count
                         :player/land-plays-left land-plays}])
     (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
       (d/transact! conn [{:game/id :game-1
                           :game/turn 1
                           :game/phase :main1
                           :game/active-player player-eid
                           :game/priority player-eid}]))
     @conn)))


(defn add-card-to-zone
  "Add a card object to a zone for a player.
   card-id: keyword like :dark-ritual
   zone: keyword like :hand, :battlefield, :graveyard, :library, :exile
   owner: keyword like :player-1
   When zone is :library, adds :object/position 0 (top of library).
   Returns [db obj-id] tuple."
  [db card-id zone owner]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db owner)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db card-id)
        _ (when (nil? card-eid)
            (throw (ex-info (str "Unknown card-id: " card-id
                                 ". Card not found in database.")
                            {:card-id card-id})))
        obj-id (random-uuid)
        base-obj {:object/id obj-id
                  :object/card card-eid
                  :object/zone zone
                  :object/owner player-eid
                  :object/controller player-eid
                  :object/tapped false}
        obj (if (= zone :library)
              (assoc base-obj :object/position 0)
              base-obj)]
    (d/transact! conn [obj])
    [@conn obj-id]))


(defn add-cards-to-library
  "Add multiple cards to the library with sequential positions.
   card-ids: vector of card-id keywords.
   Position 0 = top of library.
   Returns [db object-ids] tuple with object-ids in order."
  [db card-ids owner]
  (if (empty? card-ids)
    [db []]
    (let [conn (d/conn-from-db db)
          player-eid (q/get-player-eid db owner)
          get-card-eid (fn [card-id]
                         (let [eid (d/q '[:find ?e .
                                          :in $ ?cid
                                          :where [?e :card/id ?cid]]
                                        @conn card-id)]
                           (when (nil? eid)
                             (throw (ex-info (str "Unknown card-id: " card-id)
                                             {:card-id card-id})))
                           eid))]
      (loop [remaining card-ids
             position 0
             object-ids []]
        (if (empty? remaining)
          [@conn object-ids]
          (let [card-id (first remaining)
                obj-id (random-uuid)
                card-eid (get-card-eid card-id)]
            (d/transact! conn [{:object/id obj-id
                                :object/card card-eid
                                :object/zone :library
                                :object/owner player-eid
                                :object/controller player-eid
                                :object/tapped false
                                :object/position position}])
            (recur (rest remaining)
                   (inc position)
                   (conj object-ids obj-id))))))))


(defn add-cards-to-graveyard
  "Add multiple cards to the graveyard.
   card-ids: vector of card-id keywords.
   Returns [db object-ids] tuple."
  [db card-ids owner]
  (if (empty? card-ids)
    [db []]
    (let [conn (d/conn-from-db db)
          player-eid (q/get-player-eid db owner)]
      (loop [remaining card-ids
             object-ids []]
        (if (empty? remaining)
          [@conn object-ids]
          (let [card-id (first remaining)
                obj-id (random-uuid)
                card-eid (d/q '[:find ?e .
                                :in $ ?cid
                                :where [?e :card/id ?cid]]
                              @conn card-id)]
            (when (nil? card-eid)
              (throw (ex-info (str "Unknown card-id: " card-id)
                              {:card-id card-id})))
            (d/transact! conn [{:object/id obj-id
                                :object/card card-eid
                                :object/zone :graveyard
                                :object/owner player-eid
                                :object/controller player-eid
                                :object/tapped false}])
            (recur (rest remaining)
                   (conj object-ids obj-id))))))))


(defn get-zone-count
  "Count objects in a zone for a player."
  [db zone owner]
  (count (or (q/get-objects-in-zone db owner zone) [])))


(defn get-object-zone
  "Get the zone keyword for a given object."
  [db obj-id]
  (:object/zone (q/get-object db obj-id)))


(defn get-hand-count
  "Get the number of cards in a player's hand."
  [db owner]
  (get-zone-count db :hand owner))


(defn add-opponent
  "Add :player-2 with standard opponent settings.
   Returns updated db."
  [db]
  (let [conn (d/conn-from-db db)]
    (d/transact! conn [{:player/id :player-2
                        :player/name "Opponent"
                        :player/life 20
                        :player/mana-pool empty-mana-pool
                        :player/storm-count 0
                        :player/land-plays-left 1
                        :player/is-opponent true}])
    @conn))
