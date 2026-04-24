(ns fizzle.engine.resolution-integration-test
  "End-to-end integration tests for the resolve-stack-item → reduce-effects → SBA chain.

   Tests exercise the full pipeline via db-effect/register! + rf/dispatch-sync:
     dispatch-sync [::resolution/resolve-top]
       → resolve-one-item (event handler)
         → engine-resolution/resolve-stack-item (multimethod)
           → effects/reduce-effects
         → stack/remove-stack-item
       → :db effect handler (db_effect.cljs)
         → sba/check-and-execute-sbas  ← SBAs fire here

   db-effect/register! is called at namespace load to install the custom :db
   handler that triggers SBAs. This is the key difference from unit tests that
   call resolve-one-item directly.

   Anti-patterns avoided:
   - NO d/conn-from-db in new tests (use d/db-with)
   - NO some?/contains? assertions (exact = always)
   - NO hand-built stack items (use production builders via th/ helpers)
   - NO direct calls to resolve-stack-item (use dispatch-sync)"
  (:require
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.state-based :as sba]
    [fizzle.events.db-effect :as db-effect]
    [fizzle.events.resolution :as resolution]
    [fizzle.history.core :as history]
    [fizzle.history.interceptor :as interceptor]
    [fizzle.test-helpers :as th]
    [re-frame.core :as rf]
    [re-frame.db :as rf-db]))


;; Install custom :db effect handler so SBAs fire on every db mutation.
;; interceptor/register! so dispatch-sync creates history entries.
(db-effect/register!)
(interceptor/register!)


;; === Fixtures ===

(use-fixtures :each
  {:before (fn [] (reset! rf-db/app-db {}))
   :after  (fn [] (reset! rf-db/app-db {}))})


;; === Helpers ===

(defn- make-app-db
  "Create app-db with history initialized. Accepts a game-db directly."
  [game-db]
  (merge (history/init-history) {:game/db game-db}))


(defn- dispatch-resolve-top
  "Set rf-db/app-db to app-db, dispatch ::resolve-top synchronously, return result."
  [app-db]
  (reset! rf-db/app-db app-db)
  (rf/dispatch-sync [::resolution/resolve-top])
  @rf-db/app-db)


;; =====================================================
;; A. Baseline: Simple spell → effects → no SBA
;; =====================================================

