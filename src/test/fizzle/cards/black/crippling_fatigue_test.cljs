(ns fizzle.cards.black.crippling-fatigue-test
  "Tests for Crippling Fatigue card.

   Crippling Fatigue: {1}{B}{B} - Sorcery
   Target creature gets -2/-2 until end of turn.
   Flashback—{1}{B}, Pay 3 life.

   Key behaviors:
   - Targeted creature gets -2/-2 as a :pt-modifier grant until EOT
   - Creature with toughness <= 2 dies from SBA after -2/-2
   - Flashback from graveyard for {1}{B} + pay 3 life
   - Flashback spell is exiled after use"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.black.crippling-fatigue :as crippling-fatigue]
    [fizzle.db.queries :as q]
    [fizzle.engine.creatures :as creatures]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.state-based :as sba]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.zones :as zones]
    [fizzle.test-helpers :as th]))


;; === Helpers ===

(defn- add-creature-to-battlefield
  "Add a creature card to the battlefield with proper creature fields set."
  [db card-id owner]
  (let [[db obj-id] (th/add-card-to-zone db card-id :hand owner)
        db (zones/move-to-zone db obj-id :battlefield)]
    [db obj-id]))


;; === A. Card Definition Tests ===

(deftest crippling-fatigue-card-definition-test
  (testing "card has correct oracle properties"
    (let [card crippling-fatigue/card]
      (is (= :crippling-fatigue (:card/id card))
          "Card ID should be :crippling-fatigue")
      (is (= "Crippling Fatigue" (:card/name card))
          "Card name should match oracle")
      (is (= 3 (:card/cmc card))
          "CMC should be 3")
      (is (= {:colorless 1 :black 2} (:card/mana-cost card))
          "Mana cost should be {1}{B}{B}")
      (is (= #{:black} (:card/colors card))
          "Card should be black")
      (is (= #{:sorcery} (:card/types card))
          "Card should be a sorcery")
      (is (= "Target creature gets -2/-2 until end of turn. Flashback\u2014{1}{B}, Pay 3 life."
             (:card/text card))
          "Card text should match oracle")))

  (testing "card has correct targeting"
    (let [targeting (:card/targeting crippling-fatigue/card)]
      (is (vector? targeting)
          "Targeting must be a vector")
      (is (= 1 (count targeting))
          "Should have exactly one target requirement")
      (let [req (first targeting)]
        (is (= :target-creature (:target/id req))
            "Target ID should be :target-creature")
        (is (= :object (:target/type req))
            "Target type should be :object")
        (is (= :battlefield (:target/zone req))
            "Target zone should be :battlefield")
        (is (= :any (:target/controller req))
            "Should be able to target any controller's creature")
        (is (= {:match/types #{:creature}} (:target/criteria req))
            "Should only target creatures")
        (is (true? (:target/required req))
            "Target should be required"))))

  (testing "card has correct effect"
    (let [effects (:card/effects crippling-fatigue/card)]
      (is (= 1 (count effects))
          "Should have exactly one effect")
      (let [effect (first effects)]
        (is (= :apply-pt-modifier (:effect/type effect))
            "Effect type should be :apply-pt-modifier")
        (is (= :target-creature (:effect/target-ref effect))
            "Effect should reference :target-creature")
        (is (= -2 (:effect/power effect))
            "Power modifier should be -2")
        (is (= -2 (:effect/toughness effect))
            "Toughness modifier should be -2"))))

  (testing "card has correct flashback alternate cost"
    (let [alts (:card/alternate-costs crippling-fatigue/card)]
      (is (= 1 (count alts))
          "Should have exactly one alternate cost")
      (let [flashback (first alts)]
        (is (= :flashback (:alternate/id flashback))
            "Alternate cost ID should be :flashback")
        (is (= :graveyard (:alternate/zone flashback))
            "Flashback should be castable from graveyard")
        (is (= {:colorless 1 :black 1} (:alternate/mana-cost flashback))
            "Flashback cost should be {1}{B}")
        (is (= [{:cost/type :pay-life :cost/amount 3}]
               (:alternate/additional-costs flashback))
            "Flashback should require paying 3 life")
        (is (= :exile (:alternate/on-resolve flashback))
            "Flashback should exile on resolve")))))


;; === B. Cast-Resolve Happy Path ===

(deftest crippling-fatigue-kills-creature-with-low-toughness-test
  (testing "Crippling Fatigue kills creature with toughness <= 2 via SBA"
    (let [db (th/create-test-db {:mana {:colorless 1 :black 2}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :crippling-fatigue :hand :player-1)
          ;; 2/2 creature: -2/-2 gives 0 toughness, SBA kills it
          [db creature-id] (th/add-test-creature db :player-2 2 2)
          db-cast (th/cast-with-target db :player-1 obj-id creature-id)
          {:keys [db]} (th/resolve-top db-cast)
          ;; SBAs fire via :db effect handler in production; call manually in tests
          db (sba/check-and-execute-sbas db)]
      ;; SBA detects creature with 0 toughness and moves it to graveyard.
      ;; After zones/move-to-zone to graveyard, object/power and object/toughness
      ;; are retracted; the grant stays on the object in graveyard.
      (is (= :graveyard (:object/zone (q/get-object db creature-id)))
          "2/2 creature killed by -2/-2 should be moved to graveyard by SBA"))))


(deftest crippling-fatigue-creature-survives-high-toughness-test
  (testing "Creature with toughness > 2 survives -2/-2 (remains on battlefield)"
    (let [db (th/create-test-db {:mana {:colorless 1 :black 2}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :crippling-fatigue :hand :player-1)
          ;; 4/4 creature: -2/-2 gives 2 toughness, survives
          [db creature-id] (th/add-test-creature db :player-2 4 4)
          db-cast (th/cast-with-target db :player-1 obj-id creature-id)
          {:keys [db]} (th/resolve-top db-cast)]
      (is (= :battlefield (:object/zone (q/get-object db creature-id)))
          "4/4 creature should survive -2/-2 (2 toughness remains)")
      (is (= 2 (creatures/effective-toughness db creature-id))
          "Effective toughness should be 2 after -2/-2"))))


(deftest crippling-fatigue-moves-to-graveyard-after-cast-test
  (testing "Crippling Fatigue moves to graveyard after resolution (not flashback)"
    (let [db (th/create-test-db {:mana {:colorless 1 :black 2}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :crippling-fatigue :hand :player-1)
          [db creature-id] (th/add-test-creature db :player-2 4 4)
          db-cast (th/cast-with-target db :player-1 obj-id creature-id)
          {:keys [db]} (th/resolve-top db-cast)]
      (is (= :graveyard (:object/zone (q/get-object db obj-id)))
          "Normal cast Crippling Fatigue should go to graveyard after resolution"))))


;; === C. Cannot-Cast Guards ===

(deftest crippling-fatigue-cannot-cast-without-mana-test
  (testing "Cannot cast Crippling Fatigue without {1}{B}{B} mana"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :crippling-fatigue :hand :player-1)
          [db _creature-id] (th/add-test-creature db :player-2 2 2)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana"))))


(deftest crippling-fatigue-cannot-cast-without-target-test
  (testing "Cannot cast Crippling Fatigue without valid creature target"
    (let [db (th/create-test-db {:mana {:colorless 1 :black 2}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :crippling-fatigue :hand :player-1)]
      ;; No creatures on battlefield
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without creature target"))))


(deftest crippling-fatigue-cannot-cast-from-graveyard-without-mana-test
  (testing "Cannot cast Crippling Fatigue from graveyard without flashback mana"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :crippling-fatigue :graveyard :player-1)
          [db _creature-id] (th/add-test-creature db :player-2 2 2)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard without mana"))))


;; === D. Storm Count ===

(deftest crippling-fatigue-increments-storm-count-test
  (testing "Casting Crippling Fatigue increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 1 :black 2}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :crippling-fatigue :hand :player-1)
          [db creature-id] (th/add-test-creature db :player-2 4 4)
          storm-before (q/get-storm-count db :player-1)
          db-cast (th/cast-with-target db :player-1 obj-id creature-id)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === E. :apply-pt-modifier Effect Tests ===

(deftest apply-pt-modifier-creates-grant-on-creature-test
  (testing ":apply-pt-modifier effect creates :pt-modifier grant on target creature"
    (let [db (th/create-test-db)
          [db creature-id] (th/add-test-creature db :player-1 2 2)
          effect {:effect/type :apply-pt-modifier
                  :effect/target creature-id
                  :effect/power -2
                  :effect/toughness -2}
          db-after (effects/execute-effect db :player-1 effect)
          pt-grants (grants/get-grants-by-type db-after creature-id :pt-modifier)]
      (is (= 1 (count pt-grants))
          "Should have exactly one :pt-modifier grant")
      (let [grant (first pt-grants)]
        (is (= :pt-modifier (:grant/type grant))
            "Grant type should be :pt-modifier")
        (is (= -2 (get-in grant [:grant/data :grant/power]))
            "Grant power should be -2")
        (is (= -2 (get-in grant [:grant/data :grant/toughness]))
            "Grant toughness should be -2")))))


(deftest apply-pt-modifier-reads-effect-data-not-hardcoded-test
  (testing ":apply-pt-modifier reads power/toughness from effect data (not hardcoded)"
    (let [db (th/create-test-db)
          [db creature-id] (th/add-test-creature db :player-1 4 4)
          effect {:effect/type :apply-pt-modifier
                  :effect/target creature-id
                  :effect/power -1
                  :effect/toughness -3}
          db-after (effects/execute-effect db :player-1 effect)
          pt-grants (grants/get-grants-by-type db-after creature-id :pt-modifier)
          grant (first pt-grants)]
      (is (= -1 (get-in grant [:grant/data :grant/power]))
          "Grant power should match effect/power (-1)")
      (is (= -3 (get-in grant [:grant/data :grant/toughness]))
          "Grant toughness should match effect/toughness (-3)"))))


(deftest apply-pt-modifier-grant-has-eot-expiration-test
  (testing ":apply-pt-modifier grant expires at end of current turn cleanup"
    (let [db (th/create-test-db)
          [db creature-id] (th/add-test-creature db :player-1 2 2)
          game-state (q/get-game-state db)
          current-turn (:game/turn game-state)
          effect {:effect/type :apply-pt-modifier
                  :effect/target creature-id
                  :effect/power -2
                  :effect/toughness -2}
          db-after (effects/execute-effect db :player-1 effect)
          pt-grants (grants/get-grants-by-type db-after creature-id :pt-modifier)
          grant (first pt-grants)]
      (is (= current-turn (get-in grant [:grant/expires :expires/turn]))
          "Grant should expire on current turn")
      (is (= :cleanup (get-in grant [:grant/expires :expires/phase]))
          "Grant should expire at cleanup phase"))))


(deftest apply-pt-modifier-effective-toughness-reflects-modifier-test
  (testing "effective-toughness reflects -2/-2 modifier from grant"
    (let [db (th/create-test-db)
          [db creature-id] (th/add-test-creature db :player-1 4 4)
          toughness-before (creatures/effective-toughness db creature-id)
          effect {:effect/type :apply-pt-modifier
                  :effect/target creature-id
                  :effect/power -2
                  :effect/toughness -2}
          db-after (effects/execute-effect db :player-1 effect)]
      (is (= 4 toughness-before)
          "Test creature has base toughness 4")
      (is (= 2 (creatures/effective-toughness db-after creature-id))
          "Effective toughness should be 2 after -2/-2 modifier"))))


(deftest apply-pt-modifier-no-target-noop-test
  (testing ":apply-pt-modifier with nil target is a no-op"
    (let [db (th/create-test-db)
          effect {:effect/type :apply-pt-modifier
                  :effect/target nil
                  :effect/power -2
                  :effect/toughness -2}
          db-after (effects/execute-effect db :player-1 effect)]
      (is (= db db-after)
          "DB should be unchanged with nil target"))))


(deftest apply-pt-modifier-nonexistent-target-noop-test
  (testing ":apply-pt-modifier with nonexistent target is a no-op"
    (let [db (th/create-test-db)
          effect {:effect/type :apply-pt-modifier
                  :effect/target (random-uuid)
                  :effect/power -2
                  :effect/toughness -2}
          db-after (effects/execute-effect db :player-1 effect)]
      (is (= db db-after)
          "DB should be unchanged with nonexistent target"))))


;; === F. Targeting Tests ===

(deftest crippling-fatigue-targets-only-creatures-test
  (testing "Can only target creatures, not artifacts or other types"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db _petal-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-2)
          target-req (first (:card/targeting crippling-fatigue/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (empty? targets)
          "Should not find artifact as valid target"))))


(deftest crippling-fatigue-can-target-own-creature-test
  (testing "Can target own creatures"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          ;; Xantid Swarm is 0/1, targetable (no shroud)
          [db creature-id] (add-creature-to-battlefield db :xantid-swarm :player-1)
          target-req (first (:card/targeting crippling-fatigue/card))
          targets (targeting/find-valid-targets db :player-1 target-req)]
      (is (= 1 (count targets))
          "Should find own creature as valid target")
      (is (= creature-id (first targets))
          "Should find Xantid Swarm"))))


(deftest crippling-fatigue-no-targets-when-no-creatures-test
  (testing "has-valid-targets? returns false when no creatures on battlefield"
    (let [db (th/create-test-db)
          db (th/add-opponent db)]
      (is (false? (targeting/has-valid-targets? db :player-1 crippling-fatigue/card))
          "Should have no valid targets"))))


;; === G. SBA Integration Tests ===

(deftest sba-zero-toughness-creature-dies-after-modifier-test
  (testing "Creature with toughness exactly 0 after modifier dies via SBA"
    (let [db (th/create-test-db)
          ;; 2/2 creature + -2/-2 = 0 toughness
          [db creature-id] (th/add-test-creature db :player-1 2 2)
          effect {:effect/type :apply-pt-modifier
                  :effect/target creature-id
                  :effect/power -2
                  :effect/toughness -2}
          db-with-grant (effects/execute-effect db :player-1 effect)
          _ (is (= 0 (creatures/effective-toughness db-with-grant creature-id))
                "Precondition: effective toughness should be 0")
          db-after-sba (sba/check-and-execute-sbas db-with-grant)]
      (is (= :graveyard (:object/zone (q/get-object db-after-sba creature-id)))
          "Creature with 0 toughness should be moved to graveyard by SBA"))))


(deftest sba-negative-toughness-creature-dies-test
  (testing "Creature with negative toughness dies via SBA"
    (let [db (th/create-test-db)
          ;; 1/1 creature + -2/-2 = -1 toughness
          [db creature-id] (th/add-test-creature db :player-1 1 1)
          effect {:effect/type :apply-pt-modifier
                  :effect/target creature-id
                  :effect/power -2
                  :effect/toughness -2}
          db-with-grant (effects/execute-effect db :player-1 effect)
          _ (is (= -1 (creatures/effective-toughness db-with-grant creature-id))
                "Precondition: effective toughness should be -1")
          db-after-sba (sba/check-and-execute-sbas db-with-grant)]
      (is (= :graveyard (:object/zone (q/get-object db-after-sba creature-id)))
          "Creature with negative toughness should be moved to graveyard by SBA"))))


(deftest sba-dead-creature-goes-to-graveyard-not-exile-test
  (testing "SBA moves creature to graveyard, not exile"
    (let [db (th/create-test-db)
          [db creature-id] (th/add-test-creature db :player-1 2 2)
          effect {:effect/type :apply-pt-modifier
                  :effect/target creature-id
                  :effect/power -2
                  :effect/toughness -2}
          db-with-grant (effects/execute-effect db :player-1 effect)
          db-after-sba (sba/check-and-execute-sbas db-with-grant)]
      (is (= :graveyard (:object/zone (q/get-object db-after-sba creature-id)))
          "Dead creature should be in graveyard, not exile"))))


(deftest sba-creature-with-positive-toughness-survives-test
  (testing "Creature with positive toughness after modifier survives SBA check"
    (let [db (th/create-test-db)
          ;; 4/4 creature + -2/-2 = 2/2, positive toughness
          [db creature-id] (th/add-test-creature db :player-1 4 4)
          effect {:effect/type :apply-pt-modifier
                  :effect/target creature-id
                  :effect/power -2
                  :effect/toughness -2}
          db-with-grant (effects/execute-effect db :player-1 effect)
          _ (is (= 2 (creatures/effective-toughness db-with-grant creature-id))
                "Precondition: toughness 4 - 2 = 2")
          db-after-sba (sba/check-and-execute-sbas db-with-grant)]
      (is (= :battlefield (:object/zone (q/get-object db-after-sba creature-id)))
          "Creature with positive toughness should remain on battlefield"))))


(deftest sba-multiple-creatures-die-simultaneously-test
  (testing "Multiple creatures with 0 toughness all die from SBA check"
    (let [db (th/create-test-db)
          [db creature1-id] (th/add-test-creature db :player-1 2 2)
          [db creature2-id] (th/add-test-creature db :player-1 2 2)
          effect1 {:effect/type :apply-pt-modifier
                   :effect/target creature1-id
                   :effect/power -2
                   :effect/toughness -2}
          effect2 {:effect/type :apply-pt-modifier
                   :effect/target creature2-id
                   :effect/power -2
                   :effect/toughness -2}
          db-with-grants (-> db
                             (effects/execute-effect :player-1 effect1)
                             (effects/execute-effect :player-1 effect2))
          db-after-sba (sba/check-and-execute-sbas db-with-grants)]
      (is (= :graveyard (:object/zone (q/get-object db-after-sba creature1-id)))
          "First creature should be in graveyard")
      (is (= :graveyard (:object/zone (q/get-object db-after-sba creature2-id)))
          "Second creature should be in graveyard"))))


(deftest sba-non-creature-not-affected-by-zero-toughness-sba-test
  (testing "Non-creature permanent on battlefield is not moved by :zero-toughness SBA"
    (let [db (th/create-test-db)
          ;; Add a Lotus Petal (artifact, not a creature) to the battlefield
          [db artifact-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)
          db-after-sba (sba/check-and-execute-sbas db)]
      (is (= :battlefield (:object/zone (q/get-object db-after-sba artifact-id)))
          "Non-creature permanent should remain on battlefield after SBA check"))))


(deftest sba-token-creature-at-zero-toughness-dies-test
  (testing "Token creature with 0 effective toughness is moved to graveyard by SBA"
    (let [db (th/create-test-db)
          ;; Create a 2/2 Beast token on the battlefield
          token-effect {:effect/type :create-token
                        :effect/token {:token/name "Beast"
                                       :token/types #{:creature}
                                       :token/subtypes #{:beast}
                                       :token/colors #{:green}
                                       :token/power 2
                                       :token/toughness 2}}
          db-with-token (effects/execute-effect db :player-1 token-effect)
          ;; Find the token's object-id
          bf-objects (q/get-objects-in-zone db-with-token :player-1 :battlefield)
          token-obj (first (filter #(and (:object/is-token %) (= "Beast" (:card/name (:object/card %)))) bf-objects))
          token-id (:object/id token-obj)
          ;; Apply -2/-2 to bring toughness to 0
          pt-effect {:effect/type :apply-pt-modifier
                     :effect/target token-id
                     :effect/power -2
                     :effect/toughness -2}
          db-with-grant (effects/execute-effect db-with-token :player-1 pt-effect)
          db-after-sba (sba/check-and-execute-sbas db-with-grant)]
      ;; Token creatures cease to exist when they leave the battlefield (token-cleanup SBA).
      ;; The zero-toughness SBA moves the token to graveyard, then token-cleanup SBA
      ;; retracts the entity entirely. So the token is no longer in the db.
      (is (nil? (q/get-object db-after-sba token-id))
          "Token creature with 0 toughness ceases to exist (removed from db) after SBA"))))


;; === H. Flashback Tests ===

(deftest crippling-fatigue-flashback-available-from-graveyard-test
  (testing "Crippling Fatigue is castable via flashback from graveyard"
    (let [db (th/create-test-db {:life 20 :mana {:colorless 1 :black 1}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :crippling-fatigue :graveyard :player-1)
          [db _creature-id] (th/add-test-creature db :player-2 4 4)]
      (is (rules/can-cast? db :player-1 obj-id)
          "Should be castable from graveyard with {1}{B} + life available"))))


(deftest crippling-fatigue-flashback-not-available-without-life-test
  (testing "Crippling Fatigue flashback not castable without enough life"
    (let [db (th/create-test-db {:life 2 :mana {:colorless 1 :black 1}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :crippling-fatigue :graveyard :player-1)
          [db _creature-id] (th/add-test-creature db :player-2 4 4)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard with only 2 life"))))


(deftest crippling-fatigue-flashback-exiles-on-resolve-test
  (testing "Flashback Crippling Fatigue is exiled after resolution"
    (let [db (th/create-test-db {:life 20 :mana {:colorless 1 :black 1}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :crippling-fatigue :graveyard :player-1)
          [db creature-id] (th/add-test-creature db :player-2 4 4)
          db-cast (th/cast-with-target db :player-1 obj-id creature-id)
          {:keys [db]} (th/resolve-top db-cast)]
      (is (= :exile (:object/zone (q/get-object db obj-id)))
          "Flashback Crippling Fatigue should be exiled after resolution"))))


(deftest crippling-fatigue-flashback-pays-life-test
  (testing "Flashback Crippling Fatigue deducts 3 life from caster"
    (let [db (th/create-test-db {:life 20 :mana {:colorless 1 :black 1}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :crippling-fatigue :graveyard :player-1)
          [db creature-id] (th/add-test-creature db :player-2 4 4)
          life-before (q/get-life-total db :player-1)
          db-cast (th/cast-with-target db :player-1 obj-id creature-id)]
      (is (= (- life-before 3) (q/get-life-total db-cast :player-1))
          "Should have paid 3 life for flashback"))))
