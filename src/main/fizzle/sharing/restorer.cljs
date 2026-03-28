(ns fizzle.sharing.restorer
  "Snapshot restorer: portable game-state map → Datascript DB.

   Exact inverse of fizzle.sharing.extractor. Reconstructs a fully playable
   Datascript DB from the decoded portable map produced by
   fizzle.sharing.decoder/decode-snapshot.

   Objects receive fresh UUIDs — original UUIDs are not preserved across sessions.
   Card references are resolved via card-id → entity lookup (same as init.cljs).
   Triggers are recreated from card definitions for battlefield permanents.
   Turn-based triggers are recreated for both players."
  (:require
    [datascript.core :as d]
    [fizzle.bots.protocol :as bot-protocol]
    [fizzle.db.game-state :as game-state]
    [fizzle.db.schema :refer [schema]]
    [fizzle.db.storage :as storage]
    [fizzle.engine.cards :as cards]
    [fizzle.engine.trigger-db :as trigger-db]
    [fizzle.engine.turn-based :as turn-based]))


;; ---------------------------------------------------------------------------
;; DB helpers

(defn- get-card-eid
  "Look up a card's entity ID by its :card/id keyword."
  [db card-id]
  (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] db card-id))


(defn- get-player-eid
  "Look up a player's entity ID by :player/id."
  [db player-id]
  (d/q '[:find ?e . :in $ ?pid :where [?e :player/id ?pid]] db player-id))


;; ---------------------------------------------------------------------------
;; Player transactions

(defn- player-tx
  "Build transaction data for restoring one player from a portable player map."
  [player-id player-map is-opponent bot-archetype]
  (let [overrides (cond-> {:player/name            (if is-opponent "Opponent" "Player")
                           :player/life            (or (:player/life player-map) 20)
                           :player/mana-pool       (or (:player/mana-pool player-map)
                                                       game-state/empty-mana-pool)
                           :player/storm-count     (or (:player/storm-count player-map) 0)
                           :player/land-plays-left (or (:player/land-plays-left player-map) 1)
                           :player/max-hand-size   (or (:player/max-hand-size player-map) 7)
                           :player/grants          (or (:player/grants player-map) [])}
                    is-opponent   (assoc :player/is-opponent true)
                    bot-archetype (assoc :player/bot-archetype bot-archetype))]
    (game-state/create-player-tx player-id overrides)))


;; ---------------------------------------------------------------------------
;; Object transactions per zone

(defn- card-types-set
  "Return the set of card types for a card entity (pulled as {:db/id eid})."
  [db card-eid]
  (let [types (d/q '[:find [?t ...] :in $ ?e :where [?e :card/types ?t]] db card-eid)]
    (set types)))


(defn- creature?
  "True if the card types include :creature."
  [card-types]
  (contains? card-types :creature))


(defn- card-base-stat
  "Return a card stat field (e.g. :card/power) from the card entity, or nil."
  [db card-eid field]
  (d/q '[:find ?v . :in $ ?e ?f :where [?e ?f ?v]] db card-eid field))


(defn- object-tx-for-zone
  "Build one object transaction map for restoring an object in a given zone.

   For library objects, position is the index in the ordered array.
   For battlefield creatures, restores power/toughness from card definition
   and sets summoning-sick false (snapshot is mid-game, not just-entered).
   Tapped, counters, and grants come from the portable object map."
  [db obj-map zone owner-eid position]
  (let [card-id  (:card/id obj-map)
        card-eid (get-card-eid db card-id)
        base     {:object/id         (random-uuid)
                  :object/card       card-eid
                  :object/zone       zone
                  :object/owner      owner-eid
                  :object/controller owner-eid
                  :object/tapped     (boolean (:object/tapped obj-map))
                  :object/position   (if (= zone :library) position 0)}
        with-optional (cond-> base
                        (seq (:object/counters obj-map))
                        (assoc :object/counters (:object/counters obj-map))

                        (seq (:object/grants obj-map))
                        (assoc :object/grants (:object/grants obj-map)))]
    (if (= zone :battlefield)
      (let [types     (card-types-set db card-eid)]
        (if (creature? types)
          (let [power     (card-base-stat db card-eid :card/power)
                toughness (card-base-stat db card-eid :card/toughness)]
            (cond-> with-optional
              (some? power)     (assoc :object/power power)
              (some? toughness) (assoc :object/toughness toughness)
              true              (assoc :object/summoning-sick false
                                       :object/damage-marked 0)))
          with-optional))
      with-optional)))


(defn- transact-zone!
  "Transact objects for a zone from the portable player map.
   Returns updated conn."
  [conn player-id zone obj-maps]
  (let [db        @conn
        owner-eid (get-player-eid db player-id)]
    (d/transact! conn
                 (vec (map-indexed
                        (fn [i obj-map]
                          (object-tx-for-zone db obj-map zone owner-eid i))
                        obj-maps)))))


;; ---------------------------------------------------------------------------
;; Battlefield triggers

(defn- create-battlefield-triggers!
  "For every object on the battlefield with :card/triggers, create trigger
   entities linked to the object. Mirrors the ETB path in resolution.cljs."
  [conn player-id]
  (let [db        @conn
        owner-eid (get-player-eid db player-id)
        bf-objects (d/q '[:find [(pull ?o [:db/id {:object/card [:card/id :card/triggers]}]) ...]
                          :in $ ?owner
                          :where [?o :object/owner ?owner]
                          [?o :object/zone :battlefield]]
                        db owner-eid)]
    (doseq [obj bf-objects]
      (let [card         (:object/card obj)
            card-triggers (:card/triggers card)]
        (when (seq card-triggers)
          (let [obj-eid (:db/id obj)
                tx      (trigger-db/create-triggers-for-card-tx
                          @conn obj-eid owner-eid card-triggers)]
            (d/transact! conn tx)))))))


;; ---------------------------------------------------------------------------
;; Game state entity

(defn- transact-game-state!
  "Transact the singleton :game-1 entity from the portable game state."
  [conn snapshot]
  (let [db           @conn
        active-id    (:game/active-player snapshot)
        prio-id      (:game/priority snapshot)
        active-eid   (get-player-eid db active-id)
        prio-eid     (get-player-eid db prio-id)
        base         {:game/id             :game-1
                      :game/turn           (or (:game/turn snapshot) 1)
                      :game/phase          (or (:game/phase snapshot) :main1)
                      :game/active-player  active-eid
                      :game/priority       prio-eid
                      :game/human-player-id (or (:game/human-player-id snapshot) :player-1)}]
    (d/transact! conn [(cond-> base
                         (:game/step snapshot)
                         (assoc :game/step (:game/step snapshot)))])))


;; ---------------------------------------------------------------------------
;; Public API

(defn restore-game-state
  "Reconstruct a playable app-db map from a decoded portable game-state map.

   Input: the map returned by fizzle.sharing.decoder/decode-snapshot.

   Returns an app-db map with:
   - :game/db           — fully-playable Datascript DB
   - :active-screen     — :game (skips opening-hand screen)
   - :history/*         — empty history (fresh branch)
   - UI defaults for collapsed panels"
  [snapshot]
  (let [conn   (d/create-conn schema)
        _      (d/transact! conn cards/all-cards)
        stops  (storage/load-stops)
        players (:players snapshot)]
    ;; Transact players
    (doseq [[player-id player-map] players]
      (let [is-opp       (boolean (:player/is-opponent player-map))
            bot-archetype (:player/bot-archetype player-map)]
        (d/transact! conn (player-tx player-id player-map is-opp bot-archetype))))
    ;; Add player stops from localStorage (same as init.cljs)
    (let [db       @conn
          p1-eid   (get-player-eid db game-state/human-player-id)
          p2-eid   (get-player-eid db game-state/opponent-player-id)
          bot-archetype (when p2-eid
                          (:player/bot-archetype (d/pull db [:player/bot-archetype] p2-eid)))]
      (when p1-eid
        (d/transact! conn [[:db/add p1-eid :player/stops (:player stops)]
                           [:db/add p1-eid :player/opponent-stops (or (:opponent-stops stops) #{})]]))
      (when p2-eid
        (d/transact! conn [[:db/add p2-eid :player/stops (bot-protocol/bot-stops bot-archetype)]])))
    ;; Transact objects for each zone
    (doseq [[player-id player-map] players]
      (transact-zone! conn player-id :hand        (:hand player-map))
      (transact-zone! conn player-id :graveyard   (:graveyard player-map))
      (transact-zone! conn player-id :exile       (:exile player-map))
      (transact-zone! conn player-id :library     (:library player-map))
      (transact-zone! conn player-id :battlefield (:battlefield player-map)))
    ;; Create triggers for battlefield permanents
    (doseq [[player-id _] players]
      (create-battlefield-triggers! conn player-id))
    ;; Create turn-based triggers for each player
    (doseq [[player-id _] players]
      (let [db       @conn
            player-eid (get-player-eid db player-id)]
        (d/transact! conn (turn-based/create-turn-based-triggers-tx player-eid player-id))))
    ;; Transact game state entity
    (transact-game-state! conn snapshot)
    ;; Build app-db map (mirrors init-game-state shape, but :active-screen :game)
    {:game/db              @conn
     :active-screen        :game
     :game/game-over-dismissed false
     :ui/stack-collapsed   false
     :ui/gy-collapsed      false
     :ui/history-collapsed false
     :history/main         []
     :history/forks        {}
     :history/current-branch nil
     :history/position    -1}))