(deftest test-simple-spell-resolve-via-cast-and-resolve
  (testing "Dark Ritual resolve via cast-and-resolve: effect adds mana, spell moves to graveyard, no SBA fires"
    (let [game-db (th/create-test-db {:mana {:black 3}})
          [game-db obj-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          ;; Cast only — put on stack, don't resolve yet
          game-db-cast (-> game-db
                           (th/cast-and-resolve :player-1 obj-id))
          ;; cast-and-resolve already resolves in one step — verify no SBA fired
          ;; (player-1 starts at 20 life, no conditions to trigger SBAs)
          result-db game-db-cast]
      ;; No loss condition — SBAs found nothing to act on
      (is (nil? (:game/loss-condition (q/get-game-state result-db)))
          "No SBA should have fired for a basic mana-adding spell")
      (is (= 5 (:black (q/get-mana-pool result-db :player-1)))
          "Mana effect should have executed"))))


(deftest test-dispatch-sync-resolve-top-dark-ritual
  (testing "dispatch-sync [::resolve-top] with Dark Ritual: effect + SBA chokepoint path"
    (let [game-db (th/create-test-db {:mana {:black 3}})
          game-db (th/add-opponent game-db)
          [game-db obj-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          ;; Use rules/cast-spell to put on stack WITHOUT resolving
          game-db-cast (rules/cast-spell game-db :player-1 obj-id)
          app-db (make-app-db game-db-cast)
          ;; Dispatch resolve-top — first item is the storm meta, then the spell
          app-db-1 (dispatch-resolve-top app-db)
          app-db-2 (dispatch-resolve-top app-db-1)
          result-db (:game/db app-db-2)]
      ;; Spell in graveyard
      (is (= :graveyard (:object/zone (q/get-object result-db obj-id)))
          "Dark Ritual should be in graveyard after dispatch-sync resolve")
      ;; Mana added (3 start - 1 cost + 3 effect = 5)
      (is (= 5 (:black (q/get-mana-pool result-db :player-1)))
          "Mana effect executed through dispatch-sync path")
      ;; No SBA fired
      (is (nil? (:game/loss-condition (q/get-game-state result-db)))
          "No SBA should have fired"))))


;; =====================================================
;; B. Critical: SBA fires on every db mutation via db-effect chokepoint
;; =====================================================

(deftest test-lethal-damage-sba-fires-during-spell-resolve
  (testing "CRITICAL: Pre-existing lethal damage triggers SBA when any spell resolves (db mutation)"
    ;; Strategy: put lethal damage on a creature, then cast Dark Ritual.
    ;; Dark Ritual's resolve mutates game-db via the :db effect handler,
    ;; which calls check-and-execute-sbas — the lethal-damage SBA fires.
    (let [game-db (th/create-test-db {:mana {:black 3}})
          game-db (th/add-opponent game-db)
          ;; 2/2 creature with 2 damage — already at lethal threshold
          [game-db creature-id] (th/add-test-creature game-db :player-2 2 2)
          creature-eid (q/get-object-eid game-db creature-id)
          game-db (d/db-with game-db [[:db/add creature-eid :object/damage-marked 2]])
          ;; Cast Dark Ritual (no targets — simple spell)
          [game-db ritual-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          game-db-cast (rules/cast-spell game-db :player-1 ritual-id)
          app-db (make-app-db game-db-cast)
          ;; Resolve: storm meta first, then spell
          app-db-1 (dispatch-resolve-top app-db)
          app-db-2 (dispatch-resolve-top app-db-1)
          result-db (:game/db app-db-2)]
      ;; THE critical assertion: lethal-damage SBA should have fired via db-effect handler
      (is (= :graveyard (:object/zone (q/get-object result-db creature-id)))
          "CRITICAL: Lethal-damage SBA fires via db-effect chokepoint on any game-db mutation"))))


(deftest test-zero-toughness-sba-fires-during-spell-resolve
  (testing "0-toughness creature triggers SBA when spell resolves via db-effect"
    (let [game-db (th/create-test-db {:mana {:black 3}})
          game-db (th/add-opponent game-db)
          ;; 1/0 creature — SBA fires on first game-db mutation
          [game-db creature-id] (th/add-test-creature game-db :player-2 1 0)
          [game-db ritual-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          game-db-cast (rules/cast-spell game-db :player-1 ritual-id)
          app-db (make-app-db game-db-cast)
          app-db-1 (dispatch-resolve-top app-db)
          app-db-2 (dispatch-resolve-top app-db-1)
          result-db (:game/db app-db-2)]
      ;; zero-toughness SBA fires via db-effect
      (is (= :graveyard (:object/zone (q/get-object result-db creature-id)))
          "0-toughness SBA fires via db-effect during spell resolution"))))


(deftest test-sba-not-fire-when-no-condition-met
  (testing "No SBA fires when conditions are not met after spell resolve"
    (let [game-db (th/create-test-db {:mana {:black 3}})
          game-db (th/add-opponent game-db)
          ;; Healthy 2/2 creature — no SBA conditions
          [game-db creature-id] (th/add-test-creature game-db :player-2 2 2)
          [game-db ritual-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          game-db-cast (rules/cast-spell game-db :player-1 ritual-id)
          app-db (make-app-db game-db-cast)
          app-db-1 (dispatch-resolve-top app-db)
          app-db-2 (dispatch-resolve-top app-db-1)
          result-db (:game/db app-db-2)]
      ;; Creature should still be on battlefield — no lethal damage
      (is (= :battlefield (:object/zone (q/get-object result-db creature-id)))
          "Healthy 2/2 creature should stay on battlefield — no SBA condition met"))))


(deftest test-lethal-damage-non-dispatch-path-verify
  (testing "Verify lethal-damage SBA is detected by check-and-execute-sbas (unit baseline)"
    ;; This verifies the SBA detection logic itself so we can trust the dispatch-sync tests
    (let [game-db (th/create-test-db)
          game-db (th/add-opponent game-db)
          [game-db creature-id] (th/add-test-creature game-db :player-2 2 2)
          creature-eid (q/get-object-eid game-db creature-id)
          game-db (d/db-with game-db [[:db/add creature-eid :object/damage-marked 2]])
          result-db (sba/check-and-execute-sbas game-db)]
      (is (= :graveyard (:object/zone (q/get-object result-db creature-id)))
          "check-and-execute-sbas directly moves lethally-damaged creature to graveyard"))))


;; =====================================================
;; C. Spell reduces life to zero — life-zero SBA fires
;; =====================================================

(deftest test-life-loss-effect-triggers-life-zero-sba
  (testing "Lightning Bolt deal-damage to opponent at 3 life triggers life-zero SBA via dispatch-sync"
    (let [game-db (th/create-test-db {:mana {:red 1}})
          game-db (th/add-opponent game-db)
          ;; Set opponent life to 3 (Lightning Bolt deals exactly 3)
          opp-eid (q/get-player-eid game-db :player-2)
          game-db (d/db-with game-db [[:db/add opp-eid :player/life 3]])
          ;; Lightning Bolt targeting opponent player
          [game-db bolt-id] (th/add-card-to-zone game-db :lightning-bolt :hand :player-1)
          game-db-cast (th/cast-with-target game-db :player-1 bolt-id :player-2)
          app-db (make-app-db game-db-cast)
          ;; Resolve storm meta then spell
          app-db-1 (dispatch-resolve-top app-db)
          app-db-2 (dispatch-resolve-top app-db-1)
          result-db (:game/db app-db-2)]
      ;; Opponent should be at 0 life
      (is (= 0 (q/get-life-total result-db :player-2))
          "Opponent should be at 0 life after 3 damage")
      ;; life-zero SBA should have fired via db-effect
      (is (= :life-zero (:game/loss-condition (q/get-game-state result-db)))
          "life-zero SBA fires automatically when opponent reaches 0 life via db-effect"))))


;; =====================================================
;; D. Multi-effect spell — all effects execute
;; =====================================================

(deftest test-multi-effect-spell-executes-all-effects
  (testing "Cabal Ritual (add-mana effect) resolves and storm increments"
    (let [game-db (th/create-test-db {:mana {:black 2}})
          [game-db obj-id] (th/add-card-to-zone game-db :cabal-ritual :hand :player-1)
          game-db-resolved (th/cast-and-resolve game-db :player-1 obj-id)
          mana-pool (q/get-mana-pool game-db-resolved :player-1)]
      ;; Cabal Ritual costs BB, adds BBB (no threshold). Started: 2B, paid 2B, added 3B = 3B
      (is (= 3 (:black mana-pool))
          "Cabal Ritual effect: adds 3 black mana (2 start - 2 cost + 3 effect = 3)")
      ;; Storm count incremented
      (is (= 1 (q/get-storm-count game-db-resolved :player-1))
          "Storm count should be 1 after casting Cabal Ritual")
      ;; Spell in graveyard
      (is (= :graveyard (:object/zone (q/get-object game-db-resolved obj-id)))
          "Cabal Ritual should be in graveyard"))))


(deftest test-dark-ritual-storm-count-increments
  (testing "Dark Ritual resolves and increments storm count"
    (let [game-db (th/create-test-db {:mana {:black 3}})
          [game-db obj-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          _ (is (= 0 (q/get-storm-count game-db :player-1))
                "Precondition: storm count is 0")
          game-db-resolved (th/cast-and-resolve game-db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count game-db-resolved :player-1))
          "Storm count should be 1 after casting Dark Ritual"))))


;; =====================================================
;; E. Conditional effect (threshold)
;; =====================================================

(deftest test-cabal-ritual-without-threshold-adds-3-black
  (testing "Cabal Ritual without threshold: adds BBB (condition not met)"
    (let [game-db (th/create-test-db {:mana {:black 2}})
          ;; No threshold: graveyard has fewer than 7 cards
          [game-db obj-id] (th/add-card-to-zone game-db :cabal-ritual :hand :player-1)
          game-db-resolved (th/cast-and-resolve game-db :player-1 obj-id)
          mana-pool (q/get-mana-pool game-db-resolved :player-1)]
      ;; 2 start - 2 cost + 3 (no threshold) = 3
      (is (= 3 (:black mana-pool))
          "Without threshold: Cabal Ritual adds 3 black mana"))))


(deftest test-cabal-ritual-with-threshold-adds-5-black
  (testing "Cabal Ritual with threshold (7+ cards in graveyard): adds BBBBB"
    (let [game-db (th/create-test-db {:mana {:black 2}})
          ;; Add 7 cards to graveyard to enable threshold
          [game-db _] (th/add-cards-to-graveyard game-db
                                                 [:dark-ritual :dark-ritual :dark-ritual
                                                  :dark-ritual :dark-ritual :dark-ritual
                                                  :dark-ritual]
                                                 :player-1)
          [game-db obj-id] (th/add-card-to-zone game-db :cabal-ritual :hand :player-1)
          game-db-resolved (th/cast-and-resolve game-db :player-1 obj-id)
          mana-pool (q/get-mana-pool game-db-resolved :player-1)]
      ;; 2 start - 2 cost + 5 (threshold) = 5
      (is (= 5 (:black mana-pool))
          "With threshold: Cabal Ritual adds 5 black mana"))))


;; =====================================================
;; F. Cascading SBAs via dispatch-sync
;; =====================================================

(deftest test-lethal-damage-non-token-creature-goes-to-graveyard
  (testing "Non-token creature with lethal damage moves to graveyard (not retracted) via dispatch-sync"
    ;; Cast Dark Ritual — db mutation triggers SBA, moves creature to graveyard
    (let [game-db (th/create-test-db {:mana {:black 3}})
          game-db (th/add-opponent game-db)
          [game-db creature-id] (th/add-test-creature game-db :player-2 2 2)
          creature-eid (q/get-object-eid game-db creature-id)
          ;; Lethal damage already on creature
          game-db (d/db-with game-db [[:db/add creature-eid :object/damage-marked 2]])
          [game-db ritual-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          game-db-cast (rules/cast-spell game-db :player-1 ritual-id)
          app-db (make-app-db game-db-cast)
          app-db-1 (dispatch-resolve-top app-db)
          app-db-2 (dispatch-resolve-top app-db-1)
          result-db (:game/db app-db-2)]
      ;; Non-token: moved to graveyard (entity still exists but zone changed)
      (is (= :graveyard (:object/zone (q/get-object result-db creature-id)))
          "Non-token creature with lethal damage should be in graveyard"))))


(deftest test-token-lethal-damage-cascades-to-cleanup-via-dispatch-sync
  (testing "Token with lethal damage: lethal-damage SBA → graveyard → token-cleanup cascade via dispatch-sync"
    (let [game-db (th/create-test-db {:mana {:black 3}})
          game-db (th/add-opponent game-db)
          ;; Create a 2/2 token with 2 damage marked (lethal threshold)
          player-eid (q/get-player-eid game-db :player-2)
          card-id (keyword (str "test-token-" (random-uuid)))
          game-db (d/db-with game-db [{:card/id card-id
                                       :card/name "Test Token"
                                       :card/types #{:creature}
                                       :card/power 2
                                       :card/toughness 2}])
          card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] game-db card-id)
          token-id (random-uuid)
          game-db (d/db-with game-db [{:object/id token-id
                                       :object/card card-eid
                                       :object/zone :battlefield
                                       :object/owner player-eid
                                       :object/controller player-eid
                                       :object/is-token true
                                       :object/tapped false
                                       :object/summoning-sick false
                                       :object/damage-marked 2
                                       :object/power 2
                                       :object/toughness 2}])
          ;; Cast Dark Ritual — db mutation triggers SBA cascade
          [game-db ritual-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          game-db-cast (rules/cast-spell game-db :player-1 ritual-id)
          app-db (make-app-db game-db-cast)
          ;; After two resolve-top calls: SBAs should cascade via db-effect
          app-db-1 (dispatch-resolve-top app-db)
          app-db-2 (dispatch-resolve-top app-db-1)
          result-db (:game/db app-db-2)]
      ;; Token should be fully retracted: lethal-damage SBA moves to graveyard,
      ;; token-cleanup SBA retracts entity
      (is (nil? (q/get-object-eid result-db token-id))
          "Token should be fully retracted after cascading lethal-damage → token-cleanup SBAs"))))


;; =====================================================
;; G. Stack clears completely after resolution
;; =====================================================

(deftest test-stack-empty-after-dark-ritual-resolve
  (testing "After Dark Ritual resolves, stack is completely empty"
    (let [game-db (th/create-test-db {:mana {:black 3}})
          game-db (th/add-opponent game-db)
          [game-db obj-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-1)
          game-db-cast (rules/cast-spell game-db :player-1 obj-id)
          _ (is (pos? (count (q/get-all-stack-items game-db-cast)))
                "Precondition: stack has items after cast")
          app-db (make-app-db game-db-cast)
          ;; Resolve all items
          app-db-1 (dispatch-resolve-top app-db)
          app-db-2 (dispatch-resolve-top app-db-1)
          result-db (:game/db app-db-2)]
      (is (empty? (q/get-all-stack-items result-db))
          "Stack should be completely empty after all items resolve"))))


;; =====================================================
;; H. Interactive effect: pause → selection → resume
;; =====================================================

(deftest test-interactive-effect-pause-and-resume
  (testing "Duress resolves with interactive pause: hand-reveal-discard selection returned, then completed"
    ;; Duress resolves → discard-from-revealed-hand effect triggers selection pause
    ;; → confirm-selection discards the chosen card
    (let [game-db (th/create-test-db {:mana {:black 1}})
          game-db (th/add-opponent game-db)
          ;; Add Dark Ritual to opponent's hand — valid Duress target (instant, noncreature, nonland)
          [game-db target-id] (th/add-card-to-zone game-db :dark-ritual :hand :player-2)
          ;; Cast Duress targeting opponent
          [game-db duress-id] (th/add-card-to-zone game-db :duress :hand :player-1)
          game-db-cast (th/cast-with-target game-db :player-1 duress-id :player-2)
          ;; Resolve — should pause for hand-reveal-discard selection
          result-1 (th/resolve-top game-db-cast)
          sel (:selection result-1)
          game-db-paused (:db result-1)]
      ;; Verify the interactive pause happened
      (is (= :revealed-hand-discard (:selection/domain sel))
          "Selection type should be :hand-reveal-discard")
      ;; Confirm selection — choose Dark Ritual to discard
      (let [result-2 (th/confirm-selection game-db-paused sel #{target-id})
            game-db-final (:db result-2)]
        ;; Duress should be in graveyard (resolved)
        (is (= :graveyard (:object/zone (q/get-object game-db-final duress-id)))
            "Duress should be in graveyard after resolving")
        ;; Dark Ritual should be in opponent's graveyard (discarded)
        (is (= :graveyard (:object/zone (q/get-object game-db-final target-id)))
            "Dark Ritual should be discarded to opponent's graveyard")
        ;; No loss condition — SBAs found nothing to act on
        (is (nil? (:game/loss-condition (q/get-game-state game-db-final)))
            "No SBA should have fired during Duress resolution")))))


(deftest test-resolve-top-no-op-on-empty-stack
  (testing "dispatch-sync resolve-top on empty stack is a no-op"
    (let [game-db (th/create-test-db)
          game-db (th/add-opponent game-db)
          app-db (make-app-db game-db)
          _ (is (empty? (q/get-all-stack-items game-db))
                "Precondition: stack is empty")
          result (dispatch-resolve-top app-db)
          result-db (:game/db result)]
      (is (empty? (q/get-all-stack-items result-db))
          "Stack should still be empty after no-op resolve")
      (is (nil? (:game/loss-condition (q/get-game-state result-db)))
          "No SBA should fire on empty stack resolve"))))
