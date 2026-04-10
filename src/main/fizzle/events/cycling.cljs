(ns fizzle.events.cycling
  "Cycling ability activation.
   Pure function and re-frame event handler."
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.zone-change-dispatch :as zone-change-dispatch]
    [fizzle.history.descriptions :as descriptions]
    [re-frame.core :as rf]))


(defn cycle-card
  "Activate the cycling ability of a card in hand.
   Generic: works for any card with :card/cycling mana-cost key.

   Validates:
   - Card is in the activating player's hand
   - Card has :card/cycling key
   - Player has enough mana to pay the cycling cost

   On success:
   1. Pays the cycling cost
   2. Moves the card to graveyard (self-discard)
   3. Draws 1 card

   Returns {:db updated-db} on success, or {:db original-db} on failure."
  [game-db player-id object-id]
  (let [obj (queries/get-object game-db object-id)
        card (:object/card obj)
        cycling-cost (:card/cycling card)
        zone (:object/zone obj)
        player-eid (queries/get-player-eid game-db player-id)]
    (if-not (and obj player-eid
                 (= zone :hand)
                 cycling-cost
                 (mana/can-pay? game-db player-id cycling-cost))
      {:db game-db}
      (let [db-after-pay (mana/pay-mana game-db player-id cycling-cost)
            db-after-discard (zone-change-dispatch/move-to-zone db-after-pay object-id :graveyard)
            top-card (first (queries/get-top-n-library db-after-discard player-id 1))
            db-final (if top-card
                       (zone-change-dispatch/move-to-zone db-after-discard top-card :hand)
                       db-after-discard)]
        {:db db-final}))))


(rf/reg-event-db
  ::cycle-card
  (fn [db [_ object-id player-id]]
    (let [game-db (:game/db db)
          pid (or player-id (queries/get-human-player-id game-db))
          description (descriptions/describe-cycle-card object-id game-db)
          result (cycle-card game-db pid object-id)
          game-db-after (:db result)]
      (-> db
          (assoc :game/db game-db-after)
          (assoc :history/pending-entry
                 (descriptions/build-pending-entry game-db-after ::cycle-card description pid))))))
