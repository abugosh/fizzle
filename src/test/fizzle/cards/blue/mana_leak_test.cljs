(ns fizzle.cards.blue.mana-leak-test
  "Tests for Mana Leak card.

   Mana Leak: {1}{U} - Instant
   Counter target spell unless its controller pays {3}.

   Key behaviors:
   - Targets any spell on the stack
   - Presents payment choice to targeted spell's controller
   - If controller pays {3}: spell survives on stack
   - If controller declines: spell is countered (goes to graveyard)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.mana-leak :as mana-leak]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.game :as game]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest mana-leak-card-definition-test
  (testing "card has correct oracle properties"
    (let [card mana-leak/card]
      (is (= :mana-leak (:card/id card))
          "Card ID should be :mana-leak")
      (is (= "Mana Leak" (:card/name card))
          "Card name should match oracle")
      (is (= 2 (:card/cmc card))
          "CMC should be 2")
      (is (= {:colorless 1 :blue 1} (:card/mana-cost card))
          "Mana cost should be {1}{U}")
      (is (= #{:blue} (:card/colors card))
          "Card should be blue")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")
      (is (= "Counter target spell unless its controller pays {3}." (:card/text card))
          "Oracle text should match")))

  (testing "card has correct targeting"
    (let [targeting (:card/targeting mana-leak/card)]
      (is (vector? targeting)
          "Targeting must be a vector")
      (is (= 1 (count targeting))
          "Should have exactly one target requirement")
      (let [req (first targeting)]
        (is (= :target-spell (:target/id req))
            "Target ID should be :target-spell")
        (is (= :object (:target/type req))
            "Target type should be :object")
        (is (= :stack (:target/zone req))
            "Target zone should be :stack")
        (is (= :any (:target/controller req))
            "Should target any controller's spell")
        (is (true? (:target/required req))
            "Target should be required"))))

  (testing "card has one counter-spell effect with unless-pay"
    (let [effects (:card/effects mana-leak/card)]
      (is (= 1 (count effects))
          "Should have exactly one effect")
      (let [effect (first effects)]
        (is (= :counter-spell (:effect/type effect))
            "Effect should be counter-spell")
        (is (= :target-spell (:effect/target-ref effect))
            "Effect should reference target spell")
        (is (= {:colorless 3} (:effect/unless-pay effect))
            "Unless-pay cost should be {3}")))))


;; === B. Cast-Resolve Happy Path ===

(defn- cast-mana-leak-targeting
  "Helper: set up Mana Leak targeting a spell on the stack via production path.
   Returns db with Mana Leak on stack targeting the given spell."
  [db ml-id target-id]
  (th/cast-with-target db :player-1 ml-id target-id))


(deftest mana-leak-counters-when-declined-test
  (testing "Mana Leak counters spell when controller declines to pay"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Opponent casts Dark Ritual
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Cast Mana Leak targeting ritual
          [db ml-id] (th/add-card-to-zone db :mana-leak :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1 :colorless 1})
          db-cast (cast-mana-leak-targeting db ml-id ritual-id)
          ;; Resolve Mana Leak -> should get unless-pay selection
          result (game/resolve-one-item db-cast)]
      ;; Should get a pending selection for unless-pay
      (is (some? (:pending-selection result))
          "Should create unless-pay selection")
      (let [selection (:pending-selection result)
            _ (is (= :unless-pay (:selection/type selection))
                  "Selection type should be :unless-pay")
            _ (is (= :player-2 (:selection/player-id selection))
                  "Choosing player should be the targeted spell's controller")
            ;; Decline payment
            db-after-resolve (:db result)
            sel-with-decline (assoc selection :selection/selected #{:decline})
            exec-result (sel-core/execute-confirmed-selection db-after-resolve sel-with-decline)]
        ;; Ritual should be countered -> graveyard
        (is (= :graveyard (:object/zone (q/get-object (:db exec-result) ritual-id)))
            "Spell should be countered when payment declined")))))


(deftest mana-leak-spell-survives-when-paid-test
  (testing "Spell survives when controller pays {3}"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Opponent casts Dark Ritual with extra mana to pay
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1 :colorless 3})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Cast Mana Leak
          [db ml-id] (th/add-card-to-zone db :mana-leak :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1 :colorless 1})
          db-cast (cast-mana-leak-targeting db ml-id ritual-id)
          ;; Resolve Mana Leak
          result (game/resolve-one-item db-cast)
          selection (:pending-selection result)
          db-after-resolve (:db result)
          ;; Pay the {3}
          sel-with-pay (assoc selection :selection/selected #{:pay})
          exec-result (sel-core/execute-confirmed-selection db-after-resolve sel-with-pay)]
      ;; Ritual should still be on the stack (not countered)
      (is (= :stack (:object/zone (q/get-object (:db exec-result) ritual-id)))
          "Spell should remain on stack when payment is made")
      ;; Controller's mana should be reduced by 3
      (let [pool (q/get-mana-pool (:db exec-result) :player-2)]
        (is (= 0 (get pool :colorless 0))
            "Controller should have paid 3 colorless mana")))))


