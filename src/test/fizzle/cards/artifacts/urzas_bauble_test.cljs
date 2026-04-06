(ns fizzle.cards.artifacts.urzas-bauble-test
  "Tests for Urza's Bauble card.

   Urza's Bauble: 0 - Artifact
   {T}, Sacrifice this artifact: Look at a card at random in target player's
   hand. You draw a card at the beginning of the next turn's upkeep.

   Rulings:
   - Targets a player (any player, including yourself).
   - The peek is informational only (no state change).
   - Delayed draw triggers at the beginning of the next turn's upkeep.
   - Sacrifice is part of the activation cost (feeds threshold/graveyard count)."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.artifacts.urzas-bauble :as urzas-bauble]
    [fizzle.db.queries :as q]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.resolution :as resolution]
    [fizzle.history.descriptions :as descriptions]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

;; Oracle: "Urza's Bauble" -- verified against Scryfall
(deftest urzas-bauble-card-definition-test
  (testing "card has correct oracle properties"
    (let [card urzas-bauble/card]
      (is (= :urzas-bauble (:card/id card))
          "Card ID should be :urzas-bauble")
      (is (= "Urza's Bauble" (:card/name card))
          "Card name should match oracle")
      (is (= 0 (:card/cmc card))
          "CMC should be 0")
      (is (= {} (:card/mana-cost card))
          "Mana cost should be empty (free to cast)")
      (is (= #{} (:card/colors card))
          "Card should be colorless")
      (is (= #{:artifact} (:card/types card))
          "Card should be exactly an artifact")))

  (testing "card has correct ability structure"
    (let [abilities (:card/abilities urzas-bauble/card)]
      (is (= 1 (count abilities))
          "Should have exactly one ability")
      (let [ability (first abilities)]
        (is (= :activated (:ability/type ability))
            "Ability should be :activated")
        (is (true? (:tap (:ability/cost ability)))
            "Ability cost should include tap")
        (is (true? (:sacrifice-self (:ability/cost ability)))
            "Ability cost should include sacrifice-self"))))

  (testing "ability has correct targeting"
    (let [ability (first (:card/abilities urzas-bauble/card))
          targeting (:ability/targeting ability)]
      (is (vector? targeting)
          "Targeting must be a vector")
      (is (= 1 (count targeting))
          "Should have exactly one target requirement")
      (let [req (first targeting)]
        (is (= :player (:target/id req))
            "Target ID should be :player")
        (is (= :player (:target/type req))
            "Target type should be :player")
        (is (contains? (:target/options req) :any-player)
            "Should allow targeting any player")
        (is (true? (:target/required req))
            "Target should be required"))))

  (testing "ability has peek-random-hand and grant-delayed-draw effects"
    (let [ability (first (:card/abilities urzas-bauble/card))
          effect-list (:ability/effects ability)]
      (is (= 2 (count effect-list))
          "Should have exactly 2 effects")
      (let [peek-effect (first effect-list)
            draw-effect (second effect-list)]
        (is (= :peek-random-hand (:effect/type peek-effect))
            "First effect should be :peek-random-hand")
        (is (= :player (:effect/target-ref peek-effect))
            "Peek effect should reference :player target")
        (is (= :grant-delayed-draw (:effect/type draw-effect))
            "Second effect should be :grant-delayed-draw")
        (is (= :controller (:effect/target draw-effect))
            "Delayed draw should target :controller")))))


;; === B. Cast-Resolve Happy Path ===

;; Oracle: 0-cost artifact enters battlefield when cast
(deftest urzas-bauble-cast-to-battlefield-test
  (testing "Urza's Bauble enters the battlefield when cast (0-cost artifact)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :urzas-bauble :hand :player-1)
          _ (is (true? (rules/can-cast? db :player-1 obj-id))
                "Should be castable with 0 mana")
          db-cast (rules/cast-spell db :player-1 obj-id)
          result (resolution/resolve-one-item db-cast)
          db-resolved (:db result)]
      (is (= :battlefield (:object/zone (q/get-object db-resolved obj-id)))
          "Urza's Bauble should be on battlefield after resolution"))))


