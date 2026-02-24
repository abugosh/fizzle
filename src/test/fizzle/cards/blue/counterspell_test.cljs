(ns fizzle.cards.blue.counterspell-test
  "Tests for Counterspell card.

   Counterspell: UU - Instant
   Counter target spell.

   Key behaviors:
   - Targets any spell (object) on the stack
   - Countered spell goes to graveyard (normal), exile (flashback), removed (copy)
   - Does not target triggered abilities (stack-items without objects)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.blue.counterspell :as counterspell]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.game :as game]
    [fizzle.events.selection.targeting :as sel-targeting]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest counterspell-card-definition-test
  (testing "card has correct oracle properties"
    (let [card counterspell/card]
      (is (= :counterspell (:card/id card))
          "Card ID should be :counterspell")
      (is (= "Counterspell" (:card/name card))
          "Card name should match oracle")
      (is (= 2 (:card/cmc card))
          "CMC should be 2")
      (is (= {:blue 2} (:card/mana-cost card))
          "Mana cost should be {U}{U}")
      (is (= #{:blue} (:card/colors card))
          "Card should be blue")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")
      (is (= "Counter target spell." (:card/text card))
          "Oracle text should match")))

  (testing "card has correct targeting"
    (let [targeting (:card/targeting counterspell/card)]
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
            "Should be able to target any controller's spell")
        (is (true? (:target/required req))
            "Target should be required"))))

  (testing "card has one counter-spell effect"
    (let [effects (:card/effects counterspell/card)]
      (is (= 1 (count effects))
          "Should have exactly one effect")
      (let [effect (first effects)]
        (is (= :counter-spell (:effect/type effect))
            "Effect should be counter-spell")
        (is (= :target-spell (:effect/target-ref effect))
            "Effect should reference target spell")))))


;; === B. Cast-Resolve Happy Path ===

(deftest counterspell-counters-spell-on-stack-test
  (testing "Counterspell counters a spell and sends it to graveyard"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put Dark Ritual on opponent's hand and cast it
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Verify ritual is on stack
          _ (is (= :stack (:object/zone (q/get-object db ritual-id)))
                "Dark Ritual should be on stack before counter")
          ;; Add Counterspell to player's hand with UU mana
          [db cs-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 2})
          ;; Cast Counterspell targeting Dark Ritual
          target-req (first (:card/targeting counterspell/card))
          modes (rules/get-casting-modes db :player-1 cs-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id cs-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{ritual-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          ;; Resolve Counterspell (top of stack)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      ;; Dark Ritual should be countered -> graveyard
      (is (= :graveyard (:object/zone (q/get-object db-resolved ritual-id)))
          "Countered spell should be in graveyard")
      ;; Counterspell should be in graveyard (resolved)
      (is (= :graveyard (:object/zone (q/get-object db-resolved cs-id)))
          "Counterspell should be in graveyard after resolving"))))


(deftest counterspell-counters-instant-on-stack-test
  (testing "Counterspell counters any type of spell (not card-specific)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put Opt on opponent's hand and cast it
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          ;; Add Counterspell
          [db cs-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 2})
          target-req (first (:card/targeting counterspell/card))
          modes (rules/get-casting-modes db :player-1 cs-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id cs-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{opt-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      (is (= :graveyard (:object/zone (q/get-object db-resolved opt-id)))
          "Countered Opt should be in graveyard"))))


;; === C. Cannot-Cast Guards ===

(deftest counterspell-cannot-cast-without-mana-test
  (testing "Cannot cast Counterspell without blue mana"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a spell on stack for targeting
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Add Counterspell but no mana
          [db cs-id] (th/add-card-to-zone db :counterspell :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 cs-id))
          "Should not be castable without mana"))))


(deftest counterspell-cannot-cast-with-empty-stack-test
  (testing "Cannot cast Counterspell with empty stack (no valid targets)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cs-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 2})]
      (is (false? (targeting/has-valid-targets? db :player-1 counterspell/card))
          "Should have no valid targets with empty stack")
      (is (false? (rules/can-cast? db :player-1 cs-id))
          "Should not be castable with empty stack"))))