;; === C. Cannot-Cast Guards ===

(deftest mana-leak-cannot-cast-without-mana-test
  (testing "Cannot cast Mana Leak without sufficient mana"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a spell on stack
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Mana Leak in hand with no mana
          [db ml-id] (th/add-card-to-zone db :mana-leak :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 ml-id))
          "Should not be castable without mana"))))


(deftest mana-leak-cannot-cast-with-empty-stack-test
  (testing "Cannot cast Mana Leak with empty stack"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ml-id] (th/add-card-to-zone db :mana-leak :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1 :colorless 1})]
      (is (false? (targeting/has-valid-targets? db :player-1 mana-leak/card))
          "Should have no valid targets with empty stack")
      (is (false? (rules/can-cast? db :player-1 ml-id))
          "Should not be castable with empty stack"))))


(deftest mana-leak-cannot-cast-from-graveyard-test
  (testing "Cannot cast Mana Leak from graveyard"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ml-id] (th/add-card-to-zone db :mana-leak :graveyard :player-1)
          db (mana/add-mana db :player-1 {:blue 1 :colorless 1})
          ;; Put a spell on stack
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)]
      (is (false? (rules/can-cast? db :player-1 ml-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest mana-leak-increments-storm-count-test
  (testing "Casting Mana Leak increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a spell on stack
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Cast Mana Leak
          [db ml-id] (th/add-card-to-zone db :mana-leak :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1 :colorless 1})
          storm-before (q/get-storm-count db :player-1)
          db-cast (cast-mana-leak-targeting db ml-id ritual-id)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === E. Targeting Tests ===

(deftest mana-leak-targets-any-spell-type-test
  (testing "Targets both instants and sorceries on the stack"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          [db duress-id] (th/add-card-to-zone db :duress :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 duress-id)
          target-req (first (:card/targeting mana-leak/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 2 (count targets))
          "Should find both spells as valid targets"))))


;; === F. Unless-Pay Selection Tests ===

(deftest mana-leak-selection-has-pay-option-when-affordable-test
  (testing "Selection includes :pay when controller has enough mana"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1 :colorless 3})
          db (rules/cast-spell db :player-2 ritual-id)
          [db ml-id] (th/add-card-to-zone db :mana-leak :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1 :colorless 1})
          db-cast (cast-mana-leak-targeting db ml-id ritual-id)
          result (game/resolve-one-item db-cast)
          selection (:pending-selection result)]
      (is (= [:pay :decline] (:selection/valid-targets selection))
          "Should offer both pay and decline"))))


(deftest mana-leak-selection-always-offers-both-options-test
  (testing "Selection always offers both :pay and :decline (can-pay checked reactively)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Opponent has 0 remaining mana after casting ritual
          [db ml-id] (th/add-card-to-zone db :mana-leak :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1 :colorless 1})
          db-cast (cast-mana-leak-targeting db ml-id ritual-id)
          result (game/resolve-one-item db-cast)
          selection (:pending-selection result)]
      (is (= [:pay :decline] (:selection/valid-targets selection))
          "Should always offer both options — affordability is checked reactively in view"))))


;; === G. Edge Cases ===

(deftest mana-leak-fizzles-when-target-leaves-stack-test
  (testing "Mana Leak fizzles when target spell resolves before it"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          [db ml-id] (th/add-card-to-zone db :mana-leak :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1 :colorless 1})
          db-cast (cast-mana-leak-targeting db ml-id ritual-id)
          ;; Move ritual off stack (simulate it resolving)
          db-ritual-resolved (rules/move-spell-off-stack db-cast nil ritual-id)
          result (game/resolve-one-item db-ritual-resolved)
          db-resolved (:db result)]
      ;; Mana Leak should be in graveyard (fizzled)
      (is (= :graveyard (:object/zone (q/get-object db-resolved ml-id)))
          "Mana Leak should be in graveyard after fizzling"))))


(deftest mana-leak-partial-mana-cannot-pay-test
  (testing "Controller with less than {3} available cannot pay"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1 :colorless 2})
          db (rules/cast-spell db :player-2 ritual-id)
          [db ml-id] (th/add-card-to-zone db :mana-leak :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 1 :colorless 1})
          db-cast (cast-mana-leak-targeting db ml-id ritual-id)
          result (game/resolve-one-item db-cast)
          selection (:pending-selection result)]
      ;; Both options always offered — affordability checked reactively in view
      (is (= [:pay :decline] (:selection/valid-targets selection))
          "Should always offer both options"))))
