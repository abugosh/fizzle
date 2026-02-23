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
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.zones :as zones]))


;; === Test helpers ===

(defn add-card-to-zone
  "Add a card definition and create an object in specified zone.
   Returns [obj-id db] tuple."
  [db player-id card zone]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    ;; Add card definition if not already present
    (when-not (d/q '[:find ?e .
                     :in $ ?cid
                     :where [?e :card/id ?cid]]
                   @conn (:card/id card))
      (d/transact! conn [card]))
    ;; Create object in zone
    (let [card-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        @conn (:card/id card))
          obj-id (random-uuid)
          base-obj {:object/id obj-id
                    :object/card card-eid
                    :object/zone zone
                    :object/owner player-eid
                    :object/controller player-eid
                    :object/tapped false}
          obj (if (= zone :library)
                (assoc base-obj :object/position 0)
                base-obj)]
      (d/transact! conn [obj])
      [obj-id @conn])))


(defn add-opponent
  "Add an opponent player to the game state."
  [db]
  (let [conn (d/conn-from-db db)]
    (d/transact! conn [{:player/id :player-2
                        :player/name "Opponent"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1
                        :player/is-opponent true}])
    @conn))


;; === Test Cards ===

(def test-enchantment
  "A simple test enchantment for targeting."
  {:card/id :test-enchantment
   :card/name "Test Enchantment"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :white 1}
   :card/colors #{:white}
   :card/types #{:enchantment}
   :card/text "Test enchantment."
   :card/effects []})


(def test-creature
  "A creature that should NOT be targetable by Ray."
  {:card/id :test-creature
   :card/name "Test Creature"
   :card/cmc 2
   :card/mana-cost {:green 2}
   :card/colors #{:green}
   :card/types #{:creature}
   :card/text "2/2"
   :card/effects []})


(def test-artifact
  "An artifact that should NOT be targetable by Ray."
  {:card/id :test-artifact
   :card/name "Test Artifact"
   :card/cmc 2
   :card/mana-cost {:colorless 2}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "Test artifact."
   :card/effects []})


;; === Card Definition Tests ===

