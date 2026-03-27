(ns fizzle.bots.interceptor
  "Bot decision logic.

   This module is the ONLY place that knows about bots in the event layer.
   It provides:
   - bot-should-act?: checks if the current priority holder is a bot
   - bot-decide-action: asks bot protocol for a decision and builds action plan
   - build-bot-dispatches: converts action plan to re-frame dispatch sequence
   - ::bot-decide: event handler that dispatches standard events for bot actions

   Bot checks fire via the :db effect handler chokepoint (events/db_effect.cljs),
   which queues ::bot-decide after every game-db mutation where a bot holds priority.
   Bot taps dispatch ::activate-mana-ability. Bot casts dispatch ::cast-spell with opts.
   Each dispatch goes through the full event pipeline (history, validation)."
  (:require
    [fizzle.bots.protocol :as bot]
    [fizzle.db.game-state :as game-state]
    [fizzle.db.queries :as queries]
    [fizzle.engine.priority :as priority]
    [fizzle.engine.rules :as rules]
    [re-frame.core :as rf]))


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


(defn- find-tap-sequence
  "Find the lands that need to be tapped to pay a mana cost.
   Returns a vector of {:object-id oid :mana-color color} maps.
   Pure function: (game-db, player-id, mana-cost) -> [{:object-id :mana-color}]"
  [game-db player-id mana-cost]
  (let [battlefield (queries/get-objects-in-zone game-db player-id :battlefield)]
    (reduce
      (fn [taps [color amount]]
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
                                                 (not (some #(= (:object/id obj) (:object-id %)) taps))
                                                 (some (fn [ability]
                                                         (and (= :mana (:ability/type ability))
                                                              (get (:ability/produces ability) produces-key)))
                                                       (get-in obj [:object/card :card/abilities])))))
                                  (take amount))]
          (into taps (map (fn [obj] {:object-id (:object/id obj) :mana-color produces-key}) untapped-lands))))
      []
      mana-cost)))


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
                needed-mana (reduce + 0 (vals mana-cost))
                have-taps (count tap-seq)]
            (if (< have-taps needed-mana)
              {:action :pass}
              {:action :cast-spell
               :object-id object-id
               :target target
               :player-id player-id
               :tap-sequence tap-seq})))))))


(defn build-bot-dispatches
  "Convert a bot action plan into a sequence of re-frame dispatches.
   Each tap becomes [:dispatch [::activate-mana-ability obj-id color player-id]]
   and the cast becomes [:dispatch [::cast-spell {:player-id pid :object-id oid :target tid}]].
   Appends ::bot-action-complete sentinel as the LAST dispatch.
   Returns a vector of [:dispatch [...]] entries for use in :fx.
   Pure function: (action-map) -> [[:dispatch [event-id args...]]]"
  [action]
  (let [player-id (:player-id action)
        tap-dispatches (mapv (fn [{:keys [object-id mana-color]}]
                               [:dispatch [:fizzle.events.abilities/activate-mana-ability
                                           object-id mana-color player-id]])
                             (:tap-sequence action))
        cast-dispatch [:dispatch [:fizzle.events.casting/cast-spell
                                  {:player-id player-id
                                   :object-id (:object-id action)
                                   :target (:target action)}]]
        sentinel [:dispatch [::bot-action-complete]]]
    (conj tap-dispatches cast-dispatch sentinel)))


;; === ::bot-decide Event Handler ===

(defn- find-bot-land-to-play
  "Find a land in the bot's hand that can legally be played.
   Uses rules/can-play-land? to enforce one-land-per-turn and phase checks.
   Returns the object-id of the land, or nil if none available.
   Pure function: (game-db, player-id) -> object-id | nil"
  [game-db player-id]
  (let [hand (queries/get-hand game-db player-id)]
    (some (fn [obj]
            (let [oid (:object/id obj)]
              (when (rules/can-play-land? game-db player-id oid)
                oid)))
          hand)))


(defn bot-decide-handler
  "Core logic for ::bot-decide. Returns re-frame effects map.
   Checks phase actions first (play land), then priority decisions (cast/pass).
   Exposed as public for testing.

   Action-pending guard: compound actions (tap+cast) set :bot/action-pending?
   on app-db and append a ::bot-action-complete sentinel to :fx. Stale
   ::bot-decide events from db_effect during intermediate states see the flag
   and return a silent no-op (no yield, no dispatches). The sentinel clears
   the flag and dispatches a fresh ::bot-decide against the settled state.

   Pure function: (app-db) -> {:db ... :fx [...]}"
  [app-db]
  (cond
    ;; Guard: compound action in progress — suppress stale bot-decide (no-op)
    (:bot/action-pending? app-db)
    {:db app-db}

    ;; Guard: not bot's turn, selection in progress, or safety limit — yield
    (let [game-db (:game/db app-db)]
      (or (not game-db)
          (:game/pending-selection app-db)
          (not (bot-should-act? game-db))
          (>= (or (:bot/action-count app-db) 0) 20)))
    {:db (dissoc app-db :bot/action-count)
     :fx [[:dispatch [:fizzle.events.priority-flow/yield]]]}

    ;; Normal decision path
    :else
    (let [game-db (:game/db app-db)
          bot-action-count (or (:bot/action-count app-db) 0)
          holder-eid (priority/get-priority-holder-eid game-db)
          player-id (some (fn [pid]
                            (when (= holder-eid (queries/get-player-eid game-db pid))
                              pid))
                          [game-state/human-player-id game-state/opponent-player-id])
          archetype (when player-id (bot/get-bot-archetype game-db player-id))
          ;; Check phase action first (e.g., play a land)
          game-state (queries/get-game-state game-db)
          current-phase (:game/phase game-state)
          phase-action (when archetype
                         (bot/bot-phase-action archetype current-phase game-db player-id))]
      (cond
        ;; Phase action: play a land (single dispatch, no flag needed)
        (and (= :play-land (:action phase-action))
             (find-bot-land-to-play game-db player-id))
        (let [land-id (find-bot-land-to-play game-db player-id)]
          {:db (assoc app-db :bot/action-count (inc bot-action-count))
           :fx [[:dispatch [:fizzle.events.lands/play-land land-id player-id]]]})

        ;; Check priority decision (cast spell or pass)
        :else
        (let [action (bot-decide-action game-db)]
          (if (= :pass (:action action))
            ;; Bot passes — dispatch ::yield to pass priority
            {:db (dissoc app-db :bot/action-count)
             :fx [[:dispatch [:fizzle.events.priority-flow/yield]]]}
            ;; Bot wants to cast — compound action: set flag, include sentinel
            (let [dispatches (build-bot-dispatches action)]
              {:db (assoc app-db
                          :bot/action-count (inc bot-action-count)
                          :bot/action-pending? true)
               :fx dispatches})))))))


(defn bot-action-complete-handler
  "Clear action-pending flag and dispatch fresh ::bot-decide.
   Called by ::bot-action-complete sentinel after compound action settles.
   Exposed as public for testing.
   Pure function: (app-db) -> {:db ... :fx [...]}"
  [app-db]
  {:db (dissoc app-db :bot/action-pending?)
   :fx [[:dispatch [::bot-decide]]]})


(rf/reg-event-fx
  ::bot-decide
  (fn [{:keys [db]} _]
    (bot-decide-handler db)))


(rf/reg-event-fx
  ::bot-action-complete
  (fn [{:keys [db]} _]
    (bot-action-complete-handler db)))
