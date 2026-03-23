(ns fizzle.events.game
  (:require
    [fizzle.db.queries :as queries]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.zones :as zones]
    [fizzle.events.abilities]
    [fizzle.events.casting :as casting]
    [fizzle.events.cleanup :as cleanup]
    [fizzle.events.init :as init]
    [fizzle.events.lands :as lands]
    [fizzle.events.phases :as phases]
    [fizzle.events.priority-flow :as priority-flow]
    [fizzle.events.resolution :as resolution]
    [fizzle.events.selection]
    [fizzle.events.selection.library]
    [fizzle.events.selection.untap]
    [fizzle.events.selection.zone-ops]
    [fizzle.events.ui :as ui]
    [re-frame.core :as rf]))


;; Re-export public API from extracted modules for backward compatibility
(def init-game-state init/init-game-state)
(def set-active-screen-handler ui/set-active-screen-handler)
(def begin-cleanup cleanup/begin-cleanup)
(def complete-cleanup-discard cleanup/complete-cleanup-discard)
(def maybe-continue-cleanup cleanup/maybe-continue-cleanup)
(def phases phases/phases)
(def next-phase phases/next-phase)
(def advance-phase phases/advance-phase)
(def untap-all-permanents phases/untap-all-permanents)
(def start-turn phases/start-turn)
(def land-card? lands/land-card?)
(def can-play-land? lands/can-play-land?)
(def play-land lands/play-land)
(def tap-permanent lands/tap-permanent)
(def cast-spell-handler casting/cast-spell-handler)
(def evaluate-pre-cast-step casting/evaluate-pre-cast-step)
(def pre-cast-pipeline casting/pre-cast-pipeline)
(def select-casting-mode-handler casting/select-casting-mode-handler)
(def cancel-mode-selection-handler casting/cancel-mode-selection-handler)
(def resolve-one-item resolution/resolve-one-item)
(def advance-with-stops priority-flow/advance-with-stops)
(def negotiate-priority priority-flow/negotiate-priority)
(def yield-impl priority-flow/yield-impl)


;; Old keyword forwarding for backward compat (tests dispatch :fizzle.events.game/ keywords)
(rf/reg-event-db
  :fizzle.events.game/cast-spell
  (fn [db [_ opts]] (casting/cast-spell-handler db opts)))


(rf/reg-event-db
  :fizzle.events.game/select-casting-mode
  (fn [db [_ mode]] (casting/select-casting-mode-handler db mode)))


(rf/reg-event-db
  :fizzle.events.game/cancel-mode-selection
  (fn [db _] (casting/cancel-mode-selection-handler db)))


(rf/reg-event-db
  :fizzle.events.game/resolve-top
  (fn [db _]
    (let [result (resolution/resolve-one-item (:game/db db))]
      (if (:pending-selection result)
        (-> db
            (assoc :game/db (:db result))
            (assoc :game/pending-selection (:pending-selection result)))
        (cleanup/maybe-continue-cleanup
          (assoc db :game/db (:db result)))))))


(rf/reg-event-fx
  :fizzle.events.game/resolve-all
  (fn [{:keys [db]} [_ initial-ids]]
    (let [game-db (:game/db db)
          initial-ids (or initial-ids
                          (set (map :db/id (queries/get-all-stack-items game-db))))]
      (if (empty? initial-ids)
        {:db db}
        (let [top (stack/get-top-stack-item game-db)]
          (if (and top (contains? initial-ids (:db/id top)))
            (let [result (resolution/resolve-one-item game-db)]
              (if (:pending-selection result)
                {:db (-> db
                         (assoc :game/db (:db result))
                         (assoc :game/pending-selection (:pending-selection result)))}
                {:db (assoc db :game/db (:db result))
                 :fx [[:dispatch [:fizzle.events.game/resolve-all initial-ids]]]}))
            ;; Stack empty or new item on top — done, check cleanup
            {:db (cleanup/maybe-continue-cleanup (assoc db :game/db game-db))}))))))


(rf/reg-event-fx
  :fizzle.events.game/yield
  (fn [{:keys [db]} _]
    (priority-flow/yield-handler db)))


(rf/reg-event-fx
  :fizzle.events.game/yield-all
  (fn [{:keys [db]} _]
    (priority-flow/yield-all-handler db)))


(rf/reg-event-fx
  :fizzle.events.game/cast-and-yield
  (fn [{:keys [db]} _]
    (priority-flow/cast-and-yield-handler db)))


(rf/reg-event-db
  :fizzle.events.game/cast-and-yield-resolve
  (fn [db _]
    (priority-flow/cast-and-yield-resolve-handler db)))


;; =====================================================
;; Cycling
;; =====================================================

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
            db-after-discard (zones/move-to-zone db-after-pay object-id :graveyard)
            top-card (first (queries/get-top-n-library db-after-discard player-id 1))
            db-final (if top-card
                       (zones/move-to-zone db-after-discard top-card :hand)
                       db-after-discard)]
        {:db db-final}))))


(rf/reg-event-db
  ::cycle-card
  (fn [db [_ object-id player-id]]
    (let [game-db (:game/db db)
          pid (or player-id (queries/get-human-player-id game-db))
          result (cycle-card game-db pid object-id)]
      (assoc db :game/db (:db result)))))
