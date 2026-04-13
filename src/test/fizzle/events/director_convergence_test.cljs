(ns fizzle.events.director-convergence-test
  "Convergence tests proving the director/bot casting path and the re-frame
   dispatch path produce equivalent game state for key interactions.

   Known acceptable divergence:
   - Human path writes :history/main entries; bot path does not.
   - Field-level assertions only — never (= human-db bot-db) full equality.

   Tests:
   1. director-bot-path-converges-with-human-path-for-lightning-bolt
      (storm count + graveyard count + stack empty match; history excluded)
   2. sba-triggers-bot-loss-when-bot-life-reaches-zero
      (SBAs apply to bot-controlled state after bolt reduces life to 0)
   3. cob-trigger-fires-when-mana-activation-taps-cob
      (bot-sourced mana-activation/activate-mana-ability fires :permanent-tapped
       trigger → CoB trigger resolves → controller loses 1 life)
   4. zone-change-dispatch-fires-on-bot-cast-and-resolve
      (bot-caused zone transitions: bolt moves to graveyard correctly)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.lands.city-of-brass :as city-of-brass]
    [fizzle.cards.red.lightning-bolt :as lightning-bolt]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana-activation :as mana-activation]
    [fizzle.engine.rules :as rules]
    [fizzle.events.casting :as casting]
    [fizzle.events.db-effect :as db-effect]
    [fizzle.events.director :as director]
    [fizzle.events.resolution :as resolution]
    [fizzle.history.core :as history]
    [fizzle.history.interceptor :as interceptor]
    [fizzle.test-helpers :as th]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Register history interceptor + SBA handler for human-path dispatch-sync tests.
;; Both are idempotent — safe to call multiple times across test files.
(interceptor/register!)
(db-effect/register!)


;; ============================================================
;; Shared setup helpers
;; ============================================================

(defn- dispatch-event
  "Dispatch a re-frame event synchronously, return resulting app-db.
   Resets rf-db/app-db before dispatch for test isolation."
  [app-db event]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync event)
  @rf-db/app-db)


(defn- setup-burn-bot-app-db
  "Create app-db with burn bot (player-2) having 1 Mountain on battlefield
   and 1 Lightning Bolt in hand. Active player = bot with priority at :main1.
   Returns app-db."
  []
  (let [db (th/create-test-db {:stops #{:main1 :main2}})
        conn (d/conn-from-db db)
        _ (d/transact! conn [lightning-bolt/card])
        db (th/add-opponent @conn {:bot-archetype :burn})
        [db _] (th/add-card-to-zone db :mountain :battlefield :player-2)
        [db _] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
        game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
        opp-eid (q/get-player-eid db :player-2)
        db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                          [:db/add game-eid :game/phase :main1]
                          [:db/add game-eid :game/priority opp-eid]])]
    (merge (history/init-history) {:game/db db})))


;; ============================================================
;; Test 1: Lightning Bolt convergence — human vs bot path
;; ============================================================

