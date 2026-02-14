(ns fizzle.cards.orims-chant-test
  "Tests for Orim's Chant card.

   Orim's Chant: W - Instant
   Kicker {W}
   Target player can't cast spells this turn.
   If this spell was kicked, creatures can't attack this turn.

   This tests:
   - Card definition (type, cost, kicker, targeting, effects)
   - Kicker mode generation (primary and kicked modes)
   - Mode costs (base and combined)
   - Effect definitions (cannot-cast-spells, cannot-attack)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.orims-chant :as orims-chant]
    [fizzle.db.queries :as q]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.selection.resolution :as resolution]
    [fizzle.events.selection.targeting :as sel-targeting]
    [fizzle.test-helpers :as th]))


;; === Card Definition Tests ===

(deftest orims-chant-card-definition-test
  (testing "card has correct oracle properties"
    (let [card orims-chant/orims-chant]
      (is (= :orims-chant (:card/id card))
          "Card ID should be :orims-chant")
      (is (= "Orim's Chant" (:card/name card))
          "Card name should match oracle")
      (is (= 1 (:card/cmc card))
          "CMC should be 1")
      (is (= {:white 1} (:card/mana-cost card))
          "Mana cost should be {W}")
      (is (= #{:white} (:card/colors card))
          "Card should be white")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")
      (is (= {:white 1} (:card/kicker card))
          "Kicker cost should be {W}")))

  (testing "card uses correct targeting format"
    (let [targeting (:card/targeting orims-chant/orims-chant)]
      (is (vector? targeting)
          "Targeting must be a vector for targeting system")
      (is (= 1 (count targeting))
          "Should have exactly one target requirement")
      (let [req (first targeting)]
        (is (= :player (:target/id req))
            "Target ID should be :player")
        (is (= :player (:target/type req))
            "Target type should be :player")
        (is (contains? (:target/options req) :any-player)
            "Should allow targeting any player")
        (is (:target/required req)
            "Target should be required"))))

  (testing "base effects add cannot-cast-spells restriction"
    (let [effects (:card/effects orims-chant/orims-chant)]
      (is (= 1 (count effects))
          "Should have exactly one base effect")
      (let [effect (first effects)]
        (is (= :add-restriction (:effect/type effect))
            "Effect type should be :add-restriction")
        (is (= :targeted-player (:effect/target effect))
            "Effect target should be :targeted-player")
        (is (= :cannot-cast-spells (:restriction/type effect))
            "Restriction type should be :cannot-cast-spells"))))

  (testing "kicked effects add both restrictions"
    (let [effects (:card/kicked-effects orims-chant/orims-chant)]
      (is (= 2 (count effects))
          "Should have two kicked effects")
      (is (= #{:cannot-cast-spells :cannot-attack}
             (set (map :restriction/type effects)))
          "Should include both restriction types"))))


;; === Kicker Mode Tests ===

(deftest orims-chant-casting-modes-test
  (testing "get-casting-modes returns primary and kicked with correct costs and effects"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [orims-chant/orims-chant])
          db (th/add-opponent @conn)
          [db obj-id] (th/add-card-to-zone db :orims-chant :hand :player-1)
          modes (rules/get-casting-modes db :player-1 obj-id)
          primary (first (filter #(= :primary (:mode/id %)) modes))
          kicked (first (filter #(= :kicked (:mode/id %)) modes))]
      (is (= 2 (count modes))
          "Should return 2 casting modes")
      (is (= {:white 1} (:mode/mana-cost primary))
          "Primary mode should cost {W}")
      (is (= {:white 2} (:mode/mana-cost kicked))
          "Kicked mode should cost {WW} (base {W} + kicker {W})")
      (is (= 2 (count (:mode/effects kicked)))
          "Kicked mode should have 2 effects"))))


;; === Targeting System Integration Tests ===

(deftest has-valid-targets-test
  ;; Bug caught: Targeting system can't find player targets
  (testing "has-valid-targets? returns true when opponent exists"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [orims-chant/orims-chant])
          db (th/add-opponent @conn)]
      (is (targeting/has-valid-targets? db :player-1 orims-chant/orims-chant)
          "Should have valid targets (can target self or opponent)"))))


