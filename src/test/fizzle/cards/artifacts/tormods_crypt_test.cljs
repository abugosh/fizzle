(ns fizzle.cards.artifacts.tormods-crypt-test
  "Tests for Tormod's Crypt card.

   Tormod's Crypt: 0 - Artifact
   {T}, Sacrifice this artifact: Exile target player's graveyard.

   Rulings:
   - Targets a player, not specific cards in the graveyard.
   - Can target any player, including yourself.
   - All cards in the targeted player's graveyard are exiled."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.artifacts.tormods-crypt :as tormods-crypt]
    [fizzle.db.queries :as q]
    [fizzle.engine.abilities :as abilities]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

;; Oracle: "Tormod's Crypt" — verified against Scryfall
(deftest tormods-crypt-card-definition-test
  (testing "card has correct oracle properties"
    (let [card tormods-crypt/card]
      (is (= :tormods-crypt (:card/id card))
          "Card ID should be :tormods-crypt")
      (is (= "Tormod's Crypt" (:card/name card))
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
    (let [abilities (:card/abilities tormods-crypt/card)]
      (is (= 1 (count abilities))
          "Should have exactly one ability")
      (let [ability (first abilities)]
        (is (= :activated (:ability/type ability))
            "Ability should be :activated (not :mana)")
        (is (true? (:tap (:ability/cost ability)))
            "Ability cost should include tap")
        (is (true? (:sacrifice-self (:ability/cost ability)))
            "Ability cost should include sacrifice-self")
        (is (= "Exile target player's graveyard" (:ability/description ability))
            "Ability description should match oracle"))))

  (testing "ability has correct targeting"
    (let [ability (first (:card/abilities tormods-crypt/card))
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

  (testing "ability has exile-zone effect"
    (let [ability (first (:card/abilities tormods-crypt/card))
          effects (:ability/effects ability)]
      (is (= 1 (count effects))
          "Should have exactly one effect")
      (let [effect (first effects)]
        (is (= :exile-zone (:effect/type effect))
            "Effect type should be :exile-zone")
        (is (= :player (:effect/target-ref effect))
            "Effect should reference :player target")
        (is (= :graveyard (:effect/zone effect))
            "Effect should target graveyard zone")))))


;; === B. Cast-Resolve Happy Path ===

;; Oracle: "{T}, Sacrifice this artifact: Exile target player's graveyard."
(deftest tormods-crypt-cast-to-battlefield-test
  (testing "Tormod's Crypt enters the battlefield when cast (0-cost artifact)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :tormods-crypt :hand :player-1)
          _ (is (true? (rules/can-cast? db :player-1 obj-id))
                "Should be castable with 0 mana")
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= :battlefield (:object/zone (q/get-object db-resolved obj-id)))
          "Tormod's Crypt should be on battlefield after resolution"))))


;; Oracle: "Exile target player's graveyard."
(deftest tormods-crypt-activate-exile-graveyard-test
  (testing "Activating exiles target player's entire graveyard"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Put Tormod's Crypt on battlefield
          [db crypt-id] (th/add-card-to-zone db :tormods-crypt :battlefield :player-1)
          ;; Put cards in opponent's graveyard
          [db gy-ids] (th/add-cards-to-graveyard db [:dark-ritual :dark-ritual :cabal-ritual] :player-2)
          gy-before (th/get-zone-count db :graveyard :player-2)
          _ (is (= 3 gy-before)
                "Precondition: opponent has 3 cards in graveyard")
          ;; Verify ability shows as activatable through production path
          ability (first (:card/abilities tormods-crypt/card))
          _ (is (true? (abilities/can-activate? db crypt-id ability :player-1))
                "Ability should be activatable on untapped battlefield permanent")
          ;; Activate ability (index 0)
          result (ability-events/activate-ability db :player-1 crypt-id 0)
          sel (:pending-selection result)]
      ;; Should have pending selection for player target
      (is (= :ability-targeting (:selection/type sel))
          "Should enter targeting selection")
      ;; Select opponent as target
      (let [selection-with-target (assoc sel :selection/selected #{:player-2})
            confirm-result (ability-events/confirm-ability-target (:db result) selection-with-target)
            db-after-confirm (:db confirm-result)]
        ;; Crypt should be sacrificed (cost paid)
        (is (= :graveyard (:object/zone (q/get-object db-after-confirm crypt-id)))
            "Tormod's Crypt should be in graveyard after sacrifice")
        ;; Stack should have the ability
        (let [top-item (stack/get-top-stack-item db-after-confirm)]
          (is (= :activated-ability (:stack-item/type top-item))
              "Stack item should be activated ability type")
          ;; Resolve the ability
          (let [db-resolved (:db (resolution/resolve-one-item db-after-confirm))]
            ;; All opponent graveyard cards should be in exile
            (is (= 0 (th/get-zone-count db-resolved :graveyard :player-2))
                "Opponent should have 0 cards in graveyard after resolution")
            ;; Cards should be in exile
            (doseq [gy-id gy-ids]
              (is (= :exile (th/get-object-zone db-resolved gy-id))
                  "Each graveyard card should be in exile"))
            ;; Note: Tormod's Crypt itself was sacrificed as cost, so it's
            ;; in player-1's graveyard, NOT exiled (it was already in GY
            ;; before the ability resolved)
            (is (= :graveyard (th/get-object-zone db-resolved crypt-id))
                "Tormod's Crypt should remain in player-1's graveyard")))))))


;; === C. Cannot-Cast Guards ===

(deftest tormods-crypt-cannot-cast-from-graveyard-test
  (testing "Cannot cast Tormod's Crypt from graveyard (no flashback)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :tormods-crypt :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard"))))


