(ns fizzle.cards.blue.deep-analysis-test
  "Tests for Deep Analysis card and flashback counter/fizzle behavior.

   Deep Analysis: 3U - Sorcery
   Target player draws two cards.
   Flashback - 1U, Pay 3 life.

   This tests:
   - Card definition (type, cost, alternate costs)
   - Player targeting (target player draws)
   - Cast-time targeting (new targeting system)
   - Flashback mode availability from graveyard
   - Counter/fizzle behavior (flashback spells exile when leaving stack)
   - Normal spells go to graveyard when countered"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.blue.deep-analysis :as deep-analysis]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.resolution :as resolution]
    [fizzle.events.selection.targeting :as sel-targeting]
    [fizzle.test-helpers :as th]))


;; === Card Definition Tests ===

(deftest deep-analysis-card-definition-test
  (testing "Deep Analysis identity and core fields"
    (is (= :deep-analysis (:card/id deep-analysis/card))
        "Card id should be :deep-analysis")
    (is (= "Deep Analysis" (:card/name deep-analysis/card))
        "Card name should be 'Deep Analysis'")
    (is (= {:colorless 3 :blue 1} (:card/mana-cost deep-analysis/card))
        "Deep Analysis should cost {3}{U}")
    (is (= #{:sorcery} (:card/types deep-analysis/card))
        "Deep Analysis should be a sorcery")
    (is (= 4 (:card/cmc deep-analysis/card))
        "Deep Analysis should have CMC 4")
    (is (= #{:blue} (:card/colors deep-analysis/card))
        "Deep Analysis should be blue")
    (is (= "Target player draws two cards. Flashback\u2014{1}{U}, Pay 3 life."
           (:card/text deep-analysis/card))
        "Card text should match"))

  (testing "Deep Analysis has flashback alternate cost"
    (let [flashback (first (:card/alternate-costs deep-analysis/card))]
      (is (= :flashback (:alternate/id flashback))
          "Alternate should be flashback")
      (is (= :graveyard (:alternate/zone flashback))
          "Flashback should be castable from graveyard")
      (is (= {:colorless 1 :blue 1} (:alternate/mana-cost flashback))
          "Flashback should cost {1}{U}")
      (is (= [{:cost/type :pay-life :cost/amount 3}] (:alternate/additional-costs flashback))
          "Flashback should require paying 3 life")
      (is (= :exile (:alternate/on-resolve flashback))
          "Flashback should exile on resolve")))

  (testing "Deep Analysis has draw effect"
    (let [effects (:card/effects deep-analysis/card)
          draw-effect (first effects)]
      (is (= 1 (count effects))
          "Deep Analysis should have 1 effect")
      (is (= :draw (:effect/type draw-effect))
          "Effect should be :draw")
      (is (= 2 (:effect/amount draw-effect))
          "Should draw 2 cards"))))


;; === Cast from hand tests ===

(deftest deep-analysis-cast-from-hand-test
  (testing "Deep Analysis castable from hand for 3U"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 3 :blue 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          primary-mode (first (filter #(= :primary (:mode/id %)) modes))]
      (is (rules/can-cast-mode? db :player-1 obj-id primary-mode)
          "Should be castable with {3}{U}"))))


(deftest deep-analysis-not-castable-from-hand-without-mana-test
  (testing "Deep Analysis not castable from hand without mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana"))))


;; === Flashback cast tests ===

(deftest deep-analysis-flashback-cast-test
  (testing "Deep Analysis castable from graveyard for 1U + 3 life"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :graveyard :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)]
      (is (= 1 (count modes))
          "Should only have flashback mode from graveyard")
      (is (= :flashback (:mode/id flashback-mode))
          "Mode should be flashback")
      (is (rules/can-cast-mode? db :player-1 obj-id flashback-mode)
          "Should be castable with {1}{U} and life"))))


(deftest deep-analysis-flashback-not-castable-without-life-test
  (testing "Deep Analysis flashback not castable without enough life"
    (let [db (th/create-test-db {:life 2})
          [db obj-id] (th/add-card-to-zone db :deep-analysis :graveyard :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)]
      (is (false? (rules/can-cast-mode? db :player-1 obj-id flashback-mode))
          "Should not be castable without enough life"))))