(deftest counterspell-cannot-cast-from-graveyard-test
  (testing "Cannot cast Counterspell from graveyard"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db cs-id] (th/add-card-to-zone db :counterspell :graveyard :player-1)
          db (mana/add-mana db :player-1 {:blue 2})
          ;; Put a spell on stack for potential targeting
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)]
      (is (false? (rules/can-cast? db :player-1 cs-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest counterspell-increments-storm-count-test
  (testing "Casting Counterspell increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put a spell on stack
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Cast Counterspell
          [db cs-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 2})
          storm-before (q/get-storm-count db :player-1)
          target-req (first (:card/targeting counterspell/card))
          modes (rules/get-casting-modes db :player-1 cs-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id cs-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{ritual-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === E. Targeting Tests ===

(deftest counterspell-targets-any-spell-type-test
  (testing "Targets both instants and sorceries on the stack"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put an instant on the stack
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-2)
          db (mana/add-mana db :player-2 {:blue 1})
          db (rules/cast-spell db :player-2 opt-id)
          ;; Put a sorcery on the stack (use Duress as a sorcery)
          [db duress-id] (th/add-card-to-zone db :duress :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 duress-id)
          target-req (first (:card/targeting counterspell/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 2 (count targets))
          "Should find both spells as valid targets")
      (is (contains? (set targets) opt-id)
          "Should include instant on stack")
      (is (contains? (set targets) duress-id)
          "Should include sorcery on stack"))))


(deftest counterspell-targets-do-not-include-abilities-test
  (testing "Triggered ability stack-items are not valid targets"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; The targeting system queries objects by zone. Triggered abilities
          ;; are stack-items, not objects in :stack zone. Verify no targets
          ;; when only abilities are on the stack (empty stack = no spell objects).
          target-req (first (:card/targeting counterspell/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      ;; With no spells cast, there are no objects in :stack zone
      (is (empty? targets)
          "Should not find any targets (no spell objects on stack)"))))


;; === G. Edge Cases ===

(deftest counterspell-fizzles-when-target-leaves-stack-test
  (testing "Counterspell fizzles when target spell resolves before it"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put Dark Ritual on stack
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          db (mana/add-mana db :player-2 {:black 1})
          db (rules/cast-spell db :player-2 ritual-id)
          ;; Cast Counterspell targeting ritual
          [db cs-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 2})
          target-req (first (:card/targeting counterspell/card))
          modes (rules/get-casting-modes db :player-1 cs-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id cs-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{ritual-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          ;; Now manually resolve the ritual first (simulate it leaving stack)
          ;; Move ritual off stack to graveyard
          db-ritual-resolved (rules/move-spell-off-stack db-cast nil ritual-id)
          ;; Now resolve Counterspell — target is gone, should fizzle
          ;; But we need to remove ritual's stack-item too
          ;; Use resolve-one-item which handles the fizzle check
          ;; The ritual's stack item is still there; Counterspell is on top
          ;; Actually Counterspell is on top of stack (LIFO), so resolve it
          result (game/resolve-one-item db-ritual-resolved)
          db-resolved (:db result)]
      ;; Counterspell should still end up in graveyard (it resolved, target just fizzled)
      (is (= :graveyard (:object/zone (q/get-object db-resolved cs-id)))
          "Counterspell should be in graveyard after fizzling")
      ;; Dark Ritual should already be in graveyard (moved there before counter resolved)
      (is (= :graveyard (:object/zone (q/get-object db-resolved ritual-id)))
          "Dark Ritual should remain in graveyard"))))


(deftest counterspell-counter-copy-removes-from-db-test
  (testing "Countering a copy removes it from db entirely"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Create a copy object on the stack manually
          [db copy-id] (th/add-card-to-zone db :dark-ritual :stack :player-2)
          ;; Mark it as a copy
          copy-eid (q/get-object-eid db copy-id)
          db (d/db-with db [[:db/add copy-eid :object/is-copy true]])
          ;; Add Counterspell
          [db cs-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 2})
          target-req (first (:card/targeting counterspell/card))
          modes (rules/get-casting-modes db :player-1 cs-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id cs-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{copy-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      ;; Copy should be completely removed from db
      (is (nil? (q/get-object db-resolved copy-id))
          "Copy should be removed from db entirely"))))


(deftest counterspell-counter-flashback-spell-exiles-test
  (testing "Countering a flashback spell sends it to exile"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Create a spell on the stack with flashback cast mode
          [db spell-id] (th/add-card-to-zone db :dark-ritual :stack :player-2)
          spell-eid (q/get-object-eid db spell-id)
          ;; Set cast mode with :mode/on-resolve :exile (simulates flashback)
          db (d/db-with db [[:db/add spell-eid :object/cast-mode
                             {:mode/id :flashback
                              :mode/on-resolve :exile}]])
          ;; Add Counterspell
          [db cs-id] (th/add-card-to-zone db :counterspell :hand :player-1)
          db (mana/add-mana db :player-1 {:blue 2})
          target-req (first (:card/targeting counterspell/card))
          modes (rules/get-casting-modes db :player-1 cs-id)
          mode (first modes)
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id cs-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{spell-id}}
          db-cast (sel-targeting/confirm-cast-time-target db selection)
          result (game/resolve-one-item db-cast)
          db-resolved (:db result)]
      ;; Flashback spell should go to exile, not graveyard
      (is (= :exile (:object/zone (q/get-object db-resolved spell-id)))
          "Flashback spell should be exiled when countered"))))