(deftest director-bot-path-converges-with-human-path-for-lightning-bolt
  (testing "Both paths produce matching field-level state after Lightning Bolt resolves"
    ;; --- Human path ---
    ;; Human (player-1) with 1 red mana casts Lightning Bolt targeting player-2 (opponent).
    ;; Cast via dispatch-sync + explicit target. Resolve via dispatch-sync.
    (let [app-db (th/create-game-scenario {:bot-archetype :goldfish
                                           :mana {:red 1}})
          game-db (:game/db app-db)
          [game-db' obj-id] (th/add-card-to-zone game-db :lightning-bolt :hand :player-1)
          app-db' (assoc app-db :game/db game-db')
          _ (is (rules/can-cast? game-db' :player-1 obj-id)
                "Precondition: human can cast Lightning Bolt with 1 red mana")
          ;; Cast with explicit target (no selection dialog — target pre-determined)
          after-cast (dispatch-event app-db'
                                     [::casting/cast-spell
                                      {:player-id :player-1
                                       :object-id obj-id
                                       :target :player-2}])
          ;; Resolve the bolt off the stack (SBAs run via db-effect interceptor)
          after-resolve (dispatch-event after-cast [::resolution/resolve-top])
          human-game-db (:game/db after-resolve)

          ;; --- Bot path ---
          ;; Burn bot (player-2) has Mountain + Bolt; yield-all? true so human auto-passes.
          ;; Bot casts bolt targeting player-1, both players pass, stack resolves inline.
          bot-app-db (setup-burn-bot-app-db)
          bot-result (director/run-to-decision bot-app-db {:yield-all? true})
          bot-game-db (:game/db (:app-db bot-result))]

      ;; Storm count: both should be 1 (for their respective player after casting)
      (is (= 1 (q/get-storm-count human-game-db :player-1))
          "Human path: storm count should be 1 after Lightning Bolt resolves")
      (is (= 1 (q/get-storm-count bot-game-db :player-2))
          "Bot path: storm count should be 1 after Lightning Bolt resolves")

      ;; Graveyard: bolt should be in graveyard of the caster after resolution
      (is (= 1 (count (q/get-objects-in-zone human-game-db :player-1 :graveyard)))
          "Human path: exactly 1 card (Lightning Bolt) in human graveyard after resolve")
      (is (= 1 (count (q/get-objects-in-zone bot-game-db :player-2 :graveyard)))
          "Bot path: exactly 1 card (Lightning Bolt) in bot graveyard after resolve")

      ;; Stack: empty after resolution on both paths
      (is (q/stack-empty? human-game-db)
          "Human path: stack should be empty after resolution")
      (is (q/stack-empty? bot-game-db)
          "Bot path: stack should be empty after resolution")

      ;; Life totals: bolt hit opponent in each path
      (is (= 17 (q/get-life-total human-game-db :player-2))
          "Human path: opponent (player-2) life should be 17 after Lightning Bolt")
      (is (= 17 (q/get-life-total bot-game-db :player-1))
          "Bot path: human (player-1) life should be 17 after bot's Lightning Bolt"))))


;; ============================================================
;; Test 2: Bot + SBA — bot kills human with bolt, SBA fires
;; ============================================================

(deftest sba-triggers-when-bolt-reduces-human-life-to-zero-via-bot
  (testing "SBAs apply to bot-driven state: life-zero fires and sets loss condition when bot bolt resolves"
    ;; Setup: human (player-1) starts at 3 life.
    ;; Burn bot (player-2) has Mountain + Lightning Bolt.
    ;; After director resolves: bolt deals 3 damage → human at 0 → :life-zero SBA fires.
    ;; Note: 3 damage = bolt (3 dmg) to target at 3 life → life goes to 0 → SBA.
    (let [db (th/create-test-db {:stops #{:main1 :main2} :life 3})
          conn (d/conn-from-db db)
          _ (d/transact! conn [lightning-bolt/card])
          db (th/add-opponent @conn {:bot-archetype :burn})
          [db _] (th/add-card-to-zone db :mountain :battlefield :player-2)
          [db _] (th/add-card-to-zone db :lightning-bolt :hand :player-2)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/phase :main1]
                            [:db/add game-eid :game/priority opp-eid]])
          app-db (merge (history/init-history) {:game/db db})
          result (director/run-to-decision app-db {:yield-all? true})
          result-db (:game/db (:app-db result))
          game-state (q/get-game-state result-db)]
      ;; Human life should be <= 0 after bolt resolves
      (is (<= (q/get-life-total result-db :player-1) 0)
          "Human life should be <= 0 after Lightning Bolt (3 dmg) resolves vs 3 life")
      ;; :game/loss-condition should be set — SBA converted life=0 to game-over state
      (is (= :life-zero (:game/loss-condition game-state))
          "Loss condition :life-zero should be set — SBA ran and executed after bolt resolved"))))


;; ============================================================
;; Test 3: CoB trigger fires when mana-activation taps CoB for bot
;; ============================================================