(deftest tormods-crypt-cannot-activate-when-tapped-test
  (testing "Cannot activate Tormod's Crypt when already tapped"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db crypt-id] (th/add-card-to-zone db :tormods-crypt :battlefield :player-1)
          ;; Manually tap it
          obj-eid (q/get-object-eid db crypt-id)
          db-tapped (d/db-with db [[:db/add obj-eid :object/tapped true]])
          result (ability-events/activate-ability db-tapped :player-1 crypt-id 0)]
      (is (nil? (:pending-selection result))
          "Should not enter targeting selection when tapped"))))


;; === D. Storm Count ===

(deftest tormods-crypt-increments-storm-count-test
  (testing "Casting Tormod's Crypt increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :tormods-crypt :hand :player-1)
          storm-before (q/get-storm-count db :player-1)
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= (inc storm-before) (q/get-storm-count db-resolved :player-1))
          "Storm count should increment by 1"))))


;; === E. Targeting Tests ===

;; Oracle: "target player's graveyard" — can target any player
(deftest tormods-crypt-can-target-self-test
  (testing "Can target self's graveyard"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db crypt-id] (th/add-card-to-zone db :tormods-crypt :battlefield :player-1)
          [db gy-ids] (th/add-cards-to-graveyard db [:dark-ritual :dark-ritual] :player-1)
          _ (is (= 2 (th/get-zone-count db :graveyard :player-1))
                "Precondition: player-1 has 2 cards in graveyard")
          result (ability-events/activate-ability db :player-1 crypt-id 0)
          sel (:pending-selection result)
          ;; Target SELF
          selection-with-target (assoc sel :selection/selected #{:player-1})
          confirm-result (ability-events/confirm-ability-target (:db result) selection-with-target)
          db-resolved (:db (resolution/resolve-one-item (:db confirm-result)))]
      ;; Player-1's graveyard should be exiled (except Crypt itself which
      ;; was sacrificed AFTER targeting and goes to player-1's GY)
      (doseq [gy-id gy-ids]
        (is (= :exile (th/get-object-zone db-resolved gy-id))
            "Each graveyard card should be in exile")))))


(deftest tormods-crypt-has-valid-targets-test
  (testing "has-valid-targets? returns true when players exist"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ability (first (:card/abilities tormods-crypt/card))
          target-req (first (:ability/targeting ability))]
      (is (= 2 (count (targeting/find-valid-targets db :player-1 target-req)))
          "Should find both players as valid targets"))))


;; === F. exile-zone Effect Tests ===

(deftest exile-zone-effect-exiles-all-cards-in-zone-test
  (testing "exile-zone moves all cards from target zone to exile"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db gy-ids] (th/add-cards-to-graveyard db [:dark-ritual :cabal-ritual :dark-ritual] :player-2)
          effect {:effect/type :exile-zone
                  :effect/target :player-2
                  :effect/zone :graveyard}
          db-after (effects/execute-effect db :player-1 effect)]
      (is (= 0 (th/get-zone-count db-after :graveyard :player-2))
          "Target graveyard should be empty")
      (doseq [gy-id gy-ids]
        (is (= :exile (th/get-object-zone db-after gy-id))
            "Each card should be in exile")))))


(deftest exile-zone-effect-empty-graveyard-noop-test
  (testing "exile-zone on empty graveyard is a no-op"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          _ (is (= 0 (th/get-zone-count db :graveyard :player-2))
                "Precondition: opponent graveyard is empty")
          effect {:effect/type :exile-zone
                  :effect/target :player-2
                  :effect/zone :graveyard}
          db-after (effects/execute-effect db :player-1 effect)]
      (is (= 0 (th/get-zone-count db-after :graveyard :player-2))
          "Graveyard should still be empty"))))


(deftest exile-zone-effect-invalid-player-noop-test
  (testing "exile-zone with invalid player is a no-op"
    (let [db (th/create-test-db)
          effect {:effect/type :exile-zone
                  :effect/target :nonexistent-player
                  :effect/zone :graveyard}
          db-after (effects/execute-effect db :player-1 effect)]
      (is (= db db-after)
          "DB should be unchanged for invalid player"))))


;; === G. Edge Cases ===

;; Tormod's Crypt only exiles the targeted player's graveyard, not the other player's
(deftest tormods-crypt-only-exiles-targeted-player-test
  (testing "Only the targeted player's graveyard is exiled"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Both players have graveyard cards
          [db _p1-gy] (th/add-cards-to-graveyard db [:dark-ritual] :player-1)
          [db _p2-gy] (th/add-cards-to-graveyard db [:dark-ritual :cabal-ritual] :player-2)
          _ (is (= 1 (th/get-zone-count db :graveyard :player-1))
                "Precondition: player-1 has 1 card in graveyard")
          _ (is (= 2 (th/get-zone-count db :graveyard :player-2))
                "Precondition: player-2 has 2 cards in graveyard")
          effect {:effect/type :exile-zone
                  :effect/target :player-2
                  :effect/zone :graveyard}
          db-after (effects/execute-effect db :player-1 effect)]
      ;; Player-2 graveyard exiled
      (is (= 0 (th/get-zone-count db-after :graveyard :player-2))
          "Targeted player's graveyard should be empty")
      ;; Player-1 graveyard untouched
      (is (= 1 (th/get-zone-count db-after :graveyard :player-1))
          "Non-targeted player's graveyard should be unchanged"))))


(deftest tormods-crypt-cannot-activate-from-hand-test
  (testing "Tormod's Crypt cannot activate ability from hand"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db crypt-id] (th/add-card-to-zone db :tormods-crypt :hand :player-1)
          result (ability-events/activate-ability db :player-1 crypt-id 0)]
      (is (nil? (:pending-selection result))
          "Should not be able to activate from hand"))))