(deftest find-valid-targets-test
  ;; Bug caught: Target options format wrong (vector vs set)
  (testing "find-valid-targets returns both players with :any-player"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [orims-chant/orims-chant])
          db (th/add-opponent @conn)
          req (first (:card/targeting orims-chant/orims-chant))
          targets (targeting/find-valid-targets db :player-1 req)]
      (is (= 2 (count targets))
          "Should find 2 valid targets (self and opponent)")
      (is (contains? (set targets) :player-1)
          "Should include self as valid target")
      (is (contains? (set targets) :player-2)
          "Should include opponent as valid target"))))


(deftest goldfish-mode-targets-test
  ;; Bug caught: Can't cast in goldfish mode (no opponent)
  (testing "has-valid-targets? returns true even without opponent (can target self)"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [orims-chant/orims-chant])
          db @conn]
      ;; Even without opponent, can target self
      (is (targeting/has-valid-targets? db :player-1 orims-chant/orims-chant)
          "Should have valid targets (can target self even in goldfish)"))))


;; === Resolution Tests ===

(deftest orims-chant-resolution-applies-restriction-test
  ;; Bug caught: restriction never applied during spell resolution
  (testing "Casting and resolving Orim's Chant adds cannot-cast-spells restriction"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [orims-chant/orims-chant])
          db (th/add-opponent @conn)
          ;; Add Orim's Chant to hand
          [db obj-id] (th/add-card-to-zone db :orims-chant :hand :player-1)
          ;; Add white mana to cast
          db-with-mana (mana/add-mana db :player-1 {:white 1})
          ;; Cast with targeting flow (stores target on stack-item)
          target-req (first (:card/targeting orims-chant/orims-chant))
          modes (rules/get-casting-modes db-with-mana :player-1 obj-id)
          mode (first (filter #(= :primary (:mode/id %)) modes))
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id obj-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{:player-2}}
          db-cast (sel-targeting/confirm-cast-time-target db-with-mana selection)
          _ (is (= :stack (:object/zone (q/get-object db-cast obj-id)))
                "Precondition: Orim's Chant on stack")
          ;; Get stack-item and resolve via selection path
          obj-eid (d/q '[:find ?e . :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db-cast obj-id)
          stack-item (stack/get-stack-item-by-object-ref db-cast obj-eid)
          result (resolution/resolve-spell-with-selection db-cast :player-1 obj-id stack-item)
          db-resolved (:db result)]
      ;; Spell should be in graveyard after resolution
      (is (= :graveyard (:object/zone (q/get-object db-resolved obj-id)))
          "Orim's Chant should be in graveyard after resolution")
      ;; Player-2 should have a restriction grant
      (let [player2-grants (grants/get-player-grants db-resolved :player-2)]
        (is (seq player2-grants)
            "Player-2 should have at least one grant after resolution")
        (is (some #(= :cannot-cast-spells (:restriction/type (:grant/data %))) player2-grants)
            "Player-2 should have :cannot-cast-spells restriction")))))


(deftest orims-chant-restricted-player-cannot-cast-test
  ;; Bug caught: restriction exists but not enforced by can-cast?
  (testing "Player with cannot-cast-spells restriction cannot cast spells"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [orims-chant/orims-chant])
          db (th/add-opponent @conn)
          ;; Add Orim's Chant to hand
          [db _chant-id] (th/add-card-to-zone db :orims-chant :hand :player-1)
          ;; Add a spell to player-2's hand
          [db spell-id] (th/add-card-to-zone db :dark-ritual :hand :player-2)
          ;; Give player-2 mana to cast Dark Ritual
          conn (d/conn-from-db db)
          p2-eid (q/get-player-eid db :player-2)
          _ (d/transact! conn [[:db/add p2-eid :player/mana-pool
                                {:white 0 :blue 0 :black 1 :red 0 :green 0 :colorless 0}]])
          db @conn
          ;; Verify player-2 CAN cast before restriction
          _ (is (rules/can-cast? db :player-2 spell-id)
                "Precondition: player-2 can cast Dark Ritual before restriction")
          ;; Apply restriction directly via grants (simulating resolved Orim's Chant)
          db-restricted (grants/add-player-grant db :player-2
                                                 {:grant/type :restriction
                                                  :grant/data {:restriction/type :cannot-cast-spells}
                                                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}})]
      ;; Player-2 should NOT be able to cast now
      (is (false? (rules/can-cast? db-restricted :player-2 spell-id))
          "Player-2 should not be able to cast with cannot-cast-spells restriction"))))