(deftest cob-trigger-fires-when-mana-activation-taps-cob
  (testing "City of Brass trigger fires and resolves when mana-activation taps CoB"
    ;; When CoB is tapped via mana-activation/activate-mana-ability:
    ;;   1. costs.cljs :permanent-tapped chokepoint fires
    ;;   2. trigger_dispatch.cljs places :permanent-tapped stack-item on stack
    ;;   3. director run-to-decision resolves it via step-resolve-stack
    ;;   4. deal-damage effect fires → bot life goes from 20 to 19
    ;;
    ;; We manually tap CoB (simulating bot-act behavior for the mana-activation step),
    ;; then run director/run-to-decision to resolve the pending trigger on the stack.
    ;; yield-all? true: human auto-passes so stack resolves inline.
    (let [db (th/create-test-db {:stops #{:main1 :main2}})
          conn (d/conn-from-db db)
          _ (d/transact! conn [city-of-brass/card])
          db (th/add-opponent @conn {:bot-archetype :burn})
          ;; Add CoB to bot's battlefield (trigger-db populated at creation via build-object-tx)
          [db cob-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-2)
          ;; Set active player = bot, phase = main1, priority = bot
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          opp-eid (q/get-player-eid db :player-2)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]
                            [:db/add game-eid :game/phase :main1]
                            [:db/add game-eid :game/priority opp-eid]])
          ;; Manually activate CoB for :any mana — this is the same call bot-act makes.
          ;; It fires the :permanent-tapped chokepoint → trigger goes on the stack.
          db-after-tap (mana-activation/activate-mana-ability db :player-2 cob-id :any)
          ;; Now the CoB trigger is a :permanent-tapped stack-item waiting to resolve.
          ;; Run director (yield-all? true) to resolve the trigger:
          ;;   - human auto-passes (yield-all?)
          ;;   - bot has no spells to cast, passes too
          ;;   - both passed + stack non-empty → step-resolve-stack fires
          ;;   - trigger resolves: bot loses 1 life
          app-db (merge (history/init-history) {:game/db db-after-tap})
          result (director/run-to-decision app-db {:yield-all? true})
          result-db (:game/db (:app-db result))
          bot-life (q/get-life-total result-db :player-2)]
      ;; Bot life should be 19 (CoB trigger resolved: deal 1 damage to controller)
      (is (= 19 bot-life)
          "Bot life should be 19 after CoB :permanent-tapped trigger resolves via director"))))


;; ============================================================
;; Test 4: Zone-change dispatch fires on bot cast + resolve
;; ============================================================

(deftest zone-change-dispatch-fires-on-bot-cast-and-resolve
  (testing "Bot-caused zone transitions are tracked correctly: graveyard has exactly 1 card after bolt resolves"
    ;; zone_change_dispatch.cljs wraps move-to-zone* — any zone transition (including
    ;; bot-caused) fires the wrapper. This test proves the bot path goes through the same
    ;; zone-change machinery as the human path.
    ;; After Lightning Bolt resolves: bolt moves hand→stack→graveyard (2 zone changes).
    ;; The graveyard count of 1 and stack empty state prove both moves completed correctly.
    (let [bot-app-db (setup-burn-bot-app-db)
          result (director/run-to-decision bot-app-db {:yield-all? true})
          result-db (:game/db (:app-db result))
          bot-gy (q/get-objects-in-zone result-db :player-2 :graveyard)]
      ;; Bot graveyard should have exactly 1 card (Lightning Bolt) after it resolves
      (is (= 1 (count bot-gy))
          "Exactly 1 card should be in bot's graveyard after Lightning Bolt resolves")
      ;; Verify it's the bolt (zone-change hand→stack→graveyard completed correctly)
      (is (= :lightning-bolt (get-in (first bot-gy) [:object/card :card/id]))
          "The card in bot's graveyard should be Lightning Bolt")
      ;; Stack should be empty (zone change from stack→gy completed via zone-change dispatch)
      (is (q/stack-empty? result-db)
          "Stack should be empty after bolt resolves — zone-change dispatch completed"))))
