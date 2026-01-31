(ns fizzle.engine.rules-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.zones :as zones]))


;; === can-cast? tests ===

(deftest can-cast-returns-false-without-mana-test
  (testing "can-cast? returns false when player lacks mana"
    (let [db (init-game-state)
          hand (q/get-hand db :player-1)
          ritual (first hand)]
      (is (false? (rules/can-cast? db :player-1 (:object/id ritual)))))))


(deftest can-cast-returns-true-with-mana-test
  (testing "can-cast? returns true when player has sufficient mana"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)]
      (is (true? (rules/can-cast? db :player-1 (:object/id ritual)))))))


(deftest can-cast-returns-false-wrong-zone-test
  (testing "can-cast? returns false when card not in hand (on stack)"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 2}))  ; Extra mana for test
          hand (q/get-hand db :player-1)
          ritual (first hand)
          obj-id (:object/id ritual)
          ;; Move card to stack (simulating cast without using cast-spell)
          db' (zones/move-to-zone db obj-id :stack)]
      ;; Card is on stack, not in hand - should return false
      (is (false? (rules/can-cast? db' :player-1 obj-id))))))


;; === cast-spell tests ===

(deftest cast-spell-moves-to-stack-test
  (testing "cast-spell moves card from hand to stack"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          db' (rules/cast-spell db :player-1 (:object/id ritual))]
      (is (= 0 (count (q/get-hand db' :player-1))))
      (is (= 1 (count (q/get-objects-in-zone db' :player-1 :stack)))))))


(deftest cast-spell-pays-mana-test
  (testing "cast-spell deducts mana cost from pool"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 2}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          db' (rules/cast-spell db :player-1 (:object/id ritual))]
      (is (= 1 (:black (q/get-mana-pool db' :player-1)))))))


(deftest cast-spell-increments-storm-test
  (testing "cast-spell increments storm count"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          db' (rules/cast-spell db :player-1 (:object/id ritual))]
      (is (= 1 (q/get-storm-count db' :player-1))))))


;; === resolve-spell tests ===

(deftest resolve-spell-executes-effects-test
  (testing "resolve-spell executes card effects"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          db' (-> db
                  (rules/cast-spell :player-1 (:object/id ritual))
                  (rules/resolve-spell :player-1 (:object/id ritual)))]
      ;; Dark Ritual adds BBB
      (is (= 3 (:black (q/get-mana-pool db' :player-1)))))))


(deftest resolve-spell-moves-to-graveyard-test
  (testing "resolve-spell moves card from stack to graveyard"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          db' (-> db
                  (rules/cast-spell :player-1 (:object/id ritual))
                  (rules/resolve-spell :player-1 (:object/id ritual)))]
      (is (= 0 (count (q/get-objects-in-zone db' :player-1 :stack))))
      (is (= 1 (count (q/get-objects-in-zone db' :player-1 :graveyard)))))))


;; === Full pipeline test ===

(deftest dark-ritual-full-pipeline-test
  (testing "Dark Ritual: cast with B, resolve to get BBB, storm = 1"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          obj-id (:object/id ritual)

          ;; Verify can cast
          _ (is (true? (rules/can-cast? db :player-1 obj-id)))

          ;; Cast it
          db-cast (rules/cast-spell db :player-1 obj-id)
          _ (is (= 0 (:black (q/get-mana-pool db-cast :player-1))))
          _ (is (= 1 (q/get-storm-count db-cast :player-1)))
          _ (is (= 1 (count (q/get-objects-in-zone db-cast :player-1 :stack))))

          ;; Resolve it
          db-resolved (rules/resolve-spell db-cast :player-1 obj-id)]
      (is (= 3 (:black (q/get-mana-pool db-resolved :player-1))))
      (is (= 0 (count (q/get-objects-in-zone db-resolved :player-1 :stack))))
      (is (= 1 (count (q/get-objects-in-zone db-resolved :player-1 :graveyard)))))))


;; === Stack ordering tests ===

(defn init-two-spell-state
  "Create game state with two Dark Rituals in hand for stack ordering tests."
  []
  (let [conn (d/conn-from-db (init-game-state))
        db @conn
        player-eid (q/get-player-eid db :player-1)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)]
    ;; Add a second Dark Ritual object in hand
    (d/transact! conn [{:object/id :dr-2
                        :object/card card-eid
                        :object/zone :hand
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    @conn))


(deftest cast-spell-sets-stack-position-test
  (testing "cast-spell assigns :object/position for stack ordering"
    (let [db (-> (init-two-spell-state)
                 (mana/add-mana :player-1 {:black 2}))
          hand (q/get-hand db :player-1)
          obj-id (:object/id (first hand))
          db' (rules/cast-spell db :player-1 obj-id)
          obj' (q/get-object db' obj-id)]
      (is (= 0 (:object/position obj'))
          "First cast spell should have position 0"))))


(deftest cast-two-spells-lifo-order-test
  (testing "Second spell cast gets higher position than first (LIFO)"
    (let [db (-> (init-two-spell-state)
                 (mana/add-mana :player-1 {:black 2}))
          hand (q/get-hand db :player-1)
          first-id (:object/id (first hand))
          second-id (:object/id (second hand))
          db' (-> db
                  (rules/cast-spell :player-1 first-id)
                  (rules/cast-spell :player-1 second-id))
          first-obj (q/get-object db' first-id)
          second-obj (q/get-object db' second-id)]
      (is (> (:object/position second-obj)
             (:object/position first-obj))
          "Second cast should have higher stack position"))))


(deftest stack-objects-resolve-lifo-test
  (testing "Stack objects sorted by position descending resolve most recent first"
    (let [db (-> (init-two-spell-state)
                 (mana/add-mana :player-1 {:black 2}))
          hand (q/get-hand db :player-1)
          first-id (:object/id (first hand))
          second-id (:object/id (second hand))
          db' (-> db
                  (rules/cast-spell :player-1 first-id)
                  (rules/cast-spell :player-1 second-id))
          stack (->> (q/get-objects-in-zone db' :player-1 :stack)
                     (sort-by :object/position >))
          top-spell (first stack)]
      (is (= second-id (:object/id top-spell))
          "Most recently cast spell should be on top of stack"))))


;; === Conditional effects tests (Cabal Ritual + threshold) ===

(defn add-cards-to-graveyard
  "Add n cards to a player's graveyard."
  [db player-id n]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)]
    (doseq [_ (range n)]
      (d/transact! conn [{:object/id (random-uuid)
                          :object/card card-eid
                          :object/zone :graveyard
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false}]))
    @conn))


(defn add-cabal-ritual-to-hand
  "Add Cabal Ritual card definition and create an object in hand."
  [db player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    ;; Add card definition
    (d/transact! conn [cards/cabal-ritual])
    ;; Create object in hand
    (let [card-eid (d/q '[:find ?e .
                          :where [?e :card/id :cabal-ritual]]
                        @conn)
          obj-id (random-uuid)]
      (d/transact! conn [{:object/id obj-id
                          :object/card card-eid
                          :object/zone :hand
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false}])
      [obj-id @conn])))


(deftest test-cabal-ritual-without-threshold
  (testing "Cabal Ritual adds BBB without threshold (< 7 cards in graveyard)"
    (let [db (init-game-state)
          ;; Add 5 cards to graveyard (below threshold)
          db (add-cards-to-graveyard db :player-1 5)
          ;; Add Cabal Ritual to hand and get object-id
          [obj-id db] (add-cabal-ritual-to-hand db :player-1)
          ;; Add mana to cast (1B cost)
          db (mana/add-mana db :player-1 {:black 1 :colorless 1})
          ;; Cast and resolve
          db (rules/cast-spell db :player-1 obj-id)
          db (rules/resolve-spell db :player-1 obj-id)]
      ;; Should have 3 black mana (BBB from effect)
      (is (= 3 (:black (q/get-mana-pool db :player-1)))))))


(deftest test-cabal-ritual-with-threshold
  (testing "Cabal Ritual adds BBBBB with threshold (7+ cards in graveyard)"
    (let [db (init-game-state)
          ;; Add 7 cards to graveyard (at threshold)
          db (add-cards-to-graveyard db :player-1 7)
          ;; Add Cabal Ritual to hand
          [obj-id db] (add-cabal-ritual-to-hand db :player-1)
          ;; Add mana to cast (1B cost)
          db (mana/add-mana db :player-1 {:black 1 :colorless 1})
          ;; Cast and resolve
          db (rules/cast-spell db :player-1 obj-id)
          db (rules/resolve-spell db :player-1 obj-id)]
      ;; Should have 5 black mana (BBBBB from threshold effect)
      (is (= 5 (:black (q/get-mana-pool db :player-1)))))))


(deftest test-threshold-checked-at-resolution
  (testing "Threshold is checked at resolution time, not cast time"
    ;; This tests that if graveyard changes between cast and resolve,
    ;; the resolution-time graveyard count matters.
    ;; We'll simulate by having exactly 6 cards, then after cast
    ;; we manually add another (simulating another effect filling gy).
    (let [db (init-game-state)
          ;; Add 6 cards to graveyard (below threshold at cast)
          db (add-cards-to-graveyard db :player-1 6)
          ;; Add Cabal Ritual to hand
          [obj-id db] (add-cabal-ritual-to-hand db :player-1)
          ;; Add mana to cast
          db (mana/add-mana db :player-1 {:black 1 :colorless 1})
          ;; Cast (threshold NOT active at this moment)
          db (rules/cast-spell db :player-1 obj-id)
          ;; Before resolution, add 1 more card to graveyard (now 7 = threshold)
          db (add-cards-to-graveyard db :player-1 1)
          ;; Resolve - should check threshold NOW
          db (rules/resolve-spell db :player-1 obj-id)]
      ;; Should have 5 black mana (BBBBB) because threshold was
      ;; active at resolution, even though it wasn't at cast
      (is (= 5 (:black (q/get-mana-pool db :player-1)))))))


;; === Corner case tests ===

(deftest test-resolve-spell-not-on-stack
  (testing "resolve-spell is no-op when spell not on stack"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)
          obj-id (:object/id ritual)
          ;; Don't cast - spell is still in hand, not on stack
          ;; Calling resolve-spell on a spell in hand (wrong zone)
          db' (rules/resolve-spell db :player-1 obj-id)]
      ;; Should be no-op - spell stays in hand, no effects execute
      (is (= 1 (count (q/get-hand db' :player-1)))
          "Spell should remain in hand")
      (is (= 0 (count (q/get-objects-in-zone db' :player-1 :graveyard)))
          "Graveyard should be empty")
      (is (= 1 (:black (q/get-mana-pool db' :player-1)))
          "Mana pool should be unchanged"))))


(def malformed-negative-cost-card
  "Card with malformed negative mana cost for edge case testing."
  {:card/id :negative-cost-card
   :card/name "Negative Cost Card"
   :card/cmc 0  ; CMC is 0 but cost is negative
   :card/mana-cost {:black -1}  ; Malformed: negative cost
   :card/colors #{:black}
   :card/types #{:instant}
   :card/text "This card has malformed data."
   :card/effects []})


(defn add-malformed-card-to-hand
  "Add malformed card to player's hand for testing."
  [db player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    ;; Add card definition
    (d/transact! conn [malformed-negative-cost-card])
    ;; Create object in hand
    (let [card-eid (d/q '[:find ?e .
                          :where [?e :card/id :negative-cost-card]]
                        @conn)
          obj-id (random-uuid)]
      (d/transact! conn [{:object/id obj-id
                          :object/card card-eid
                          :object/zone :hand
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/tapped false}])
      [obj-id @conn])))


(deftest test-cast-negative-cost
  (testing "cast-spell handles malformed card with negative mana cost"
    (let [db (init-game-state)
          [obj-id db] (add-malformed-card-to-hand db :player-1)
          initial-black (:black (q/get-mana-pool db :player-1))
          ;; Cast the malformed card (no mana needed since cost is negative)
          db' (rules/cast-spell db :player-1 obj-id)
          final-black (:black (q/get-mana-pool db' :player-1))]
      ;; Verify spell was cast (moved to stack)
      (is (= 1 (count (q/get-objects-in-zone db' :player-1 :stack)))
          "Card should be on stack")
      ;; With negative cost {:black -1}, subtracting negative adds mana
      ;; This documents the current behavior - not necessarily desired
      ;; but the test ensures we know what happens with bad data
      (is (= (+ initial-black 1) final-black)
          "Negative cost subtracts negative (adds mana) - documents malformed behavior"))))


;; === Permanent type resolution tests ===

(deftest test-permanent-type-artifact
  (testing "permanent-type? returns true for artifacts"
    (is (true? (rules/permanent-type? #{:artifact}))
        "Artifact should be a permanent type")))


(deftest test-permanent-type-creature
  (testing "permanent-type? returns true for creatures"
    (is (true? (rules/permanent-type? #{:creature}))
        "Creature should be a permanent type")))


(deftest test-permanent-type-enchantment
  (testing "permanent-type? returns true for enchantments"
    (is (true? (rules/permanent-type? #{:enchantment}))
        "Enchantment should be a permanent type")))


(deftest test-permanent-type-instant-sorcery
  (testing "permanent-type? returns false for instant and sorcery"
    (is (false? (rules/permanent-type? #{:instant}))
        "Instant should not be a permanent type")
    (is (false? (rules/permanent-type? #{:sorcery}))
        "Sorcery should not be a permanent type")))


(deftest test-permanent-type-land
  (testing "permanent-type? returns false for land (lands don't go through resolve-spell)"
    ;; Lands use play-land, not cast-spell/resolve-spell
    ;; but if they somehow did, they shouldn't be treated as permanents here
    (is (false? (rules/permanent-type? #{:land}))
        "Land should not be treated as permanent in resolve-spell")))


(defn add-artifact-to-hand
  "Add an artifact card to player's hand for testing resolution.
   Returns [obj-id db] tuple."
  [db player-id card-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      @conn card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone :hand
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [obj-id @conn]))


(deftest test-artifact-resolves-to-battlefield
  (testing "Artifact spell resolves to battlefield, not graveyard"
    (let [db (init-game-state)
          ;; Add Lotus Petal card definition (it's in cards/all-cards via init)
          ;; But init-game-state only loads dark-ritual, so add it
          conn (d/conn-from-db db)
          _ (d/transact! conn [cards/lotus-petal])
          db @conn
          [obj-id db] (add-artifact-to-hand db :player-1 :lotus-petal)
          ;; Cast - artifacts have 0 mana cost
          db-cast (rules/cast-spell db :player-1 obj-id)
          _ (is (= :stack (:object/zone (q/get-object db-cast obj-id)))
                "Artifact should be on stack after casting")
          ;; Resolve
          db-resolved (rules/resolve-spell db-cast :player-1 obj-id)]
      (is (= :battlefield (:object/zone (q/get-object db-resolved obj-id)))
          "Artifact should resolve to battlefield")
      (is (= 1 (count (q/get-objects-in-zone db-resolved :player-1 :battlefield)))
          "Should have 1 permanent on battlefield")
      (is (= 0 (count (q/get-objects-in-zone db-resolved :player-1 :graveyard)))
          "Graveyard should be empty"))))


(deftest test-instant-still-resolves-to-graveyard
  (testing "Instant spell still resolves to graveyard (not battlefield)"
    (let [db (-> (init-game-state)
                 (mana/add-mana :player-1 {:black 1}))
          hand (q/get-hand db :player-1)
          ritual (first hand)  ; Dark Ritual is an instant
          obj-id (:object/id ritual)
          db-cast (rules/cast-spell db :player-1 obj-id)
          db-resolved (rules/resolve-spell db-cast :player-1 obj-id)]
      (is (= :graveyard (:object/zone (q/get-object db-resolved obj-id)))
          "Instant should resolve to graveyard")
      (is (= 0 (count (q/get-objects-in-zone db-resolved :player-1 :battlefield)))
          "Battlefield should be empty")
      (is (= 1 (count (q/get-objects-in-zone db-resolved :player-1 :graveyard)))
          "Should have 1 card in graveyard"))))
