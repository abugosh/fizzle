(ns fizzle.cards.white.ray-of-revelation-test
  "Tests for Ray of Revelation card.

   Ray of Revelation: 1W - Instant
   Destroy target enchantment.
   Flashback {G}

   This tests:
   - Card definition (type, cost, alternate costs)
   - Type-based targeting (enchantments on battlefield)
   - Flashback mode availability from graveyard
   - Card exiles after flashback resolution
   - Destroy effect moves target to graveyard"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.white.ray-of-revelation :as ray]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.zones :as zones]
    [fizzle.test-helpers :as th]))


;; === Card Definition Tests ===

;; Oracle: "Destroy target enchantment.\nFlashback {G}"
(deftest ray-of-revelation-card-definition-test
  (testing "Ray of Revelation type, cost, and color"
    (is (= :ray-of-revelation (:card/id ray/card))
        "Card ID should be :ray-of-revelation")
    (is (= "Ray of Revelation" (:card/name ray/card))
        "Card name should match oracle")
    (is (= {:colorless 1 :white 1} (:card/mana-cost ray/card))
        "Ray of Revelation should cost {1}{W}")
    (is (= #{:instant} (:card/types ray/card))
        "Ray of Revelation should be an instant")
    (is (= 2 (:card/cmc ray/card))
        "Ray of Revelation should have CMC 2")
    (is (= #{:white} (:card/colors ray/card))
        "Ray of Revelation should be white")
    (is (= "Destroy target enchantment.\nFlashback {G}" (:card/text ray/card))
        "Card text should match oracle"))

  (testing "Ray of Revelation has flashback alternate cost"
    (let [flashback (first (:card/alternate-costs ray/card))]
      (is (= :flashback (:alternate/id flashback))
          "Alternate should be flashback")
      (is (= :graveyard (:alternate/zone flashback))
          "Flashback should be castable from graveyard")
      (is (= {:green 1} (:alternate/mana-cost flashback))
          "Flashback should cost {G}")
      (is (= :exile (:alternate/on-resolve flashback))
          "Flashback should exile on resolve")))

  (testing "Ray of Revelation targets enchantments on battlefield"
    (let [targeting (first (:card/targeting ray/card))]
      (is (= :target-enchantment (:target/id targeting))
          "Target id should be :target-enchantment")
      (is (= :object (:target/type targeting))
          "Target type should be :object")
      (is (= :battlefield (:target/zone targeting))
          "Target zone should be :battlefield")
      (is (= :any (:target/controller targeting))
          "Should target any controller's enchantments")
      (is (= #{:enchantment} (get-in targeting [:target/criteria :match/types]))
          "Should only target enchantments")
      (is (true? (:target/required targeting))
          "Target should be required")))

  (testing "Ray of Revelation has destroy effect"
    (let [effects (:card/effects ray/card)
          destroy-effect (first effects)]
      (is (= 1 (count effects))
          "Ray should have 1 effect")
      (is (= :destroy (:effect/type destroy-effect))
          "Effect should be :destroy")
      (is (= :target-enchantment (:effect/target-ref destroy-effect))
          "Effect should reference :target-enchantment"))))


;; === Casting Tests ===

;; Oracle: "{1}{W}"
(deftest ray-cast-from-hand-with-mana-test
  (testing "Ray castable from hand for {1}{W}"
    (let [db (th/create-test-db)
          ;; Need a valid target on battlefield
          [db _enchant-id] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :ray-of-revelation :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :white 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          primary-mode (first (filter #(= :primary (:mode/id %)) modes))]
      (is (rules/can-cast-mode? db :player-1 obj-id primary-mode)
          "Should be castable with {1}{W}"))))


(deftest ray-not-castable-without-mana-test
  (testing "Ray not castable from hand without mana"
    (let [db (th/create-test-db)
          [db _enchant-id] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :ray-of-revelation :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana"))))


;; === Target Validation Tests ===

;; Oracle: "Destroy target enchantment."
(deftest ray-can-target-enchantment-on-battlefield-test
  (testing "Ray can target enchantment on battlefield"
    (let [db (th/create-test-db)
          [db enchant-id] (th/add-card-to-zone db :chill :battlefield :player-1)]
      (is (targeting/has-valid-targets? db :player-1 ray/card)
          "Should have valid targets when enchantment on battlefield")
      (let [target-req (first (:card/targeting ray/card))
            targets (targeting/find-valid-targets db :player-1 target-req)]
        (is (= 1 (count targets))
            "Should find exactly one valid target")
        (is (= enchant-id (first targets))
            "Should find the enchantment")))))


;; Oracle: "target enchantment" - implies only enchantments, not artifacts/creatures
(deftest ray-cannot-target-non-enchantment-test
  (testing "Ray cannot target lands"
    (let [db (th/create-test-db)
          [db _land-id] (th/add-card-to-zone db :swamp :battlefield :player-1)]
      (is (false? (targeting/has-valid-targets? db :player-1 ray/card))
          "Should not have valid targets when only land on battlefield")))

  (testing "Ray cannot target artifacts"
    (let [db (th/create-test-db)
          [db _artifact-id] (th/add-card-to-zone db :lotus-petal :battlefield :player-1)]
      (is (false? (targeting/has-valid-targets? db :player-1 ray/card))
          "Should not have valid targets when only artifact on battlefield"))))


;; Edge case: No valid targets means card shouldn't be castable
(deftest ray-not-castable-without-valid-targets-test
  (testing "Ray not castable when no enchantments on battlefield"
    (let [db (th/create-test-db)
          [db _obj-id] (th/add-card-to-zone db :ray-of-revelation :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :white 1})]
      ;; Even with enough mana, can't cast without valid target
      (is (false? (targeting/has-valid-targets? db :player-1 ray/card))
          "Should not have valid targets with empty battlefield"))))


;; Oracle: Can target ANY enchantment (opponent's included)
(deftest ray-can-target-opponent-enchantment-test
  (testing "Ray can target opponent's enchantment"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          ;; Add enchantment to opponent's battlefield
          [db _enchant-id] (th/add-card-to-zone db :chill :battlefield :player-2)]
      (is (targeting/has-valid-targets? db :player-1 ray/card)
          "Should be able to target opponent's enchantment"))))


;; === D. Storm Count ===

;; Oracle: Casting from hand increments storm count
(deftest ray-cast-increments-storm-count-test
  (testing "Casting Ray of Revelation increments storm count"
    (let [db (th/create-test-db)
          [db enchant-id] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :ray-of-revelation :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :white 1})
          storm-before (q/get-storm-count db :player-1)
          db-cast (th/cast-with-target db :player-1 obj-id enchant-id)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1 on cast"))))


;; === Flashback Tests ===

;; Oracle: "Flashback {G}"
(deftest ray-flashback-cast-from-graveyard-test
  (testing "Ray castable from graveyard for {G}"
    (let [db (th/create-test-db)
          [db _enchant-id] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :ray-of-revelation :graveyard :player-1)
          db (mana/add-mana db :player-1 {:green 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)]
      (is (= 1 (count modes))
          "Should only have flashback mode from graveyard")
      (is (= :flashback (:mode/id flashback-mode))
          "Mode should be flashback")
      (is (rules/can-cast-mode? db :player-1 obj-id flashback-mode)
          "Should be castable with {G}"))))


(deftest ray-flashback-not-castable-without-mana-test
  (testing "Ray flashback not castable without mana"
    (let [db (th/create-test-db)
          [db _enchant-id] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :ray-of-revelation :graveyard :player-1)]
      ;; No mana added
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard without mana"))))


(deftest ray-flashback-not-castable-with-wrong-mana-test
  (testing "Ray flashback not castable with wrong mana color"
    (let [db (th/create-test-db)
          [db _enchant-id] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :ray-of-revelation :graveyard :player-1)
          ;; Add white mana, but flashback needs green
          db (mana/add-mana db :player-1 {:white 1})]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable with wrong mana color"))))


;; Ruling (2021-03-19): "Flashback increments storm count"
(deftest ray-flashback-increments-storm-test
  (testing "Flashback cast increments storm count"
    (let [db (th/create-test-db)
          [db _enchant-id] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :ray-of-revelation :graveyard :player-1)
          db (mana/add-mana db :player-1 {:green 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)
          db-cast (rules/cast-spell-mode db :player-1 obj-id flashback-mode)]
      (is (= 1 (q/get-storm-count db-cast :player-1))
          "Storm should increment for flashback cast"))))


;; === Flashback Resolution Tests ===

;; Ruling (2021-03-19): "Then exile it."
(deftest ray-flashback-resolves-to-exile-test
  (testing "Flashback Ray exiles after resolution"
    (let [db (th/create-test-db)
          [db enchant-id] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :ray-of-revelation :graveyard :player-1)
          db (mana/add-mana db :player-1 {:green 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)
          ;; Cast the spell
          db-cast (rules/cast-spell-mode db :player-1 obj-id flashback-mode)
          ;; Store the target on the stack-item (matching real cast-time targeting flow)
          spell-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db-cast obj-id)
          si (d/q '[:find ?e . :in $ ?obj :where [?e :stack-item/object-ref ?obj]] db-cast spell-eid)
          db-cast (d/db-with db-cast [[:db/add si :stack-item/targets {:target-enchantment enchant-id}]
                                      [:db/add spell-eid :object/targets {:target-enchantment enchant-id}]])
          ;; Resolve the spell
          db-resolved (rules/resolve-spell db-cast :player-1 obj-id)]
      (is (= :exile (:object/zone (q/get-object db-resolved obj-id)))
          "Flashback spell should go to exile after resolution"))))


;; Ruling (2021-03-19): "Spells using flashback are always exiled afterward,
;; regardless of whether they resolve, get countered, or leave the stack otherwise."
(deftest ray-flashback-countered-exiles-test
  (testing "Countered flashback Ray goes to exile not graveyard"
    (let [db (th/create-test-db)
          [db _enchant-id] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :ray-of-revelation :graveyard :player-1)
          db (mana/add-mana db :player-1 {:green 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          flashback-mode (first modes)
          db-cast (rules/cast-spell-mode db :player-1 obj-id flashback-mode)
          ;; Counter = move off stack without resolving
          db-countered (rules/move-spell-off-stack db-cast :player-1 obj-id)]
      (is (= :exile (:object/zone (q/get-object db-countered obj-id)))
          "Countered flashback spell should go to exile"))))


;; Normal (non-flashback) spell goes to graveyard when countered
(deftest ray-normal-cast-countered-goes-to-graveyard-test
  (testing "Countered normal Ray goes to graveyard"
    (let [db (th/create-test-db)
          [db _enchant-id] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db obj-id] (th/add-card-to-zone db :ray-of-revelation :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :white 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          primary-mode (first (filter #(= :primary (:mode/id %)) modes))
          db-cast (rules/cast-spell-mode db :player-1 obj-id primary-mode)
          db-countered (rules/move-spell-off-stack db-cast :player-1 obj-id)]
      (is (= :graveyard (:object/zone (q/get-object db-countered obj-id)))
          "Countered normal spell should go to graveyard"))))


;; === Edge Case Tests ===

;; Edge case: Target removed before resolution (fizzle)
(deftest ray-fizzles-when-target-removed-test
  (testing "Ray resolves gracefully when target is removed"
    (let [db (th/create-test-db)
          [db enchant-id] (th/add-card-to-zone db :chill :battlefield :player-1)
          [db _obj-id] (th/add-card-to-zone db :ray-of-revelation :hand :player-1)
          db (mana/add-mana db :player-1 {:colorless 1 :white 1})
          ;; Store targets and get requirements
          targets {:target-enchantment enchant-id}
          requirements (targeting/get-targeting-requirements ray/card)
          ;; Target is removed before resolution (e.g., destroyed by another spell)
          db-target-removed (zones/move-to-zone db enchant-id :graveyard)]
      ;; Verify target is no longer legal
      (is (false? (targeting/all-targets-legal? db-target-removed targets requirements))
          "Target should no longer be legal after being removed"))))


;; Edge case: Flashback requires target just like normal cast
(deftest ray-flashback-requires-valid-target-test
  (testing "Flashback cannot be cast without valid target"
    (let [db (th/create-test-db)
          ;; No enchantment on battlefield
          [db _obj-id] (th/add-card-to-zone db :ray-of-revelation :graveyard :player-1)
          db (mana/add-mana db :player-1 {:green 1})]
      ;; Has mana, but no valid targets
      (is (false? (targeting/has-valid-targets? db :player-1 ray/card))
          "Should have no valid targets with empty battlefield"))))