;; Oracle: "{T}, Sacrifice this artifact: Look at a card at random...draw..."
(deftest urzas-bauble-activate-resolve-test
  (testing "Activating and resolving grants delayed draw"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db bauble-id] (th/add-card-to-zone db :urzas-bauble :battlefield :player-1)
          ;; Put cards in opponent's hand so peek has something
          [db _hand-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          ;; Activate ability (index 0)
          result (ability-events/activate-ability db :player-1 bauble-id 0)
          sel (:pending-selection result)]
      ;; Should have pending selection for player target
      (is (= :ability-targeting (:selection/type sel))
          "Should enter targeting selection")
      ;; Select opponent as target
      (let [selection-with-target (assoc sel :selection/selected #{:player-2})
            confirm-result (ability-events/confirm-ability-target (:db result) selection-with-target)
            db-after-confirm (:db confirm-result)]
        ;; Bauble should be sacrificed (cost paid)
        (is (= :graveyard (:object/zone (q/get-object db-after-confirm bauble-id)))
            "Urza's Bauble should be in graveyard after sacrifice")
        ;; Stack should have the ability
        (let [top-item (stack/get-top-stack-item db-after-confirm)]
          (is (= :activated-ability (:stack-item/type top-item))
              "Stack item should be activated ability type")
          ;; Resolve the ability
          (let [db-resolved (:db (resolution/resolve-one-item db-after-confirm))]
            ;; Check that delayed-draw grant was created for player-1
            (let [player-grants (grants/get-player-grants db-resolved :player-1)
                  draw-grants (filter #(= :delayed-effect (:grant/type %)) player-grants)]
              (is (= 1 (count draw-grants))
                  "Player-1 should have exactly 1 delayed-effect grant after resolution"))))))))


;; === C. Cannot-Cast Guards ===

(deftest urzas-bauble-cannot-cast-from-graveyard-test
  (testing "Cannot cast Urza's Bauble from graveyard (no flashback)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :urzas-bauble :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard"))))


(deftest urzas-bauble-cannot-activate-when-tapped-test
  (testing "Cannot activate Urza's Bauble when already tapped"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db bauble-id] (th/add-card-to-zone db :urzas-bauble :battlefield :player-1)
          ;; Manually tap it
          obj-eid (q/get-object-eid db bauble-id)
          db-tapped (d/db-with db [[:db/add obj-eid :object/tapped true]])
          result (ability-events/activate-ability db-tapped :player-1 bauble-id 0)]
      (is (nil? (:pending-selection result))
          "Should not enter targeting selection when tapped"))))


;; === D. Storm Count ===

(deftest urzas-bauble-increments-storm-count-test
  (testing "Casting Urza's Bauble increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :urzas-bauble :hand :player-1)
          storm-before (q/get-storm-count db :player-1)
          db-cast (rules/cast-spell db :player-1 obj-id)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === E. Targeting Tests ===

;; Oracle: "target player's hand" -- can target any player
(deftest urzas-bauble-can-target-self-test
  (testing "Can target self"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db bauble-id] (th/add-card-to-zone db :urzas-bauble :battlefield :player-1)
          result (ability-events/activate-ability db :player-1 bauble-id 0)
          sel (:pending-selection result)
          ;; Target SELF
          selection-with-target (assoc sel :selection/selected #{:player-1})
          confirm-result (ability-events/confirm-ability-target (:db result) selection-with-target)
          db-resolved (:db (resolution/resolve-one-item (:db confirm-result)))]
      ;; Bauble should be in graveyard (sacrificed)
      (is (= :graveyard (:object/zone (q/get-object db-resolved bauble-id)))
          "Urza's Bauble should be in graveyard after sacrifice")
      ;; Delayed draw should be granted
      (let [player-grants (grants/get-player-grants db-resolved :player-1)
            draw-grants (filter #(= :delayed-effect (:grant/type %)) player-grants)]
        (is (= 1 (count draw-grants))
            "Player-1 should have exactly 1 delayed-draw grant")))))


(deftest urzas-bauble-has-valid-targets-test
  (testing "has-valid-targets? returns true when players exist"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ability (first (:card/abilities urzas-bauble/card))
          target-req (first (:ability/targeting ability))]
      (is (= 2 (count (targeting/find-valid-targets db :player-1 target-req)))
          "Should find both players as valid targets"))))


;; === F. Effect Tests ===

;; Oracle: "Look at a card at random in target player's hand"
(deftest peek-random-hand-effect-stores-result-test
  (testing "peek-random-hand stores revealed card name on game state"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _hand-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          effect {:effect/type :peek-random-hand
                  :effect/target :player-2}
          db-after (effects/execute-effect db :player-1 effect)]
      (is (= "Dark Ritual" (:game/peek-result (q/get-game-state db-after)))
          "peek-result should contain the revealed card name")
      (is (= (q/get-hand db :player-2) (q/get-hand db-after :player-2))
          "Opponent's hand should be unchanged (peek is informational)"))))


(deftest peek-random-hand-empty-hand-noop-test
  (testing "peek-random-hand on empty hand stores no peek-result"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          _ (is (= 0 (th/get-zone-count db :hand :player-2))
                "Precondition: opponent has empty hand")
          effect {:effect/type :peek-random-hand
                  :effect/target :player-2}
          db-after (effects/execute-effect db :player-1 effect)]
      (is (nil? (:game/peek-result (q/get-game-state db-after)))
          "No peek-result should be stored for empty hand"))))


