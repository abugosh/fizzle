(ns fizzle.sharing.restorer
  "Snapshot restorer: portable game-state map → Datascript DB.

   Exact inverse of fizzle.sharing.extractor. Reconstructs a fully playable
   Datascript DB from the decoded portable map produced by
   fizzle.sharing.decoder/decode-snapshot.

   Objects receive fresh UUIDs — original UUIDs are not preserved across sessions.
   Card references are resolved via card-id → entity lookup (same as init.cljs).
   Triggers are embedded in objects at creation via build-object-tx (same as init.cljs).
   Turn-based triggers are recreated for both players."
  (:require
    [datascript.core :as d]
    [fizzle.bots.protocol :as bot-protocol]
    [fizzle.db.game-state :as game-state]
    [fizzle.db.schema :refer [schema]]
    [fizzle.db.storage :as storage]
    [fizzle.engine.cards :as cards]
    [fizzle.engine.object-spec :as object-spec]
    [fizzle.engine.objects :as objects]))


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

(defn- player-overrides-from-snapshot
  "Build player attribute overrides from a portable player map."
  [player-map is-opponent bot-archetype]
  (cond-> {:player/name            (if is-opponent "Opponent" "Player")
           :player/life            (or (:player/life player-map) 20)
           :player/mana-pool       (or (:player/mana-pool player-map)
                                       game-state/empty-mana-pool)
           :player/storm-count     (or (:player/storm-count player-map) 0)
           :player/land-plays-left (or (:player/land-plays-left player-map) 1)
           :player/max-hand-size   (or (:player/max-hand-size player-map) 7)
           :player/grants          (or (:player/grants player-map) [])}
    is-opponent   (assoc :player/is-opponent true)
    bot-archetype (assoc :player/bot-archetype bot-archetype)))


;; ---------------------------------------------------------------------------
;; Object transactions per zone

(defn- object-tx-for-zone
  "Build one object transaction map for restoring an object in a given zone.

   Delegates base construction to build-object-tx (the shared chokepoint),
   then overrides :object/tapped from snapshot and adds restore-specific
   optional fields (:object/counters, :object/grants).

   For battlefield creatures, also sets summoning-sick false and damage-marked 0
   (snapshot is mid-game, not just-entered — creature was already on battlefield).

   Cards with :card/triggers get their trigger entities embedded via build-object-tx."
  [db obj-map zone owner-eid position]
  (let [card-id     (:card/id obj-map)
        card-eid    (get-card-eid db card-id)
        card-data   (d/pull db [:card/types :card/power :card/toughness :card/triggers :card/replacement-effects] card-eid)
        creature?   (contains? (set (:card/types card-data)) :creature)
        pos         (if (= zone :library) position 0)
        result      (cond-> (-> (objects/build-object-tx db card-eid card-data zone owner-eid pos)
                                ;; Override tapped: restorer preserves snapshot state (build-object-tx defaults false)
                                (assoc :object/tapped (boolean (:object/tapped obj-map))))
                      (seq (:object/counters obj-map))
                      (assoc :object/counters (:object/counters obj-map))

                      (seq (:object/grants obj-map))
                      (assoc :object/grants (:object/grants obj-map))

                      ;; Battlefield creatures: set combat attrs (bypassing zone transition)
                      (and (= zone :battlefield) creature?)
                      (assoc :object/summoning-sick false
                             :object/damage-marked 0))]
    (object-spec/validate-object-tx! result)
    result))


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
    ;; Transact players (entity + turn-based triggers)
    (doseq [[player-id player-map] players]
      (let [is-opp        (boolean (:player/is-opponent player-map))
            bot-archetype (:player/bot-archetype player-map)
            overrides     (player-overrides-from-snapshot player-map is-opp bot-archetype)]
        (game-state/create-complete-player conn player-id overrides)))
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
    ;; Transact objects for each zone.
    ;; Triggers are embedded in objects at creation via build-object-tx — no post-creation registration needed.
    (doseq [[player-id player-map] players]
      (transact-zone! conn player-id :hand        (:hand player-map))
      (transact-zone! conn player-id :graveyard   (:graveyard player-map))
      (transact-zone! conn player-id :exile       (:exile player-map))
      (transact-zone! conn player-id :library     (:library player-map))
      (transact-zone! conn player-id :battlefield (:battlefield player-map)))
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
