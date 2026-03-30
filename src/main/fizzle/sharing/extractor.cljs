(ns fizzle.sharing.extractor
  "Converts a live Datascript DB into a portable plain-map snapshot.

   All Datascript entity IDs and refs are resolved to stable keywords:
   - Player refs  → :player/id keyword  (e.g. :player-1)
   - Card refs    → :card/id keyword    (e.g. :dark-ritual)
   - :db/id keys are stripped

   Zone contents are plain vectors of object maps.
   Library is sorted ascending by :object/position (index 0 = top)."
  (:require
    [datascript.core :as d]))


;; ---------------------------------------------------------------------------
;; Helpers

(defn- resolve-player-ref
  "Given a db and a raw player entity ID (integer), return the :player/id keyword."
  [db eid]
  (d/q '[:find ?pid .
         :in $ ?e
         :where [?e :player/id ?pid]]
       db eid))


(defn- all-player-ids
  "Return all :player/id keywords in the db."
  [db]
  (d/q '[:find [?pid ...]
         :where [_ :player/id ?pid]]
       db))


(defn- player-eid
  [db player-id]
  (d/q '[:find ?e .
         :in $ ?pid
         :where [?e :player/id ?pid]]
       db player-id))


(defn- pull-card-id
  "Given a card entity (pulled map or eid), return its :card/id keyword."
  [card]
  (if (map? card)
    (:card/id card)
    nil))


(defn- extract-object
  "Convert a pulled object entity to a portable map.
   Strips :db/id; resolves :object/card → :card/id,
   :object/owner and :object/controller → :player/id keyword."
  [db obj]
  (let [card-id (or (pull-card-id (:object/card obj))
                    ;; card may be stored as eid integer
                    (when (integer? (:object/card obj))
                      (d/q '[:find ?cid .
                             :in $ ?e
                             :where [?e :card/id ?cid]]
                           db (:object/card obj))))
        owner-id (cond
                   (map? (:object/owner obj))      (:player/id (:object/owner obj))
                   (integer? (:object/owner obj))  (resolve-player-ref db (:object/owner obj))
                   :else                           (:object/owner obj))
        ctrl-id  (cond
                   (map? (:object/controller obj))     (:player/id (:object/controller obj))
                   (integer? (:object/controller obj)) (resolve-player-ref db (:object/controller obj))
                   :else                              (:object/controller obj))]
    (-> obj
        (dissoc :db/id :object/card :object/owner :object/controller)
        (dissoc :object/triggers)        ; trigger refs not portable
        (assoc :card/id card-id
               :object/owner owner-id
               :object/controller ctrl-id)
        ;; Ensure optional fields that may be absent default to nil so downstream
        ;; code can rely on their presence.
        (update :object/counters #(or % {}))
        (update :object/grants   #(or % [])))))


(defn- objects-in-zone
  "Return pulled objects for player-id in the given zone."
  [db pid zone]
  (let [peid (player-eid db pid)]
    (->> (d/q '[:find [(pull ?obj [* {:object/card [:card/id]}
                                   {:object/owner [:player/id]}
                                   {:object/controller [:player/id]}]) ...]
                :in $ ?owner ?zone
                :where [?obj :object/owner ?owner]
                [?obj :object/zone ?zone]]
              db peid zone)
         (mapv #(extract-object db %)))))


(defn- extract-library
  "Library objects sorted ascending by :object/position (0 = top)."
  [db pid]
  (->> (objects-in-zone db pid :library)
       (sort-by :object/position)
       (vec)))


(defn- extract-player
  "Extract full state for one player."
  [db pid]
  (let [peid (player-eid db pid)
        p    (d/pull db [:player/life
                         :player/mana-pool
                         :player/storm-count
                         :player/land-plays-left
                         :player/max-hand-size
                         :player/grants
                         :player/is-opponent
                         :player/bot-archetype
                         :player/stops
                         :player/drew-from-empty]
                     peid)]
    (-> p
        (dissoc :db/id)
        (update :player/grants         #(or % []))
        (update :player/max-hand-size  #(or % 7))
        (assoc :hand        (objects-in-zone db pid :hand)
               :library     (extract-library db pid)
               :graveyard   (objects-in-zone db pid :graveyard)
               :battlefield (objects-in-zone db pid :battlefield)
               :exile       (objects-in-zone db pid :exile)))))


;; ---------------------------------------------------------------------------
;; Public API

(defn extract
  "Extract the portable game state from a Datascript DB value.

   Returns:
   {:game/turn          integer
    :game/phase         keyword
    :game/step          keyword (may be nil)
    :game/active-player :player/id keyword
    :game/priority      :player/id keyword
    :game/winner        :player/id keyword or nil
    :game/loss-condition keyword or nil
    :game/auto-mode     keyword or nil
    :game/human-player-id keyword
    :players            {:player-id {player-state + zones}}}"
  [db]
  (let [game   (d/pull db [:game/turn
                           :game/phase
                           :game/step
                           :game/auto-mode
                           :game/loss-condition
                           {:game/active-player [:player/id]}
                           {:game/priority      [:player/id]}
                           {:game/winner        [:player/id]}
                           :game/human-player-id]
                       [:game/id :game-1])
        pids   (all-player-ids db)]
    {:game/turn           (:game/turn game)
     :game/phase          (:game/phase game)
     :game/step           (:game/step game)
     :game/auto-mode      (:game/auto-mode game)
     :game/loss-condition (:game/loss-condition game)
     :game/active-player  (get-in game [:game/active-player :player/id])
     :game/priority       (get-in game [:game/priority :player/id])
     :game/winner         (get-in game [:game/winner :player/id])
     :game/human-player-id (:game/human-player-id game)
     :players             (into {} (map (fn [pid] [pid (extract-player db pid)]) pids))}))
