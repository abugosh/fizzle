(ns fizzle.cards.black.vendetta-test
  "Tests for Vendetta card.

   Vendetta: {B} - Instant
   Destroy target nonblack creature. It can't be regenerated.
   You lose life equal to that creature's toughness.

   Key behaviors:
   - Targets nonblack creatures only (:match/not-colors #{:black})
   - Caster loses life equal to target creature's toughness at resolution
   - Life loss reads :card/toughness (persists after destroy)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.black.vendetta :as vendetta]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === Helpers ===

(defn- add-test-creature
  "Add a custom creature directly to the battlefield with given power/toughness and colors.
   Returns [db obj-id]."
  [db owner power toughness colors]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db owner)
        card-id (keyword (str "test-creature-" (random-uuid)))
        _ (d/transact! conn [{:card/id card-id
                              :card/name "Test Creature"
                              :card/types #{:creature}
                              :card/colors colors
                              :card/power power
                              :card/toughness toughness}])
        card-eid (d/q '[:find ?e . :in $ ?cid :where [?e :card/id ?cid]] @conn card-id)
        obj-id (random-uuid)
        _ (d/transact! conn [{:object/id obj-id
                              :object/card card-eid
                              :object/zone :battlefield
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/tapped false
                              :object/power power
                              :object/toughness toughness
                              :object/summoning-sick true
                              :object/damage-marked 0}])]
    [@conn obj-id]))


;; =====================================================
;; A. Card Definition
;; =====================================================