(deftest deep-analysis-flashback-increments-storm-test
  (testing "Flashback cast increments storm count"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :graveyard :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell-mode db :player-1 obj-id flashback-mode)]
      (is (= 1 (q/get-storm-count db-cast :player-1))
          "Storm should increment for flashback cast"))))


;; === Flashback resolution tests ===

(deftest flashback-resolves-to-exile-test
  (testing "Flashback Deep Analysis exiles after resolution"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :graveyard :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          [db _lib-ids] (th/add-cards-to-library db [:dark-ritual :dark-ritual :dark-ritual] :player-1)
          db-cast (th/cast-with-target db :player-1 obj-id :player-1)
          {:keys [db]} (th/resolve-top db-cast)]
      (is (= :exile (:object/zone (q/get-object db obj-id)))
          "Flashback spell should go to exile after resolution"))))


;; === Counter/remove from stack tests ===

(deftest flashback-spell-countered-exiles-test
  (testing "Countered flashback spell goes to exile not graveyard"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :graveyard :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell-mode db :player-1 obj-id flashback-mode)
          ;; Counter = move off stack without resolving
          db-countered (rules/move-spell-off-stack db-cast :player-1 obj-id)]
      (is (= :exile (:object/zone (q/get-object db-countered obj-id)))
          "Countered flashback spell should go to exile"))))


(deftest normal-spell-countered-goes-to-graveyard-test
  (testing "Countered normal spell goes to graveyard"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 3 :blue 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          primary-mode (first modes)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell-mode db :player-1 obj-id primary-mode)
          db-countered (rules/move-spell-off-stack db-cast :player-1 obj-id)]
      (is (= :graveyard (:object/zone (q/get-object db-countered obj-id)))
          "Countered normal spell should go to graveyard"))))


(deftest move-spell-off-stack-no-op-if-not-on-stack-test
  (testing "move-spell-off-stack is no-op if spell not on stack"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :hand :player-1)
          ;; Don't cast - spell is still in hand
          db' (rules/move-spell-off-stack db :player-1 obj-id)]
      (is (= :hand (:object/zone (q/get-object db' obj-id)))
          "Spell should remain in hand (no-op)"))))


(deftest move-spell-off-stack-nil-cast-mode-test
  (testing "move-spell-off-stack handles nil cast-mode (old cast-spell API)"
    (let [db (th/create-test-db {:mana {:black 1}})
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Cast using old API (no mode tracking)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 obj-id)
          ;; Counter the spell
          db-countered (rules/move-spell-off-stack db-cast :player-1 obj-id)]
      (is (= :graveyard (:object/zone (q/get-object db-countered obj-id)))
          "Spell without cast-mode should go to graveyard (default)"))))


;; === Player targeting tests ===

(deftest deep-analysis-triggers-player-target-selection-test
  (testing "Resolving Deep Analysis triggers player target selection"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 3 :blue 1})
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 obj-id)
          result (resolution/resolve-one-item db-cast)]
      (is (= :player-target (:selection/domain (:pending-selection result)))
          "Selection type should be :player-target")
      (is (= obj-id (:selection/spell-id (:pending-selection result)))
          "Should track spell id for cleanup"))))


(deftest deep-analysis-player-target-selection-state-test
  (testing "Player target selection state has correct structure"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 3 :blue 1})
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 obj-id)
          result (resolution/resolve-one-item db-cast)
          selection (:pending-selection result)]
      (is (= :player-1 (:selection/player-id selection))
          "Caster should be tracked")
      (is (= #{} (:selection/selected selection))
          "No target should be selected initially")
      (is (= :draw (:effect/type (:selection/target-effect selection)))
          "Stored effect should be the draw effect"))))