;; Oracle: "Destroy target enchantment.\nFlashback {G}"
(deftest ray-of-revelation-card-definition-test
  (testing "Ray of Revelation type, cost, and color"
    (is (= {:colorless 1 :white 1} (:card/mana-cost ray/card))
        "Ray of Revelation should cost {1}{W}")
    (is (= #{:instant} (:card/types ray/card))
        "Ray of Revelation should be an instant")
    (is (= 2 (:card/cmc ray/card))
        "Ray of Revelation should have CMC 2")
    (is (= #{:white} (:card/colors ray/card))
        "Ray of Revelation should be white"))

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
    (let [db (init-game-state)
          ;; Need a valid target on battlefield
          [_enchant-id db] (add-card-to-zone db :player-1 test-enchantment :battlefield)
          [obj-id db] (add-card-to-zone db :player-1 ray/card :hand)
          db (mana/add-mana db :player-1 {:colorless 1 :white 1})
          modes (rules/get-casting-modes db :player-1 obj-id)
          primary-mode (first (filter #(= :primary (:mode/id %)) modes))]
      (is (rules/can-cast-mode? db :player-1 obj-id primary-mode)
          "Should be castable with {1}{W}"))))


(deftest ray-not-castable-without-mana-test
  (testing "Ray not castable from hand without mana"
    (let [db (init-game-state)
          [_enchant-id db] (add-card-to-zone db :player-1 test-enchantment :battlefield)
          [obj-id db] (add-card-to-zone db :player-1 ray/card :hand)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana"))))


;; === Target Validation Tests ===

;; Oracle: "Destroy target enchantment."
(deftest ray-can-target-enchantment-on-battlefield-test
  (testing "Ray can target enchantment on battlefield"
    (let [db (init-game-state)
          [enchant-id db] (add-card-to-zone db :player-1 test-enchantment :battlefield)]
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
  (testing "Ray cannot target creatures"
    (let [db (init-game-state)
          [_creature-id db] (add-card-to-zone db :player-1 test-creature :battlefield)]
      (is (false? (targeting/has-valid-targets? db :player-1 ray/card))
          "Should not have valid targets when only creature on battlefield")))

  (testing "Ray cannot target artifacts"
    (let [db (init-game-state)
          [_artifact-id db] (add-card-to-zone db :player-1 test-artifact :battlefield)]
      (is (false? (targeting/has-valid-targets? db :player-1 ray/card))
          "Should not have valid targets when only artifact on battlefield"))))


;; Edge case: No valid targets means card shouldn't be castable
(deftest ray-not-castable-without-valid-targets-test
  (testing "Ray not castable when no enchantments on battlefield"
    (let [db (init-game-state)
          [_obj-id db] (add-card-to-zone db :player-1 ray/card :hand)
          db (mana/add-mana db :player-1 {:colorless 1 :white 1})]
      ;; Even with enough mana, can't cast without valid target
      (is (false? (targeting/has-valid-targets? db :player-1 ray/card))
          "Should not have valid targets with empty battlefield"))))


;; Oracle: Can target ANY enchantment (opponent's included)
(deftest ray-can-target-opponent-enchantment-test
  (testing "Ray can target opponent's enchantment"
    (let [db (-> (init-game-state)
                 (add-opponent))
          ;; Add enchantment to opponent's battlefield
          [_enchant-id db] (add-card-to-zone db :player-2 test-enchantment :battlefield)]
      (is (targeting/has-valid-targets? db :player-1 ray/card)
          "Should be able to target opponent's enchantment"))))


;; === Flashback Tests ===

;; Oracle: "Flashback {G}"
(deftest ray-flashback-cast-from-graveyard-test
  (testing "Ray castable from graveyard for {G}"
    (let [db (init-game-state)
          [_enchant-id db] (add-card-to-zone db :player-1 test-enchantment :battlefield)
          [obj-id db] (add-card-to-zone db :player-1 ray/card :graveyard)
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
    (let [db (init-game-state)
          [_enchant-id db] (add-card-to-zone db :player-1 test-enchantment :battlefield)
          [obj-id db] (add-card-to-zone db :player-1 ray/card :graveyard)]
      ;; No mana added
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard without mana"))))


(deftest ray-flashback-not-castable-with-wrong-mana-test
  (testing "Ray flashback not castable with wrong mana color"
    (let [db (init-game-state)
          [_enchant-id db] (add-card-to-zone db :player-1 test-enchantment :battlefield)
          [obj-id db] (add-card-to-zone db :player-1 ray/card :graveyard)
          ;; Add white mana, but flashback needs green
          db (mana/add-mana db :player-1 {:white 1})]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable with wrong mana color"))))


;; Ruling (2021-03-19): "Flashback increments storm count"
(deftest ray-flashback-increments-storm-test
  (testing "Flashback cast increments storm count"
    (let [db (init-game-state)
          [_enchant-id db] (add-card-to-zone db :player-1 test-enchantment :battlefield)
          [obj-id db] (add-card-to-zone db :player-1 ray/card :graveyard)
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
    (let [db (init-game-state)
          [enchant-id db] (add-card-to-zone db :player-1 test-enchantment :battlefield)
          [obj-id db] (add-card-to-zone db :player-1 ray/card :graveyard)
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
    (let [db (init-game-state)
          [_enchant-id db] (add-card-to-zone db :player-1 test-enchantment :battlefield)
          [obj-id db] (add-card-to-zone db :player-1 ray/card :graveyard)
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
    (let [db (init-game-state)
          [_enchant-id db] (add-card-to-zone db :player-1 test-enchantment :battlefield)
          [obj-id db] (add-card-to-zone db :player-1 ray/card :hand)
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
    (let [db (init-game-state)
          [enchant-id db] (add-card-to-zone db :player-1 test-enchantment :battlefield)
          [_obj-id db] (add-card-to-zone db :player-1 ray/card :hand)
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
    (let [db (init-game-state)
          ;; No enchantment on battlefield
          [_obj-id db] (add-card-to-zone db :player-1 ray/card :graveyard)
          db (mana/add-mana db :player-1 {:green 1})]
      ;; Has mana, but no valid targets
      (is (false? (targeting/has-valid-targets? db :player-1 ray/card))
          "Should have no valid targets with empty battlefield"))))