;; === Stack-Item Target Storage Tests ===

(deftest confirm-cast-time-target-stores-on-stack-item-test
  (testing "cast-time targeting stores targets on stack-item, not object"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [orims-chant/orims-chant])
          db (th/add-opponent @conn)
          [db obj-id] (th/add-card-to-zone db :orims-chant :hand :player-1)
          db-with-mana (mana/add-mana db :player-1 {:white 1})
          ;; Build cast-time target selection (simulates UI)
          target-req (first (:card/targeting orims-chant/orims-chant))
          modes (rules/get-casting-modes db-with-mana :player-1 obj-id)
          mode (first (filter #(= :primary (:mode/id %)) modes))
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id obj-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{:player-2}}
          ;; Confirm cast with target
          db-after (sel-targeting/confirm-cast-time-target db-with-mana selection)]
      ;; Spell should be on stack
      (is (= :stack (:object/zone (q/get-object db-after obj-id)))
          "Orim's Chant should be on stack after casting")
      ;; Stack-item should have targets
      (let [obj-eid (d/q '[:find ?e . :in $ ?oid
                           :where [?e :object/id ?oid]]
                         db-after obj-id)
            stack-item (stack/get-stack-item-by-object-ref db-after obj-eid)]
        (is (some? stack-item)
            "Stack-item should exist for the spell")
        (is (= {:player :player-2} (:stack-item/targets stack-item))
            "Stack-item should have targets with :player-2"))
      ;; Object should NOT have targets
      (let [obj (q/get-object db-after obj-id)]
        (is (nil? (:object/targets obj))
            "Object should NOT have :object/targets")))))


(deftest resolve-spell-with-selection-uses-stack-item-targets-test
  (testing "spell resolution reads targets from stack-item and applies effects"
    (let [db (th/create-test-db)
          conn (d/conn-from-db db)
          _ (d/transact! conn [orims-chant/orims-chant])
          db (th/add-opponent @conn)
          [db obj-id] (th/add-card-to-zone db :orims-chant :hand :player-1)
          db-with-mana (mana/add-mana db :player-1 {:white 1})
          ;; Cast and store target on stack-item
          target-req (first (:card/targeting orims-chant/orims-chant))
          modes (rules/get-casting-modes db-with-mana :player-1 obj-id)
          mode (first (filter #(= :primary (:mode/id %)) modes))
          selection {:selection/type :cast-time-targeting
                     :selection/player-id :player-1
                     :selection/object-id obj-id
                     :selection/mode mode
                     :selection/target-requirement target-req
                     :selection/selected #{:player-2}}
          db-cast (sel-targeting/confirm-cast-time-target db-with-mana selection)
          ;; Get the stack-item for the spell
          obj-eid (d/q '[:find ?e . :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db-cast obj-id)
          stack-item (stack/get-stack-item-by-object-ref db-cast obj-eid)
          ;; Resolve via resolve-spell-with-selection (needs stack-item)
          result (resolution/resolve-spell-with-selection db-cast :player-1 obj-id stack-item)]
      ;; Spell should be resolved (in graveyard)
      (is (= :graveyard (:object/zone (q/get-object (:db result) obj-id)))
          "Orim's Chant should be in graveyard after resolution")
      ;; Player-2 should have restriction
      (let [p2-grants (grants/get-player-grants (:db result) :player-2)]
        (is (seq p2-grants)
            "Player-2 should have grants after resolution")
        (is (some #(= :cannot-cast-spells (:restriction/type (:grant/data %))) p2-grants)
            "Player-2 should have cannot-cast-spells restriction")))))