(deftest deep-analysis-draw-effect-with-resolved-target-test
  (testing "Draw effect executes correctly when target is resolved player"
    (let [db (th/create-test-db)
          ;; Add library cards to draw from
          [db _lib-ids] (th/add-cards-to-library db [:dark-ritual :dark-ritual :dark-ritual] :player-1)
          initial-hand-count (th/get-hand-count db :player-1)
          ;; Execute draw effect with resolved target (simulating confirmed selection)
          db-after (effects/execute-effect db :player-1
                                           {:effect/type :draw
                                            :effect/amount 2
                                            :effect/target :player-1})
          final-hand-count (th/get-hand-count db-after :player-1)]
      (is (= (+ initial-hand-count 2) final-hand-count)
          "Player should have drawn 2 cards when target is :player-1"))))


(deftest deep-analysis-selection-stores-target-effect-test
  (testing "Player target selection stores effect for later execution"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 3 :blue 1})
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 obj-id)
          result (resolution/resolve-one-item db-cast)
          selection (:pending-selection result)
          target-effect (:selection/target-effect selection)]
      ;; The stored effect should have :any-player target (to be replaced on confirm)
      (is (= :draw (:effect/type target-effect))
          "Should store draw effect")
      (is (= 2 (:effect/amount target-effect))
          "Should store amount")
      (is (= :any-player (:effect/target target-effect))
          "Should have :any-player target awaiting selection"))))


;; === Flashback casting from graveyard ===

(deftest deep-analysis-flashback-uses-flashback-mode-test
  (testing "cast-spell from graveyard uses flashback mode, not primary"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :graveyard :player-1)
          ;; Add flashback mana (1U) - NOT primary mana (3U)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          ;; Verify can cast with flashback cost
          _ (is (rules/can-cast? db :player-1 obj-id)
                "Should be castable from graveyard with flashback cost")
          ;; Cast using cast-spell (should pick flashback mode)
          initial-life (q/get-life-total db :player-1)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 obj-id)
          after-life (q/get-life-total db-cast :player-1)]
      ;; Verify spell is on stack
      (is (= :stack (:object/zone (q/get-object db-cast obj-id)))
          "Spell should be on stack")
      ;; Verify flashback life cost was paid
      (is (= (- initial-life 3) after-life)
          "Flashback should pay 3 life")
      ;; Verify cast mode is flashback (for exile on resolution)
      (let [obj (q/get-object db-cast obj-id)]
        (is (= :flashback (:mode/id (:object/cast-mode obj)))
            "Cast mode should be flashback")))))


(deftest deep-analysis-flashback-full-flow-exiles-test
  (testing "Flashback Deep Analysis with cast-time targeting exiles after resolution"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :graveyard :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          ;; Add library cards to draw from
          [db _lib-ids] (th/add-cards-to-library db [:dark-ritual :dark-ritual :dark-ritual] :player-1)
          ;; Cast with targeting through production path
          db-cast (th/cast-with-target db :player-1 obj-id :player-1)
          ;; Resolve — target stored at cast time, no selection needed
          {:keys [db]} (th/resolve-top db-cast)]
      (is (= :exile (:object/zone (q/get-object db obj-id)))
          "Flashback spell should exile after resolution with player targeting"))))


(deftest deep-analysis-not-castable-from-graveyard-without-mana-test
  (testing "Deep Analysis not castable from graveyard without flashback mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :graveyard :player-1)]
      ;; No mana added
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard without mana"))))


(deftest deep-analysis-not-castable-from-graveyard-with-wrong-mana-test
  (testing "Deep Analysis not castable from graveyard with primary cost mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :graveyard :player-1)
          ;; Add colorless only - flashback needs blue
          db (mana/add-mana db :player-1 {:colorless 2})]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without blue mana"))))


;; === Cast-time Targeting Tests ===

(deftest deep-analysis-has-targeting-requirement-test
  (testing "Deep Analysis has :card/targeting for player target"
    (let [card deep-analysis/card
          reqs (targeting/get-targeting-requirements card)]
      (is (= 1 (count reqs))
          "Should have exactly 1 targeting requirement")
      (is (= :player (:target/id (first reqs)))
          "Target id should be :player")
      (is (= :player (:target/type (first reqs)))
          "Target type should be :player")
      (is (true? (:target/required (first reqs)))
          "Target should be required"))))


(deftest deep-analysis-cast-time-targeting-test
  (testing "Casting Deep Analysis prompts for target selection via cast-spell-with-targeting"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 3 :blue 1})
          ;; Use cast-spell-with-targeting which should return pending-selection
          ;; for cards with :card/targeting
          result (sel-targeting/cast-spell-with-targeting db :player-1 obj-id)]
      ;; Should have pending target selection (not yet on stack)
      (is (= :cast-time-targeting (:selection/domain (:pending-target-selection result)))
          "Selection type should be :cast-time-targeting"))))