;; === G. Edge Cases ===

;; Edge case: Sacrifice feeds threshold (artifact in graveyard)
(deftest urzas-bauble-sacrifice-feeds-graveyard-count-test
  (testing "Sacrificed Urza's Bauble goes to graveyard (feeds threshold)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db bauble-id] (th/add-card-to-zone db :urzas-bauble :battlefield :player-1)
          gy-before (th/get-zone-count db :graveyard :player-1)
          ;; Activate ability
          result (ability-events/activate-ability db :player-1 bauble-id 0)
          sel (:pending-selection result)
          selection-with-target (assoc sel :selection/selected #{:player-2})
          confirm-result (ability-events/confirm-ability-target (:db result) selection-with-target)
          gy-after (th/get-zone-count (:db confirm-result) :graveyard :player-1)]
      (is (= (inc gy-before) gy-after)
          "Graveyard count should increase by 1 (sacrificed Bauble)"))))


;; Edge case: Cannot activate from hand
(deftest urzas-bauble-cannot-activate-from-hand-test
  (testing "Urza's Bauble cannot activate ability from hand"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db bauble-id] (th/add-card-to-zone db :urzas-bauble :hand :player-1)
          result (ability-events/activate-ability db :player-1 bauble-id 0)]
      (is (nil? (:pending-selection result))
          "Should not be able to activate from hand"))))


;; Edge case: Resolve description includes revealed card name
(deftest urzas-bauble-resolve-description-shows-revealed-card-test
  (testing "Resolve description includes revealed card name in history log"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db bauble-id] (th/add-card-to-zone db :urzas-bauble :battlefield :player-1)
          [db _hand-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          ;; Activate ability, target opponent
          result (ability-events/activate-ability db :player-1 bauble-id 0)
          sel (:pending-selection result)
          selection-with-target (assoc sel :selection/selected #{:player-2})
          confirm-result (ability-events/confirm-ability-target (:db result) selection-with-target)
          pre-game-db (:db confirm-result)
          ;; Resolve — this executes :peek-random-hand which stores the result
          resolve-result (resolution/resolve-one-item pre-game-db)
          game-db-after (:db resolve-result)
          ;; Generate description using the same function the history interceptor uses
          event [:fizzle.events.resolution/resolve-top]
          desc (descriptions/describe-event event pre-game-db game-db-after)]
      (is (re-find #"Resolve Urza's Bauble ability" desc)
          "Description should mention the card")
      (is (re-find #"revealed Dark Ritual" desc)
          "Description should include the revealed card name"))))


;; Edge case: Stale peek-result is cleared between resolutions
(deftest urzas-bauble-peek-result-cleared-between-resolutions-test
  (testing "peek-result from previous resolution does not leak into next"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Set up a fake peek-result as if a previous resolve set it
          game-eid (d/q '[:find ?g . :where [?g :game/id _]] db)
          db (d/db-with db [[:db/add game-eid :game/peek-result "Stale Card"]])
          ;; Add a Dark Ritual to hand and cast it
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (rules/cast-spell db :player-1 ritual-id)
          ;; Resolve Dark Ritual — should clear the stale peek-result
          result (resolution/resolve-one-item db)]
      (is (nil? (:game/peek-result (q/get-game-state (:db result))))
          "peek-result should be cleared after resolving a non-peek spell"))))


;; Edge case: Empty hand peek still grants delayed draw
(deftest urzas-bauble-empty-hand-still-grants-draw-test
  (testing "Empty hand: peek does nothing, but delayed draw is still granted"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db bauble-id] (th/add-card-to-zone db :urzas-bauble :battlefield :player-1)
          ;; Opponent has no cards in hand
          _ (is (= 0 (th/get-zone-count db :hand :player-2))
                "Precondition: opponent has empty hand")
          ;; Activate, target opponent
          result (ability-events/activate-ability db :player-1 bauble-id 0)
          sel (:pending-selection result)
          selection-with-target (assoc sel :selection/selected #{:player-2})
          confirm-result (ability-events/confirm-ability-target (:db result) selection-with-target)
          db-resolved (:db (resolution/resolve-one-item (:db confirm-result)))]
      ;; Delayed draw should still be granted even though hand was empty
      (let [player-grants (grants/get-player-grants db-resolved :player-1)
            draw-grants (filter #(= :delayed-effect (:grant/type %)) player-grants)]
        (is (= 1 (count draw-grants))
            "Player-1 should have exactly 1 delayed-draw grant even when target's hand was empty")))))