(deftest vendetta-card-definition-test
  (testing "Vendetta card definition has all required fields with exact values"
    (let [card vendetta/card]
      (is (= :vendetta (:card/id card)))
      (is (= "Vendetta" (:card/name card)))
      (is (= 1 (:card/cmc card)))
      (is (= {:black 1} (:card/mana-cost card)))
      (is (= #{:black} (:card/colors card)))
      (is (= #{:instant} (:card/types card)))
      (is (= "Destroy target nonblack creature. It can't be regenerated. You lose life equal to that creature's toughness."
             (:card/text card)))))

  (testing "Vendetta has correct targeting"
    (let [targeting (:card/targeting vendetta/card)]
      (is (vector? targeting))
      (is (= 1 (count targeting)))
      (let [req (first targeting)]
        (is (= :target-creature (:target/id req)))
        (is (= :object (:target/type req)))
        (is (= :battlefield (:target/zone req)))
        (is (= :any (:target/controller req)))
        (is (= {:match/types #{:creature}
                :match/not-colors #{:black}}
               (:target/criteria req)))
        (is (true? (:target/required req))))))

  (testing "Vendetta has two effects: destroy then lose-life-equal-to-toughness"
    (let [effects (:card/effects vendetta/card)]
      (is (= 2 (count effects)))
      (let [[destroy-effect life-effect] effects]
        (is (= :destroy (:effect/type destroy-effect)))
        (is (= :target-creature (:effect/target-ref destroy-effect)))
        (is (= :lose-life-equal-to-toughness (:effect/type life-effect)))
        (is (= :target-creature (:effect/target-ref life-effect)))))))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

(deftest vendetta-destroys-creature-and-caster-loses-life-test
  (testing "Vendetta destroys target nonblack creature and caster loses life equal to toughness"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db vendetta-id] (th/add-card-to-zone db :vendetta :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          ;; White creature with toughness 3
          [db creature-id] (add-test-creature db :player-2 2 3 #{:white})
          caster-life-before (q/get-life-total db :player-1)
          db-cast (th/cast-with-target db :player-1 vendetta-id creature-id)
          result (resolution/resolve-one-item db-cast)
          db-resolved (:db result)]
      ;; Creature should be destroyed (in graveyard)
      (is (= :graveyard (:object/zone (q/get-object db-resolved creature-id)))
          "Target creature should be in graveyard after Vendetta resolves")
      ;; Caster loses life equal to toughness (3)
      (is (= (- caster-life-before 3) (q/get-life-total db-resolved :player-1))
          "Caster should lose 3 life (equal to creature's toughness)"))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

(deftest vendetta-cannot-cast-without-mana-test
  (testing "Cannot cast Vendetta without black mana"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db vendetta-id] (th/add-card-to-zone db :vendetta :hand :player-1)
          [db _creature-id] (add-test-creature db :player-2 2 3 #{:white})]
      (is (false? (rules/can-cast? db :player-1 vendetta-id))
          "Should not be castable without mana"))))


(deftest vendetta-cannot-cast-without-targets-test
  (testing "Cannot cast Vendetta with no creatures on battlefield"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db vendetta-id] (th/add-card-to-zone db :vendetta :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})]
      (is (false? (rules/can-cast? db :player-1 vendetta-id))
          "Should not be castable without any creatures"))))


(deftest vendetta-cannot-cast-with-only-black-creatures-test
  (testing "Cannot cast Vendetta when only black creatures are on battlefield"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db vendetta-id] (th/add-card-to-zone db :vendetta :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          [db _creature-id] (add-test-creature db :player-2 2 2 #{:black})]
      (is (false? (rules/can-cast? db :player-1 vendetta-id))
          "Should not be castable when only black creatures are available"))))


;; =====================================================
;; D. Storm Count
;; =====================================================

(deftest vendetta-increments-storm-count-test
  (testing "Casting Vendetta increments storm count"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db vendetta-id] (th/add-card-to-zone db :vendetta :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          [db creature-id] (add-test-creature db :player-2 2 3 #{:white})
          storm-before (q/get-storm-count db :player-1)
          db-cast (th/cast-with-target db :player-1 vendetta-id creature-id)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1 when Vendetta is cast"))))


;; =====================================================
;; F. Targeting Tests
;; =====================================================

(deftest vendetta-cannot-target-black-creature-test
  (testing "Vendetta cannot target a black creature"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _black-creature-id] (add-test-creature db :player-2 2 2 #{:black})
          target-req (first (:card/targeting vendetta/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (empty? targets)
          "Black creature should not be a valid target"))))


(deftest vendetta-can-target-multicolor-nonblack-creature-test
  (testing "Vendetta can target a multicolor creature that is not black (e.g., red/green)"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db creature-id] (add-test-creature db :player-2 3 3 #{:red :green})
          target-req (first (:card/targeting vendetta/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Red/green creature should be a valid target")
      (is (= creature-id (first targets))
          "Should find the multicolor nonblack creature"))))


;; =====================================================
;; G. Edge Cases
;; =====================================================

(deftest vendetta-high-toughness-caster-loses-much-life-test
  (testing "Vendetta against 0/7 creature makes caster lose 7 life"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db vendetta-id] (th/add-card-to-zone db :vendetta :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          ;; 0/7 white creature
          [db creature-id] (add-test-creature db :player-2 0 7 #{:white})
          caster-life-before (q/get-life-total db :player-1)
          db-cast (th/cast-with-target db :player-1 vendetta-id creature-id)
          result (resolution/resolve-one-item db-cast)
          db-resolved (:db result)]
      (is (= (- caster-life-before 7) (q/get-life-total db-resolved :player-1))
          "Caster should lose 7 life for 0/7 creature"))))


(deftest vendetta-life-can-go-below-zero-test
  (testing "Vendetta life loss can reduce caster to negative life"
    (let [db (th/create-test-db {:life 2})
          db (th/add-opponent db)
          [db vendetta-id] (th/add-card-to-zone db :vendetta :hand :player-1)
          db (mana/add-mana db :player-1 {:black 1})
          ;; 2/5 creature — caster at 2 life loses 5 → -3
          [db creature-id] (add-test-creature db :player-2 2 5 #{:white})
          db-cast (th/cast-with-target db :player-1 vendetta-id creature-id)
          result (resolution/resolve-one-item db-cast)
          db-resolved (:db result)]
      (is (= -3 (q/get-life-total db-resolved :player-1))
          "Caster should be at -3 life (2 - 5 = -3)"))))