(deftest deep-analysis-confirm-cast-time-target-test
  (testing "Confirming cast-time target moves spell to stack with stored target"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 3 :blue 1})
          ;; Cast with target through production path
          db-cast (th/cast-with-target db :player-1 obj-id :player-1)]
      ;; Spell should be on stack
      (is (= :stack (:object/zone (q/get-object db-cast obj-id)))
          "Spell should be on stack after confirming target")
      ;; Target should be stored on stack-item
      (let [obj-eid (d/q '[:find ?e . :in $ ?oid
                           :where [?e :object/id ?oid]]
                         db-cast obj-id)
            stack-item (stack/get-stack-item-by-object-ref db-cast obj-eid)]
        (is (= {:player :player-1} (:stack-item/targets stack-item))
            "Stack-item should have stored target {:player :player-1}"))
      ;; Object should NOT have targets
      (is (nil? (:object/targets (q/get-object db-cast obj-id)))
          "Object should NOT have :object/targets"))))


(deftest deep-analysis-resolution-uses-stored-target-test
  (testing "Resolution uses stored :stack-item/targets when present"
    (let [db (th/create-test-db)
          ;; Add library cards to draw from
          [db _lib-ids] (th/add-cards-to-library db [:dark-ritual :dark-ritual :dark-ritual] :player-1)
          ;; Add Deep Analysis to hand
          [db obj-id] (th/add-card-to-zone db :deep-analysis :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 3 :blue 1})
          ;; Cast with target through production path
          db-cast (th/cast-with-target db :player-1 obj-id :player-1)
          ;; Count cards before resolution
          initial-hand-count (th/get-hand-count db-cast :player-1)
          ;; Resolve the spell - should use stored target, not prompt
          resolve-result (th/resolve-top db-cast)]
      ;; Should NOT have pending selection (target already chosen at cast time)
      (is (nil? (:selection resolve-result))
          "Should not prompt for target when :stack-item/targets is present")
      ;; Spell should have moved off stack
      (is (= :graveyard (:object/zone (q/get-object (:db resolve-result) obj-id)))
          "Spell should be in graveyard after resolution")
      ;; Player 1 should have drawn 2 cards
      (is (= (+ initial-hand-count 2)
             (th/get-hand-count (:db resolve-result) :player-1))
          "Target player should have drawn 2 cards"))))


(deftest deep-analysis-player-target-always-legal-test
  (testing "Player targets are always legal (players don't move zones)"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 3 :blue 1})
          ;; Cast with target through production path
          db-cast (th/cast-with-target db :player-1 obj-id :player-1)
          ;; Get targets from stack-item
          obj-eid (d/q '[:find ?e . :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db-cast obj-id)
          stack-item (stack/get-stack-item-by-object-ref db-cast obj-eid)
          targets (:stack-item/targets stack-item)
          requirements (targeting/get-targeting-requirements deep-analysis/card)]
      ;; Check that all targets are legal (player targets always are)
      (is (targeting/all-targets-legal? db-cast targets requirements)
          "Player targets should always be legal"))))


(deftest deep-analysis-flashback-with-targeting-test
  (testing "Flashback cast also uses cast-time targeting"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :deep-analysis :graveyard :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :blue 1})
          ;; Cast via cast-spell-with-targeting (should work from graveyard too)
          result (sel-targeting/cast-spell-with-targeting db :player-1 obj-id)]
      ;; Should have pending target selection
      (is (= :cast-time-targeting (:selection/domain (:pending-target-selection result)))
          "Selection type should be :cast-time-targeting")
      ;; Mode should be flashback
      (is (= :flashback (:mode/id (:selection/mode (:pending-target-selection result))))
          "Mode should be flashback"))))
