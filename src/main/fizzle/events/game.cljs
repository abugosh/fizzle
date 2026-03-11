(ns fizzle.events.game
  (:require
    [datascript.core :as d]
    [fizzle.bots.protocol :as bot-protocol]
    [fizzle.db.queries :as queries]
    [fizzle.engine.priority :as priority]
    [fizzle.engine.resolution :as engine-resolution]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.static-abilities :as static-abilities]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.abilities]
    [fizzle.events.cleanup :as cleanup]
    [fizzle.events.init :as init]
    [fizzle.events.lands :as lands]
    [fizzle.events.phases :as phases]
    [fizzle.events.selection]
    [fizzle.events.selection.combat :as sel-combat]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.events.selection.costs :as sel-costs]
    [fizzle.events.selection.library]
    [fizzle.events.selection.storm :as sel-storm]
    [fizzle.events.selection.targeting :as sel-targeting]
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


;; =====================================================
;; Data-Driven Pre-Cast Pipeline (ADR-015)
;; =====================================================
;; Each pre-cast step is a defmethod on evaluate-pre-cast-step.
;; Steps return nil (skip), {:selection sel} (pause for input),
;; or {:db db} (terminal — casting complete).

(defmulti evaluate-pre-cast-step
  "Evaluate a single pre-cast pipeline step.
   Dispatches on step keyword.

   Arguments:
     step - Keyword identifying the pre-cast step
     ctx  - Map with :game-db, :player-id, :object-id, :mode, :target

   Returns:
     nil           — skip this step (condition not met)
     {:selection s} — pause pipeline for player input
     {:db db}       — terminal, casting complete"
  (fn [step _ctx] step))


(def pre-cast-pipeline
  "Ordered vector of pre-cast step keywords. Evaluated left-to-right.
   Costs before targeting, targeting before mana allocation."
  [:exile-cards-cost :return-land-cost :discard-specific-cost :sacrifice-permanent-cost
   :pay-x-life :x-mana-cost :targeting :mana-allocation])


(defmethod evaluate-pre-cast-step :exile-cards-cost
  [_ {:keys [game-db player-id object-id mode]}]
  (when (sel-costs/has-exile-cards-x-cost? mode)
    (let [exile-cost (sel-costs/get-exile-cards-x-cost mode)
          sel (sel-costs/build-exile-cards-selection game-db player-id object-id mode exile-cost)]
      (when sel
        {:selection sel}))))


(defmethod evaluate-pre-cast-step :return-land-cost
  [_ {:keys [game-db player-id object-id mode]}]
  (when (sel-costs/has-return-land-cost? mode)
    (let [return-cost (sel-costs/get-return-land-cost mode)
          sel (sel-costs/build-return-land-selection game-db player-id object-id mode return-cost)]
      (when sel
        {:selection sel}))))


(defmethod evaluate-pre-cast-step :discard-specific-cost
  [_ {:keys [game-db player-id object-id mode]}]
  (when (sel-costs/has-discard-specific-cost? mode)
    (let [discard-cost (sel-costs/get-discard-specific-cost mode)
          sel (sel-costs/build-discard-specific-selection game-db player-id object-id mode discard-cost)]
      (when sel
        {:selection sel}))))


(defmethod evaluate-pre-cast-step :sacrifice-permanent-cost
  [_ {:keys [game-db player-id object-id mode]}]
  (when (sel-costs/has-sacrifice-permanent-cost? mode)
    (let [sac-cost (sel-costs/get-sacrifice-permanent-cost mode)
          sel (sel-costs/build-sacrifice-permanent-selection game-db player-id object-id mode sac-cost)]
      (when sel
        {:selection sel}))))


(defmethod evaluate-pre-cast-step :pay-x-life
  [_ {:keys [game-db player-id object-id mode]}]
  (when (sel-costs/has-pay-x-life-cost? mode)
    {:selection (sel-costs/build-pay-x-life-selection game-db player-id object-id mode)}))


(defmethod evaluate-pre-cast-step :x-mana-cost
  [_ {:keys [game-db player-id object-id mode]}]
  (when (sel-costs/has-mana-x-cost? mode)
    {:selection (sel-costs/build-x-mana-selection game-db player-id object-id mode)}))


