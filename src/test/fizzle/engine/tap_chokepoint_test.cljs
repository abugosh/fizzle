(ns fizzle.engine.tap-chokepoint-test
  "Tests for the tap chokepoint migration.

   Verifies that :permanent-tapped triggers fire from all four game-action tap paths:
   1. costs/pay-cost :tap  — tap-as-cost (mana abilities, future non-mana abilities)
   2. combat/tap-and-mark-attackers — attack declaration taps
   3. effects :tap-all — mass tap effect (Twiddle-style)
   4. events/selection/library execute-tutor-selection :enters-tapped path — fetchland

   Also verifies:
   - No double-fire: tapping via mana_activation fires exactly once
   - th/tap-permanent (test setup helper) does NOT fire :permanent-tapped

   Note: events/lands::tap-permanent was dead production code (no UI/views callers)
   and was removed (Option B of reviewer gap fix)."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.combat :as combat]
    [fizzle.engine.costs :as costs]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.engine.trigger-db :as trigger-db]
    [fizzle.events.resolution :as resolution]
    [fizzle.events.selection.library :as sel-library]
    [fizzle.test-helpers :as th]))


;; === Path 1: costs/pay-cost :tap fires :permanent-tapped ===

(deftest test-pay-cost-tap-dispatches-permanent-tapped
  (testing "costs/pay-cost :tap dispatches :permanent-tapped trigger"
    (let [db (th/create-test-db)
          ;; City of Brass has a :becomes-tapped trigger registered at object creation
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-1)
          ;; No triggers on stack before tapping
          _ (is (= 0 (count (q/get-all-stack-items db')))
                "Precondition: no stack items before tap")
          ;; Directly call pay-cost :tap (the chokepoint) bypassing mana_activation
          db-after-pay (costs/pay-cost db' obj-id {:tap true})]
      ;; After pay-cost :tap, the :permanent-tapped trigger should be on the stack
      (is (= 1 (count (q/get-all-stack-items db-after-pay)))
          "One :permanent-tapped trigger should be on the stack after pay-cost :tap"))))


(deftest test-pay-cost-tap-result-is-tapped
  (testing "costs/pay-cost :tap sets object tapped AND dispatches trigger"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-1)
          db-after-pay (costs/pay-cost db' obj-id {:tap true})]
      ;; Object should be tapped
      (is (true? (:object/tapped (q/get-object db-after-pay obj-id)))
          "Object should be tapped after pay-cost :tap")
      ;; Trigger should be on stack
      (is (= 1 (count (q/get-all-stack-items db-after-pay)))
          "Trigger should be on stack after pay-cost :tap"))))


;; === No double-fire via mana_activation path ===

(deftest test-mana-activation-no-double-fire
  (testing "Activating mana ability fires :permanent-tapped exactly once (no double-fire)"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-1)
          ;; Activate via mana_activation (which calls pay-all-costs → costs/pay-cost :tap)
          db-after-tap (engine-mana/activate-mana-ability db' :player-1 obj-id :black)]
      ;; Exactly 1 trigger on stack, not 2
      (is (= 1 (count (q/get-all-stack-items db-after-tap)))
          "Exactly one trigger on stack — costs.cljs fires it, mana_activation.cljs must NOT also fire it")
      ;; Resolve trigger — damage should be 1, not 2
      (let [db-resolved (:db (resolution/resolve-one-item db-after-tap))]
        (is (= 19 (q/get-life-total db-resolved :player-1))
            "Player loses exactly 1 life (not 2) — no double-fire")))))


;; === Path 2: combat/tap-and-mark-attackers fires :permanent-tapped ===

(deftest test-combat-tap-dispatches-permanent-tapped
  (testing "combat/tap-and-mark-attackers dispatches :permanent-tapped for each attacker"
    (let [db (th/create-test-db)
          ;; Add a creature to battlefield for player-1
          [db' obj-id] (th/add-test-creature db :player-1 2 2)
          ;; Register a manual :permanent-tapped trigger on the creature
          ;; (no real card uses this, but we need a trigger to verify dispatch)
          obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db' obj-id)
          player-eid (q/get-player-eid db' :player-1)
          trigger-tx (trigger-db/create-trigger-tx
                       {:trigger/event-type :permanent-tapped
                        :trigger/source {:db/id obj-eid}
                        :trigger/controller {:db/id player-eid}
                        :trigger/match {:event/object-id :self}
                        :trigger/uses-stack? true
                        :trigger/effects [{:effect/type :lose-life
                                           :effect/amount 1
                                           :effect/target :controller}]})
          db'' (d/db-with db' trigger-tx)
          ;; Tap via combat path
          db-after-attack (combat/tap-and-mark-attackers db'' [obj-id])]
      ;; Trigger should be on stack
      (is (= 1 (count (q/get-all-stack-items db-after-attack)))
          "One :permanent-tapped trigger should be on stack after combat tap"))))


;; === Path 3: :tap-all effect fires :permanent-tapped ===

(deftest test-tap-all-effect-dispatches-permanent-tapped
  (testing ":tap-all effect dispatches :permanent-tapped for each tapped permanent"
    (let [db (th/create-test-db)
          ;; Add a land (City of Brass) to battlefield — it has :becomes-tapped trigger
          [db' _obj-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-1)
          ;; Execute :tap-all effect targeting player-1's lands
          db-after-tap-all (effects/execute-effect db' :player-1
                                                   {:effect/type :tap-all
                                                    :effect/target :player-1
                                                    :effect/permanent-type :land})]
      ;; CoB's :becomes-tapped trigger should fire via :permanent-tapped dispatch
      (is (= 1 (count (q/get-all-stack-items db-after-tap-all)))
          "One :permanent-tapped trigger on stack after :tap-all effect"))))


;; === Path 4: events/lands — dead code removed ===
;; The ::tap-permanent re-frame event and lands/tap-permanent function were dead
;; production code with no UI/views callers. They bypassed the :permanent-tapped
;; dispatch chokepoint and have been removed (Option B).
;;
;; th/tap-permanent in test_helpers now uses inline d/db-with directly.
;; It is a TEST SETUP helper — not a game-action tap — and must NOT fire
;; :permanent-tapped triggers (correct behavior for initializing test state).

(deftest test-th-tap-permanent-sets-tapped-without-trigger
  (testing "th/tap-permanent sets :object/tapped true without dispatching :permanent-tapped"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :city-of-brass :battlefield :player-1)
          _ (is (= 0 (count (q/get-all-stack-items db')))
                "Precondition: no stack items before test-helper tap")
          db-after (th/tap-permanent db' obj-id)]
      ;; th/tap-permanent is test setup — should NOT fire :permanent-tapped trigger
      (is (true? (:object/tapped (q/get-object db-after obj-id)))
          "Object should be tapped after th/tap-permanent")
      (is (= 0 (count (q/get-all-stack-items db-after)))
          "th/tap-permanent must NOT fire :permanent-tapped trigger (test setup, not game action)"))))


;; === Path 4: execute-tutor-selection enters-tapped path fires :permanent-tapped ===

(deftest test-fetchland-enters-tapped-dispatches-permanent-tapped
  (testing "execute-tutor-selection with enters-tapped dispatches :permanent-tapped trigger"
    (let [db (th/create-test-db)
          ;; Move City of Brass to library — it has a :becomes-tapped trigger registered at creation
          [db' cob-id] (th/add-card-to-zone db :city-of-brass :library :player-1)
          _ (is (= 0 (count (q/get-all-stack-items db')))
                "Precondition: no stack items before tutor resolution")
          ;; Construct a tutor selection with enters-tapped true
          ;; This simulates a fetchland or similar effect that puts a land on battlefield tapped
          selection {:selection/selected #{cob-id}
                     :selection/target-zone :battlefield
                     :selection/player-id :player-1
                     :selection/shuffle? false
                     :selection/enters-tapped true}
          db-after (sel-library/execute-tutor-selection db' selection)]
      ;; City of Brass should now be on the battlefield
      (is (= :battlefield (th/get-object-zone db-after cob-id))
          "CoB should be on the battlefield after tutor resolution")
      ;; CoB should be tapped (because enters-tapped was true)
      (is (true? (:object/tapped (q/get-object db-after cob-id)))
          "CoB should be tapped because enters-tapped was true")
      ;; CoB's :becomes-tapped trigger should have fired via :permanent-tapped dispatch
      (is (= 1 (count (q/get-all-stack-items db-after)))
          "CoB :becomes-tapped trigger should be on stack — :permanent-tapped dispatch must fire when entering tapped"))))
