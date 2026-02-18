(ns fizzle.bots.interceptor
  "Bot action interceptor and decision logic.

   This module is the ONLY place that knows about bots in the event layer.
   It provides:
   - bot-should-act?: checks if the current priority holder is a bot
   - bot-decide-action: asks bot protocol for a decision and builds action plan
   - execute-bot-cast: taps lands and casts spell through standard engine paths
   - execute-bot-actions: runs bot action loop with safety limit
   - bot-action-interceptor: re-frame interceptor that queues ::bot-decide
   - ::bot-decide: event handler that executes bot actions

   All game events (::cast-spell, ::yield, etc.) remain bot-unaware.
   The interceptor bridges between bot protocol decisions and standard events."
  (:require
    [fizzle.bots.protocol :as bot]
    [fizzle.db.queries :as queries]
    [fizzle.engine.priority :as priority]
    [fizzle.engine.rules :as rules]
    [fizzle.events.abilities :as abilities]
    [re-frame.core :as rf]))


(defn bot-should-act?
  "Check if the current priority holder is a bot.
   Returns true if the player entity holding priority has a bot archetype.
   Pure function: (game-db) -> boolean"
  [game-db]
  (let [holder-eid (priority/get-priority-holder-eid game-db)]
    (if holder-eid
      (let [;; Get the player-id for the priority holder
            player-id (some (fn [pid]
                              (when (= holder-eid (queries/get-player-eid game-db pid))
                                pid))
                            [:player-1 :player-2 :opponent])]
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
                                                 ;; Not already in our tap sequence
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
     {:action :cast-spell :object-id oid :target tid :tap-sequence [...]}
     {:action :pass}
   Pure function: (game-db) -> action-map"
  [game-db]
  (let [holder-eid (priority/get-priority-holder-eid game-db)
        ;; Find player-id from eid
        player-id (some (fn [pid]
                          (when (= holder-eid (queries/get-player-eid game-db pid))
                            pid))
                        [:player-1 :player-2 :opponent])
        archetype (when player-id (bot/get-bot-archetype game-db player-id))]
    (if-not archetype
      {:action :pass}
      (let [decision (bot/bot-priority-decision archetype {:db game-db :player-id player-id})]
        (if (= :pass decision)
          {:action :pass}
          ;; Bot wants to cast a spell
          (let [object-id (:object-id decision)
                target (:target decision)
                ;; Look up the card's mana cost to determine what to tap
                card (queries/get-card game-db object-id)
                mana-cost (or (:card/mana-cost card) {})
                tap-seq (find-tap-sequence game-db player-id mana-cost)]
            ;; Verify the bot can actually cast after tapping
            ;; If we can't find enough lands, pass instead
            (let [needed-mana (reduce + 0 (vals mana-cost))
                  have-taps (count tap-seq)]
              (if (< have-taps needed-mana)
                {:action :pass}
                {:action :cast-spell
                 :object-id object-id
                 :target target
                 :player-id player-id
                 :tap-sequence tap-seq}))))))))


(defn execute-bot-cast
  "Execute a bot cast action: tap lands then cast spell.
   Taps each land through activate-mana-ability, then casts via rules/cast-spell.
   Pure function: (game-db, player-id, action) -> game-db"
  [game-db player-id action]
  (let [;; Step 1: Tap lands in sequence
        db-after-taps (reduce
                        (fn [db {:keys [object-id mana-color]}]
                          (abilities/activate-mana-ability db player-id object-id mana-color))
                        game-db
                        (:tap-sequence action))
        ;; Step 2: Cast the spell through standard rules
        spell-id (:object-id action)]
    (if (rules/can-cast? db-after-taps player-id spell-id)
      (rules/cast-spell db-after-taps player-id spell-id)
      ;; Can't cast (e.g., Orim's Chant restriction) — return db with just taps applied
      db-after-taps)))


(defn execute-bot-actions
  "Run bot action loop with safety limit.
   Returns a vector of action results (for testing/debugging).
   Stops when bot passes or limit reached.
   Pure function: (game-db, player-id, limit) -> [action-results]"
  [game-db player-id limit]
  (loop [db game-db
         actions []
         n limit]
    (if (zero? n)
      actions
      (let [action (bot-decide-action db)]
        (if (= :pass (:action action))
          (conj actions action)
          (let [new-db (execute-bot-cast db player-id action)]
            (recur new-db
                   (conj actions action)
                   (dec n))))))))


;; === Re-frame Interceptor ===

(def ^:private bot-trigger-events
  "Events after which the bot interceptor checks whether to queue ::bot-decide.
   These are events that can change who holds priority."
  #{:fizzle.events.game/yield
    :fizzle.events.game/yield-all
    :fizzle.events.game/resolve-top
    :fizzle.events.game/advance-phase
    :fizzle.events.game/start-turn
    :fizzle.events.game/cast-and-yield})


(def ^:private max-bot-actions
  "Safety limit: maximum number of consecutive bot actions per priority window."
  20)


(def bot-action-interceptor
  "Global re-frame interceptor that checks if the priority holder is a bot
   after game-state-changing events. If yes, injects a ::bot-decide dispatch.

   Runs in the :after phase of the interceptor chain. Only fires for events
   in bot-trigger-events. Does not fire if a pending-selection exists
   (bot can't act during interactive selections)."
  (rf/->interceptor
    :id :bot/action
    :after (fn [context]
             (let [event (get-in context [:coeffects :event])
                   event-id (first event)]
               (if-not (bot-trigger-events event-id)
                 context
                 (let [db-after (get-in context [:effects :db])
                       game-db (when db-after (:game/db db-after))]
                   (if (or (not game-db)
                           (:game/pending-selection db-after)
                           (not (bot-should-act? game-db)))
                     context
                     ;; Bot should act — inject ::bot-decide dispatch
                     (let [existing-fx (get-in context [:effects :fx] [])]
                       (assoc-in context [:effects :fx]
                                 (conj existing-fx [:dispatch [::bot-decide]]))))))))))


;; === ::bot-decide Event Handler ===

(rf/reg-event-fx
  ::bot-decide
  (fn [{:keys [db]} _]
    (let [game-db (:game/db db)]
      (if (or (not game-db)
              (:game/pending-selection db)
              (not (bot-should-act? game-db)))
        ;; Not a bot's turn or selection in progress — no-op
        {:db db}
        (let [action (bot-decide-action game-db)
              ;; Track consecutive bot actions to prevent infinite loops
              bot-action-count (or (:bot/action-count db) 0)]
          (if (or (= :pass (:action action))
                  (>= bot-action-count max-bot-actions))
            ;; Bot passes or safety limit reached — dispatch ::yield to pass priority
            {:db (dissoc db :bot/action-count)
             :fx [[:dispatch [:fizzle.events.game/yield]]]}
            ;; Bot wants to cast — execute the cast, then re-check
            (let [player-id (:player-id action)
                  new-game-db (execute-bot-cast game-db player-id action)]
              {:db (-> db
                       (assoc :game/db new-game-db)
                       (assoc :bot/action-count (inc bot-action-count)))
               :fx [[:dispatch [::bot-decide]]]})))))))


(defn register!
  "Register the bot action interceptor globally. Call once during app initialization."
  []
  (rf/reg-global-interceptor bot-action-interceptor))