(defmethod evaluate-pre-cast-step :targeting
  [_ {:keys [game-db player-id object-id mode target]}]
  (let [obj (queries/get-object game-db object-id)
        card (:object/card obj)
        chosen-mode (:object/chosen-mode obj)
        targeting-reqs (if chosen-mode
                         (or (:mode/targeting chosen-mode) [])
                         (targeting/get-targeting-requirements card))]
    (when (seq targeting-reqs)
      (let [first-req (first targeting-reqs)]
        (if target
          ;; Pre-determined target: cast directly
          (let [sel {:selection/player-id player-id
                     :selection/object-id object-id
                     :selection/mode mode
                     :selection/target-requirement first-req
                     :selection/selected #{target}}]
            {:db (sel-targeting/confirm-cast-time-target game-db sel)})
          ;; Interactive targeting
          (let [valid-targets (targeting/find-valid-targets game-db player-id first-req)]
            (if (and (= :player (:target/type first-req))
                     (= 1 (count valid-targets)))
              ;; Single player target — auto-cast without dialog
              (let [auto-target (first valid-targets)
                    sel {:selection/player-id player-id
                         :selection/object-id object-id
                         :selection/mode mode
                         :selection/target-requirement first-req
                         :selection/selected #{auto-target}}]
                {:db (sel-targeting/confirm-cast-time-target game-db sel)})
              ;; Multiple targets or object targeting — show selection
              {:selection (sel-targeting/build-cast-time-target-selection
                            game-db player-id object-id mode first-req)})))))))


(defmethod evaluate-pre-cast-step :mana-allocation
  [_ {:keys [game-db player-id object-id mode]}]
  (let [effective-cost (static-abilities/get-effective-mana-cost
                         game-db player-id object-id mode)]
    (when (sel-costs/has-generic-mana-cost? effective-cost)
      (if-let [sel (sel-costs/build-mana-allocation-selection
                     game-db player-id object-id mode effective-cost)]
        {:selection sel}
        ;; nil from builder means 0 generic (defensive fallback) — cast directly
        {:db (rules/cast-spell-mode game-db player-id object-id mode)}))))


(defn- initiate-cast-with-mode
  "Start casting a spell with a specific mode.
   Evaluates the pre-cast pipeline steps in order. Each step checks for a
   pre-cast requirement (additional cost, targeting, mana allocation) and
   returns nil (skip), {:selection sel} (pause for input), or {:db db} (done).
   If all steps return nil, casts immediately.
   Returns updated app-db."
  [app-db player-id object-id mode target]
  (let [game-db (:game/db app-db)
        ctx {:game-db game-db :player-id player-id
             :object-id object-id :mode mode :target target}]
    (loop [steps pre-cast-pipeline]
      (if (empty? steps)
        ;; All steps skipped — cast immediately
        (-> app-db
            (assoc :game/db (rules/cast-spell-mode game-db player-id object-id mode))
            (dissoc :game/selected-card))
        (let [result (evaluate-pre-cast-step (first steps) ctx)]
          (cond
            (nil? result) (recur (rest steps))
            (:selection result) (-> app-db
                                    (assoc :game/pending-selection (:selection result))
                                    (dissoc :game/selected-card))
            (:db result) (-> app-db
                             (assoc :game/db (:db result))
                             (dissoc :game/selected-card))))))))


(defn- get-valid-spell-modes
  "For a modal card (:card/modes), return only modes that have valid targets.
   Returns nil for non-modal cards."
  [game-db player-id card]
  (when-let [card-modes (:card/modes card)]
    (filterv (fn [spell-mode]
               (let [targeting (or (:mode/targeting spell-mode) [])]
                 (every? (fn [req]
                           (if (:target/required req)
                             (seq (targeting/find-valid-targets game-db player-id req))
                             true))
                         targeting)))
             card-modes)))


(defn- build-spell-mode-selection
  "Build a spell-mode selection for a modal card.
   Mode maps are used as candidate identifiers — select-count 1, auto-confirm true.
   Per-mode targeting evaluation happens here (valid modes only).
   Returns selection map for :game/pending-selection."
  [player-id object-id valid-modes]
  {:selection/type :spell-mode
   :selection/lifecycle :finalized
   :selection/player-id player-id
   :selection/object-id object-id
   :selection/candidates valid-modes
   :selection/selected #{}
   :selection/select-count 1
   :selection/auto-confirm? true
   :selection/validation :exact
   :selection/on-complete {:continuation/type :cast-after-spell-mode
                           :continuation/object-id object-id}})


(defmethod sel-core/execute-confirmed-selection :spell-mode
  [game-db selection]
  (let [chosen-mode (first (:selection/selected selection))
        object-id (:selection/object-id selection)
        obj-eid (queries/get-object-eid game-db object-id)]
    {:db (if (and chosen-mode obj-eid)
           (d/db-with game-db [[:db/add obj-eid :object/chosen-mode chosen-mode]])
           game-db)}))


(defmethod sel-core/apply-continuation :cast-after-spell-mode
  [continuation app-db]
  (let [object-id (:continuation/object-id continuation)
        game-db (:game/db app-db)
        player-id (queries/get-human-player-id game-db)
        modes (rules/get-casting-modes game-db player-id object-id)
        primary (first (filter #(= :primary (:mode/id %)) modes))
        casting-mode (or primary (first modes))]
    (initiate-cast-with-mode app-db player-id object-id casting-mode nil)))


(defn cast-spell-handler
  "Handle cast-spell event: check casting modes and either auto-cast,
   show mode selector, or initiate casting with X cost/targeting checks.
   Accepts optional opts map with :player-id, :object-id, :target.
   When opts provided, uses explicit values instead of human-pid/selected-card.
   Pure function: (app-db, opts?) -> app-db"
  ([app-db] (cast-spell-handler app-db nil))
  ([app-db opts]
   (let [game-db (:game/db app-db)
         player-id (or (:player-id opts)
                       (queries/get-human-player-id game-db))
         object-id (or (:object-id opts)
                       (:game/selected-card app-db))
         target (:target opts)]
     (if (and object-id (rules/can-cast? game-db player-id object-id))
       (let [obj (queries/get-object game-db object-id)
             card (:object/card obj)
             valid-spell-modes (get-valid-spell-modes game-db player-id card)]
         (if (seq valid-spell-modes)
           ;; Modal card: spell mode selection through standard selection system
           (assoc app-db :game/pending-selection
                  (build-spell-mode-selection player-id object-id valid-spell-modes))
           ;; Non-modal card: proceed with casting mode selection
           (let [modes (rules/get-casting-modes game-db player-id object-id)
                 castable-modes (filterv #(rules/can-cast-mode? game-db player-id object-id %) modes)]
             (cond
               ;; No castable modes - shouldn't happen if can-cast? passed
               (empty? castable-modes)
               app-db

               ;; Multiple modes: show selector first (X costs/targeting checked after mode selection)
               (> (count castable-modes) 1)
               (assoc app-db :game/pending-mode-selection
                      {:object-id object-id
                       :modes castable-modes})

               ;; Single mode: check for X costs, targeting, then cast
               :else
               (initiate-cast-with-mode app-db player-id object-id (first castable-modes) target)))))
       app-db))))


(rf/reg-event-db
  ::cast-spell
  (fn [db [_ opts]]
    (cast-spell-handler db opts)))


(defn- get-source-id
  "Get the source object-id for a stack-item (for selection building)."
  [game-db stack-item]
  (or (when-let [obj-ref-raw (:stack-item/object-ref stack-item)]
        (let [obj-ref (if (map? obj-ref-raw) (:db/id obj-ref-raw) obj-ref-raw)
              obj (d/pull game-db [:object/id] obj-ref)]
          (:object/id obj)))
      (:stack-item/source stack-item)))


(defn- build-selection-from-result
  "Build pending-selection from a multimethod result that has :needs-selection.
   Handles stored-player for targeted abilities (Cephalid Coliseum) and
   adjusts selection fields for abilities vs spells."
  [game-db player-id stack-item result]
  (let [stack-item-eid (:db/id stack-item)
        source-id (get-source-id game-db stack-item)
        stored-player (:stored-player result)
        sel-effect (:needs-selection result)
        use-stored-player (and stored-player
                               (= :player (:effect/selection sel-effect)))
        sel-player-id (if use-stored-player stored-player player-id)
        sel (sel-core/build-selection-for-effect
              (:db result) sel-player-id source-id
              sel-effect
              (vec (:remaining-effects result)))
        sel (if (= :activated-ability (:stack-item/type stack-item))
              (-> sel
                  (dissoc :selection/spell-id)
                  (assoc :selection/stack-item-eid stack-item-eid
                         :selection/source-type :stack-item))
              sel)]
    {:db (:db result) :pending-selection sel}))


(defn- clear-peek-result
  "Clear any stale :game/peek-result from a previous resolution."
  [db]
  (let [game-state (queries/get-game-state db)]
    (if-let [old-val (:game/peek-result game-state)]
      (let [game-eid (d/q '[:find ?g . :where [?g :game/id _]] db)]
        (d/db-with db [[:db/retract game-eid :game/peek-result old-val]]))
      db)))


(defn resolve-one-item
  "Resolve the topmost stack-item. Returns {:db} or {:db :pending-selection}.
   Controller identity comes from the stack-item itself — no player-id parameter."
  [game-db]
  (let [game-db (clear-peek-result game-db)
        top (stack/get-top-stack-item game-db)]
    (if-not top
      {:db game-db}
      (let [controller (:stack-item/controller top)
            result (engine-resolution/resolve-stack-item game-db top)]
        (cond
          (:needs-storm-split result)
          (if-let [sel (sel-storm/build-storm-split-selection game-db controller top)]
            {:db game-db :pending-selection sel}
            {:db (stack/remove-stack-item game-db (:db/id top))})

          (:needs-attackers result)
          (let [eligible (:eligible-attackers result)
                archetype (bot-protocol/get-bot-archetype game-db controller)]
            (if archetype
              ;; Bot chooses attackers via configurable rules
              (let [chosen (bot-protocol/bot-choose-attackers archetype eligible)
                    sel (sel-combat/build-attacker-selection
                          eligible controller (:db/id top))
                    sel (assoc sel :selection/selected (set chosen))
                    app-db {:game/db game-db :game/pending-selection sel}
                    result-db (sel-core/confirm-selection-impl app-db)]
                {:db (:game/db result-db)})
              ;; Human gets attacker selection UI
              {:db game-db
               :pending-selection (sel-combat/build-attacker-selection
                                    eligible controller (:db/id top))}))

          (:needs-blockers result)
          (let [attackers (:attackers result)
                defender-id (:defender-id result)
                sel (sel-combat/build-blocker-selection
                      game-db attackers defender-id (:db/id top))]
            {:db game-db
             :pending-selection sel})

          (:needs-selection result)
          (build-selection-from-result game-db controller top result)

          :else
          {:db (stack/remove-stack-item (:db result) (:db/id top))})))))


(rf/reg-event-db
  ::resolve-top
  (fn [db _]
    (let [result (resolve-one-item (:game/db db))]
      (if (:pending-selection result)
        (-> db
            (assoc :game/db (:db result))
            (assoc :game/pending-selection (:pending-selection result)))
        (cleanup/maybe-continue-cleanup
          (assoc db :game/db (:db result)))))))


(rf/reg-event-fx
  ::resolve-all
  (fn [{:keys [db]} [_ initial-ids]]
    (let [game-db (:game/db db)
          initial-ids (or initial-ids
                          (set (map :db/id (queries/get-all-stack-items game-db))))]
      (if (empty? initial-ids)
        {:db db}
        (let [top (stack/get-top-stack-item game-db)]
          (if (and top (contains? initial-ids (:db/id top)))
            (let [result (resolve-one-item game-db)]
              (if (:pending-selection result)
                {:db (-> db
                         (assoc :game/db (:db result))
                         (assoc :game/pending-selection (:pending-selection result)))}
                {:db (assoc db :game/db (:db result))
                 :fx [[:dispatch [::resolve-all initial-ids]]]}))
            ;; Stack empty or new item on top — done, check cleanup
            {:db (cleanup/maybe-continue-cleanup (assoc db :game/db game-db))}))))))


;; === Priority System ===

(defn advance-with-stops
  "Advance phases until a stop is hit or a turn boundary is crossed.
   Handles cleanup and start-turn automatically.
   After crossing a turn boundary, returns immediately — the caller (yield-impl)
   handles continuing through the new turn via recursive ::yield dispatch.
   When ignore-stops? is true (F6 mode), skips player phase stop checks.
   Bot-agnostic: checks stops for any player the same way.
   Returns {:app-db app-db'} with game-db at the stopped phase.
   Pure function: (app-db, ignore-stops?) -> {:app-db app-db'}"
  [app-db ignore-stops?]
  (let [game-db (:game/db app-db)
        active-player-id (queries/get-active-player-id game-db)
        player-eid (queries/get-player-eid game-db active-player-id)]
    (loop [gdb game-db]
      (let [game-state (queries/get-game-state gdb)
            current-phase (:game/phase game-state)
            nxt (phases/next-phase current-phase)]
        (if (= nxt :cleanup)
          ;; Advancing to cleanup: advance phase, begin cleanup, then cross turn boundary
          (let [advanced-db (phases/advance-phase gdb active-player-id)
                cleanup-result (cleanup/begin-cleanup advanced-db active-player-id)]
            (if (:pending-selection cleanup-result)
              ;; Cleanup needs discard — pause with pending-selection
              {:app-db (-> app-db
                           (assoc :game/db (:db cleanup-result))
                           (assoc :game/pending-selection (:pending-selection cleanup-result)))}
              ;; No discard needed — cross turn boundary (start-turn switches active player)
              (let [db-after-cleanup (:db cleanup-result)
                    db-after-turn (phases/start-turn db-after-cleanup active-player-id)]
                ;; Always return at turn boundary — yield-impl handles continuation
                ;; via recursive ::yield dispatch (re-reads active player from db)
                {:app-db (assoc app-db :game/db db-after-turn)})))
          ;; Normal phase advance
          (let [advanced-db (phases/advance-phase gdb active-player-id)
                ;; Read actual phase from advanced-db (may differ from nxt
                ;; when combat is skipped due to no creatures)
                actual-phase (:game/phase (queries/get-game-state advanced-db))]
            ;; Check if new phase triggers anything on the stack
            (if (not (queries/stack-empty? advanced-db))
              ;; Stack triggered — stop and give priority
              {:app-db (assoc app-db :game/db advanced-db)}
              ;; Check stop (skipped in F6 mode)
              (if (and (not ignore-stops?)
                       (priority/check-stop advanced-db player-eid actual-phase))
                ;; Stop hit — pause here
                {:app-db (assoc app-db :game/db advanced-db)}
                ;; No stop or F6 — continue advancing
                (recur advanced-db)))))))))


(defn- yield-resolve-stack
  "Handle yield when all passed and stack is not empty: resolve top item.
   Returns {:app-db, :continue-yield?} or {:app-db} with pending-selection."
  [app-db]
  (let [game-db (:game/db app-db)
        auto-mode (priority/get-auto-mode game-db)
        result (resolve-one-item game-db)]
    (if (:pending-selection result)
      ;; Selection needed — clear auto-mode, return selection
      {:app-db (-> app-db
                   (assoc :game/db (priority/clear-auto-mode (:db result)))
                   (assoc :game/pending-selection (:pending-selection result)))}
      ;; Resolved one item
      (let [resolved-db (:db result)]
        (if (not (queries/stack-empty? resolved-db))
          ;; More items on stack — only cascade in auto-mode.
          ;; Without auto-mode (manual yield), stop after one resolution
          ;; so the player gets priority to respond to remaining items.
          (if auto-mode
            {:app-db (assoc app-db :game/db resolved-db)
             :continue-yield? true}
            {:app-db (assoc app-db :game/db resolved-db)})
          ;; Stack empty — clear :resolving auto-mode if active
          (let [resolved-db (if (= :resolving auto-mode)
                              (priority/clear-auto-mode resolved-db)
                              resolved-db)]
            {:app-db (cleanup/maybe-continue-cleanup
                       (assoc app-db :game/db resolved-db))}))))))


(defn- player-is-bot?
  "Check if a player entity has a bot archetype set.
   Uses DB data only (no bot protocol calls).
   Pure function: (db, player-eid) -> boolean"
  [db player-eid]
  (boolean (:player/bot-archetype (d/pull db [:player/bot-archetype] player-eid))))


(defn- bot-would-pass?
  "Check if bot player would pass priority in current game state.
   Returns true if bot has no action to take (should auto-pass).
   Pure function: (game-db, opponent-player-id) -> boolean"
  [game-db opponent-player-id]
  (let [archetype (bot-protocol/get-bot-archetype game-db opponent-player-id)]
    (if archetype
      (= :pass (bot-protocol/bot-priority-decision
                 archetype {:db game-db :player-id opponent-player-id}))
      true)))


(defn- bot-turn-advance-one-phase
  "Advance exactly one phase during a bot's turn, handling cleanup/turn boundary.
   Returns {:app-db} with the game state after advancing one phase.
   Pure function: (app-db) -> {:app-db app-db'}"
  [app-db]
  (let [game-db (:game/db app-db)
        active-player-id (queries/get-active-player-id game-db)
        game-state (queries/get-game-state game-db)
        current-phase (:game/phase game-state)
        nxt (phases/next-phase current-phase)]
    (if (= nxt :cleanup)
      ;; Advancing to cleanup: advance, begin cleanup, cross turn boundary
      (let [advanced-db (phases/advance-phase game-db active-player-id)
            cleanup-result (cleanup/begin-cleanup advanced-db active-player-id)]
        (if (:pending-selection cleanup-result)
          {:app-db (-> app-db
                       (assoc :game/db (:db cleanup-result))
                       (assoc :game/pending-selection (:pending-selection cleanup-result)))}
          (let [db-after-cleanup (:db cleanup-result)
                db-after-turn (phases/start-turn db-after-cleanup active-player-id)]
            {:app-db (assoc app-db :game/db db-after-turn)})))
      ;; Normal: advance one phase
      {:app-db (assoc app-db :game/db (phases/advance-phase game-db active-player-id))})))


(defn- yield-advance-phase
  "Handle yield when all passed and stack is empty: advance phases.
   During a bot's turn, advances one phase at a time (returning :continue-yield?)
   so the event loop can fire the bot interceptor for actions like playing lands.
   During a human's turn, uses advance-with-stops to batch through to the next stop.
   Returns {:app-db, :continue-yield?} or {:app-db} with pending-selection."
  [app-db]
  (let [game-db (:game/db app-db)
        auto-mode (priority/get-auto-mode game-db)
        f6? (= :f6 auto-mode)
        active-player-id (queries/get-active-player-id game-db)
        active-eid (queries/get-player-eid game-db active-player-id)
        bot-turn? (player-is-bot? game-db active-eid)
        priority-holder-eid (priority/get-priority-holder-eid game-db)
        bot-driving? (and bot-turn? (player-is-bot? game-db priority-holder-eid))]
    (if bot-driving?
      ;; Bot interceptor driving during bot's turn: one phase at a time
      (let [result (bot-turn-advance-one-phase app-db)
            result-db (:game/db (:app-db result))
            new-active-id (queries/get-active-player-id result-db)
            crossed-turn? (not= active-player-id new-active-id)
            new-phase (:game/phase (queries/get-game-state result-db))]
        (cond
          ;; Pending selection (e.g., cleanup discard)
          (:game/pending-selection (:app-db result))
          result

          ;; Turn boundary crossed — continue
          crossed-turn?
          (let [human-pid (queries/get-human-player-id result-db)
                landed-on-human? (= new-active-id human-pid)]
            (if (and landed-on-human? f6?)
              {:app-db (update (:app-db result) :game/db priority/clear-auto-mode)
               :continue-yield? true}
              {:app-db (:app-db result)
               :continue-yield? true}))

          ;; Stop hit on bot's turn (human's opponent-turn stops) — pause unless F6
          (and (not f6?)
               (priority/check-stop result-db active-eid new-phase))
          {:app-db (:app-db result)}

          ;; Same turn, no stop — pause for bot interceptor to dispatch ::bot-decide
          :else
          {:app-db (:app-db result)}))

      ;; Human turn: batch advance to next stop
      (let [result (advance-with-stops app-db f6?)
            result-db (:game/db (:app-db result))
            new-active-id (queries/get-active-player-id result-db)
            crossed-turn? (not= active-player-id new-active-id)]
        (cond
          ;; Pending selection (e.g., cleanup discard)
          (:game/pending-selection (:app-db result))
          result

          ;; Turn boundary crossed — continue (next yield re-reads active player)
          crossed-turn?
          (let [human-pid (queries/get-human-player-id result-db)
                landed-on-human? (= new-active-id human-pid)]
            (if (and landed-on-human? f6?)
              ;; Crossed to human turn with F6 — clear auto-mode, keep yielding to stop
              {:app-db (update (:app-db result) :game/db priority/clear-auto-mode)
               :continue-yield? true}
              ;; Crossed to other player — continue cascade
              {:app-db (:app-db result)
               :continue-yield? true}))

          ;; F6 within same turn — clear auto-mode (stop hit)
          f6?
          (update result :app-db
                  (fn [adb] (update adb :game/db priority/clear-auto-mode)))

          ;; Normal: same player's turn, stop here
          :else
          result)))))


(defn negotiate-priority
  "Handle priority passing between players.
   Returns {:app-db updated-app-db, :all-passed? bool}.

   When all-passed? is true, passes are reset in the returned app-db.
   When all-passed? is false, priority has been transferred to the opponent.

   Bot auto-passing rules:
   - Bot players auto-pass when a human yields AND bot protocol returns :pass
     (consults bot-priority-decision to allow reactive archetypes to hold priority)
   - When a bot yields, the human is NEVER auto-passed
     (human must get priority to respond to bot actions)
   - During a bot's turn with empty stack, human opponent auto-passes too
     (human doesn't need priority on empty stack during bot's turn)
   - During a bot's turn with non-empty stack, human keeps priority to respond
   - In auto-mode (:resolving, :f6), both players auto-pass regardless

   Pure function: (app-db) -> {:app-db app-db', :all-passed? bool}"
  [app-db]
  (let [game-db (:game/db app-db)
        auto-mode (priority/get-auto-mode game-db)
        holder-eid (priority/get-priority-holder-eid game-db)
        ;; Step 1: current player passes
        gdb (priority/yield-priority game-db holder-eid)
        active-player-id (queries/get-active-player-id gdb)
        active-eid (queries/get-player-eid gdb active-player-id)
        opponent-player-id (queries/get-other-player-id gdb active-player-id)
        ;; Step 2: determine if opponent should auto-pass
        should-auto-pass-opponent?
        (when opponent-player-id
          (let [opp-eid (queries/get-player-eid gdb opponent-player-id)]
            (or auto-mode
                (and (not (player-is-bot? gdb holder-eid))
                     (or (and (player-is-bot? gdb opp-eid)
                              (bot-would-pass? gdb opponent-player-id))
                         (and (player-is-bot? gdb active-eid)
                              (queries/stack-empty? gdb))))
                (and (player-is-bot? gdb active-eid)
                     (queries/stack-empty? gdb)
                     (not (priority/check-stop gdb active-eid
                                               (:game/phase (queries/get-game-state gdb))))))))
        gdb (if should-auto-pass-opponent?
              (let [opp-eid (queries/get-player-eid gdb opponent-player-id)]
                (-> gdb
                    (priority/yield-priority active-eid)
                    (priority/yield-priority opp-eid)))
              gdb)
        all-passed (or (not opponent-player-id)
                       (priority/both-passed? gdb))]
    (if all-passed
      {:app-db (assoc app-db :game/db (priority/reset-passes gdb))
       :all-passed? true}
      {:app-db (assoc app-db :game/db (priority/transfer-priority gdb holder-eid))
       :all-passed? false})))


(defn yield-impl
  "Core priority passing logic. Pure function on app-db.
   Returns map with:
     :app-db          — updated app-db (always present)
     :continue-yield? — true if ::yield should re-dispatch

   1. Negotiate priority (pass for current player, auto-pass opponent if applicable)
   2. If all passed and stack not empty → resolve top item
   3. If all passed and stack empty → advance phases
   4. If not all passed → priority transferred, wait"
  [app-db]
  (let [result (negotiate-priority app-db)]
    (if (:all-passed? result)
      (let [negotiated-app-db (:app-db result)
            game-db (:game/db negotiated-app-db)]
        (if (not (queries/stack-empty? game-db))
          (yield-resolve-stack negotiated-app-db)
          (yield-advance-phase negotiated-app-db)))
      {:app-db (:app-db result)})))


(def ^:private max-yield-steps
  "Safety limit: maximum number of yield steps per auto-mode cascade.
   Prevents infinite loops from bugs in phase/priority logic."
  200)


(rf/reg-event-fx
  ::yield
  (fn [{:keys [db]} _]
    (if (:game/pending-selection db)
      {:db db}
      (let [result (yield-impl db)
            auto-mode (priority/get-auto-mode (:game/db (:app-db result)))
            step-count (or (:yield/step-count (:app-db result)) 0)]
        (cond
          ;; Safety limit reached — stop cascade, clear auto-mode
          (and auto-mode (:continue-yield? result) (>= step-count max-yield-steps))
          {:db (-> (:app-db result)
                   (update :game/db priority/clear-auto-mode)
                   (dissoc :yield/step-count))}

          ;; Continue yielding with auto-mode — animated cascade via dispatch-later
          (and auto-mode (:continue-yield? result))
          {:db (update (:app-db result) :yield/step-count (fnil inc 0))
           :fx [[:dispatch-later {:ms 100 :dispatch [::yield]}]]}

          ;; Continue yielding without auto-mode — immediate dispatch
          (:continue-yield? result)
          {:db (:app-db result)
           :fx [[:dispatch [::yield]]]}

          ;; Auto-mode active but paused (bot turn at priority phase) — keep
          ;; step counter so cascade resumes after bot interceptor fires
          auto-mode
          {:db (update (:app-db result) :yield/step-count (fnil inc 0))}

          ;; Done — clear step counter
          :else
          {:db (dissoc (:app-db result) :yield/step-count)})))))


(rf/reg-event-fx
  ::yield-all
  (fn [{:keys [db]} _]
    (if (:game/pending-selection db)
      {:db db}
      (let [game-db (:game/db db)
            mode (if (queries/stack-empty? game-db) :f6 :resolving)]
        {:db (-> db
                 (assoc :game/db (priority/set-auto-mode game-db mode))
                 (assoc :yield/step-count 0))
         :fx [[:dispatch [::yield]]]}))))


(defn- resolve-one-and-stop
  "Resolve the top stack item with temporary :resolving auto-mode (so opponent
   auto-passes), then clear auto-mode. Returns updated app-db. Used by
   cast-and-yield and the :resolve-one-and-stop continuation."
  [app-db]
  (if (or (:game/pending-selection app-db)
          (queries/stack-empty? (:game/db app-db)))
    app-db
    (let [adb (update app-db :game/db priority/set-auto-mode :resolving)
          result (yield-impl adb)]
      (update (:app-db result) :game/db priority/clear-auto-mode))))


;; Register continuation: :resolve-one-and-stop
;; Called by confirm-selection-impl when selection completes with this continuation.
(defmethod sel-core/apply-continuation :resolve-one-and-stop
  [_ app-db]
  (resolve-one-and-stop app-db))


(rf/reg-event-fx
  ::cast-and-yield
  (fn [{:keys [db]} _]
    (let [new-db (cast-spell-handler db)]
      (cond
        ;; Pre-cast selection needed (mana allocation, targeting, etc.)
        ;; Set on-complete continuation so auto-resolve happens after selection completes
        ;; Don't overwrite if selection already has its own continuation (e.g., spell-mode)
        (:game/pending-selection new-db)
        (if (:selection/on-complete (:game/pending-selection new-db))
          {:db new-db}
          {:db (assoc-in new-db [:game/pending-selection :selection/on-complete]
                         {:continuation/type :resolve-one-and-stop})})

        ;; Mode selection needed — no continuation (mode selection is a choice, not a cost)
        (:game/pending-mode-selection new-db)
        {:db new-db}

        ;; Cast failed or nothing on stack
        (not (seq (queries/get-all-stack-items (:game/db new-db))))
        {:db new-db}

        ;; Cast succeeded, stack has items — resolve just the top item, then stop.
        :else
        {:db (resolve-one-and-stop new-db)}))))


(rf/reg-event-db
  ::cast-and-yield-resolve
  (fn [db _]
    (resolve-one-and-stop db)))


;; === Mode Selection System ===
;; For spells with multiple valid casting modes from the same zone

(defn select-casting-mode-handler
  "Handle select-casting-mode event: cast spell with the chosen mode.
   Clears pending mode selection and initiates cast with X cost/targeting checks.
   Pure function: (app-db, mode) -> app-db"
  [app-db mode]
  (let [pending (:game/pending-mode-selection app-db)
        object-id (:object-id pending)]
    (if (and pending object-id mode)
      ;; Use initiate-cast-with-mode to handle X costs, targeting, and casting
      (-> (initiate-cast-with-mode app-db (queries/get-human-player-id (:game/db app-db)) object-id mode nil)
          (dissoc :game/pending-mode-selection))
      app-db)))


(rf/reg-event-db
  ::select-casting-mode
  (fn [db [_ mode]]
    (select-casting-mode-handler db mode)))


(defn cancel-mode-selection-handler
  "Handle cancel-mode-selection event: clear pending mode selection state.
   Pure function: (app-db) -> app-db"
  [app-db]
  (dissoc app-db :game/pending-mode-selection))


(rf/reg-event-db
  ::cancel-mode-selection
  (fn [db _]
    (cancel-mode-selection-handler db)))
