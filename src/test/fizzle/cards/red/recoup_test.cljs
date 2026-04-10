(ns fizzle.cards.red.recoup-test
  "Tests for Recoup card.

   Recoup: 1R - Sorcery
   Target sorcery card in your graveyard gains flashback until end of turn.
   The flashback cost is equal to the card's mana cost.
   Flashback {3}{R}

   This tests:
   - Card definition (type, cost, targeting, effects, flashback)
   - Cast requirements (must have valid sorcery target)
   - Grant flashback effect (grants alternate cost to target)
   - Fizzle behavior (target removed before resolution)
   - Cleanup expiration (granted flashback expires)
   - Flashback Recoup (casting Recoup from graveyard)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.red.recoup :as recoup]
    [fizzle.db.queries :as q]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.zones :as zones]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === Card Definition Tests ===

(deftest test-recoup-card-definition
  (testing "Recoup has correct mana cost, type, and color"
    (is (= {:colorless 1 :red 1} (:card/mana-cost recoup/card))
        "Recoup should cost {1}{R}")
    (is (= 2 (:card/cmc recoup/card))
        "Recoup should have CMC 2")
    (is (= #{:sorcery} (:card/types recoup/card))
        "Recoup should be a sorcery")
    (is (= #{:red} (:card/colors recoup/card))
        "Recoup should be red"))

  (testing "Recoup has :card/targeting for graveyard sorcery"
    (let [reqs (targeting/get-targeting-requirements recoup/card)]
      (is (= 1 (count reqs))
          "Should have exactly 1 targeting requirement")
      (is (= :graveyard-sorcery (:target/id (first reqs)))
          "Target id should be :graveyard-sorcery")
      (is (= :object (:target/type (first reqs)))
          "Target type should be :object")
      (is (= :graveyard (:target/zone (first reqs)))
          "Target zone should be :graveyard")
      (is (= :self (:target/controller (first reqs)))
          "Target controller should be :self")
      (is (= {:match/types #{:sorcery}} (:target/criteria (first reqs)))
          "Target criteria should require sorcery type")
      (is (true? (:target/required (first reqs)))
          "Target should be required")))

  (testing "Recoup has :grant-flashback effect"
    (let [effects (:card/effects recoup/card)
          effect (first effects)]
      (is (= 1 (count effects))
          "Should have exactly 1 effect")
      (is (= :grant-flashback (:effect/type effect))
          "Effect type should be :grant-flashback")
      (is (= :graveyard-sorcery (:effect/target-ref effect))
          "Effect target-ref should match targeting id")))

  (testing "Recoup has its own flashback for {3}{R}"
    (let [flashback (first (:card/alternate-costs recoup/card))]
      (is (= :flashback (:alternate/id flashback))
          "Alternate should be flashback")
      (is (= :graveyard (:alternate/zone flashback))
          "Flashback should be castable from graveyard")
      (is (= {:colorless 3 :red 1} (:alternate/mana-cost flashback))
          "Flashback should cost {3}{R}")
      (is (= :exile (:alternate/on-resolve flashback))
          "Flashback should exile on resolve"))))


;; === C. Cannot-Cast Guards ===

(deftest recoup-cannot-cast-without-mana-test
  (testing "Cannot cast Recoup without mana"
    (let [db (-> (th/create-test-db) th/add-opponent)
          [db recoup-id] (th/add-card-to-zone db :recoup :hand :player-1)
          ;; Add a valid sorcery target
          [db _] (th/add-cards-to-graveyard db [:careful-study] :player-1)]
      (is (false? (rules/can-cast? db :player-1 recoup-id))
          "Should not be castable without mana"))))


;; === D. Storm Count ===

(deftest recoup-increments-storm-count-test
  (testing "Casting Recoup increments storm count"
    (let [db (-> (th/create-test-db {:mana {:colorless 1 :red 1}}) th/add-opponent)
          [db recoup-id] (th/add-card-to-zone db :recoup :hand :player-1)
          [db [sorcery-id]] (th/add-cards-to-graveyard db [:careful-study] :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Storm count should start at 0")
          db-cast (th/cast-with-target db :player-1 recoup-id sorcery-id)]
      (is (= 1 (q/get-storm-count db-cast :player-1))
          "Storm count should be 1 after casting Recoup"))))


;; === Cast Requirements Tests ===

(deftest test-recoup-requires-valid-target
  (testing "Recoup cannot be cast without sorcery in graveyard"
    (let [db (-> (th/create-test-db {:mana {:colorless 1 :red 1}}) th/add-opponent)
          [db recoup-id] (th/add-card-to-zone db :recoup :hand :player-1)]
      ;; No sorceries in graveyard
      (is (false? (targeting/has-valid-targets? db :player-1 recoup/card))
          "Should not have valid targets with empty graveyard")
      (is (false? (rules/can-cast? db :player-1 recoup-id))
          "Recoup should not be castable without valid target"))))


(deftest test-recoup-finds-valid-sorcery-target
  (testing "Recoup can target sorcery in graveyard"
    (let [db (-> (th/create-test-db {:mana {:colorless 1 :red 1}}) th/add-opponent)
          [db _recoup-id] (th/add-card-to-zone db :recoup :hand :player-1)
          ;; Add a sorcery to graveyard (careful-study is a sorcery)
          [db [sorcery-id]] (th/add-cards-to-graveyard db [:careful-study] :player-1)]
      (is (targeting/has-valid-targets? db :player-1 recoup/card)
          "Should have valid target with sorcery in graveyard")
      (let [req (first (targeting/get-targeting-requirements recoup/card))
            targets (targeting/find-valid-targets db :player-1 req)]
        (is (= [sorcery-id] targets)
            "Should find the sorcery as valid target")))))


(deftest test-recoup-does-not-target-instants
  (testing "Recoup cannot target instants in graveyard"
    (let [db (-> (th/create-test-db {:mana {:colorless 1 :red 1}}) th/add-opponent)
          [db _recoup-id] (th/add-card-to-zone db :recoup :hand :player-1)
          ;; Add an instant to graveyard (dark-ritual is instant)
          [db _] (th/add-cards-to-graveyard db [:dark-ritual] :player-1)]
      (is (false? (targeting/has-valid-targets? db :player-1 recoup/card))
          "Should not have valid target with only instant in graveyard"))))


(deftest test-recoup-does-not-target-opponent-graveyard
  (testing "Recoup only targets own graveyard"
    (let [db (-> (th/create-test-db {:mana {:colorless 1 :red 1}}) th/add-opponent)
          [db _recoup-id] (th/add-card-to-zone db :recoup :hand :player-1)
          ;; Add sorcery to opponent's graveyard
          [db _] (th/add-cards-to-graveyard db [:careful-study] :player-2)]
      (is (false? (targeting/has-valid-targets? db :player-1 recoup/card))
          "Should not target sorcery in opponent's graveyard"))))


;; === Grant Flashback Tests ===

(deftest test-recoup-grants-flashback-on-resolution
  (testing "After Recoup resolves, target has granted flashback"
    (let [db (-> (th/create-test-db {:mana {:colorless 1 :red 1}}) th/add-opponent)
          [db recoup-id] (th/add-card-to-zone db :recoup :hand :player-1)
          [db [sorcery-id]] (th/add-cards-to-graveyard db [:careful-study] :player-1)
          db-cast (th/cast-with-target db :player-1 recoup-id sorcery-id)
          ;; Resolve Recoup via production path
          db-resolved (:db (resolution/resolve-one-item db-cast))
          ;; Check grants on target
          flashback-grants (grants/get-grants-by-type db-resolved sorcery-id :alternate-cost)]
      (is (= 1 (count flashback-grants))
          "Target should have 1 alternate-cost grant")
      (is (= :granted-flashback (get-in (first flashback-grants) [:grant/data :alternate/id]))
          "Grant should be :granted-flashback"))))


(deftest test-granted-flashback-uses-original-mana-cost
  (testing "Granted flashback cost equals target's mana cost"
    (let [db (-> (th/create-test-db {:mana {:colorless 1 :red 1}}) th/add-opponent)
          [db recoup-id] (th/add-card-to-zone db :recoup :hand :player-1)
          ;; Add careful-study (costs {U})
          [db [sorcery-id]] (th/add-cards-to-graveyard db [:careful-study] :player-1)
          db-cast (th/cast-with-target db :player-1 recoup-id sorcery-id)
          db-resolved (:db (resolution/resolve-one-item db-cast))
          ;; Get the granted flashback
          flashback-grants (grants/get-grants-by-type db-resolved sorcery-id :alternate-cost)
          granted-cost (get-in (first flashback-grants) [:grant/data :alternate/mana-cost])]
      ;; Careful Study costs {U} = {:blue 1}
      (is (= {:blue 1} granted-cost)
          "Granted flashback should cost the target's mana cost {:blue 1}"))))


(deftest test-granted-flashback-exiles-on-resolve
  (testing "Card cast via granted flashback exiles after resolution"
    (let [db (-> (th/create-test-db {:mana {:colorless 1 :red 1}}) th/add-opponent)
          [db recoup-id] (th/add-card-to-zone db :recoup :hand :player-1)
          [db [sorcery-id]] (th/add-cards-to-graveyard db [:careful-study] :player-1)
          db-cast (th/cast-with-target db :player-1 recoup-id sorcery-id)
          db-resolved (:db (resolution/resolve-one-item db-cast))
          flashback-grants (grants/get-grants-by-type db-resolved sorcery-id :alternate-cost)
          on-resolve (get-in (first flashback-grants) [:grant/data :alternate/on-resolve])]
      (is (= :exile on-resolve)
          "Granted flashback should exile on resolve"))))


(deftest test-granted-flashback-full-flow-exile
  (testing "Full flow: Cast via granted flashback stores :exile on-resolve in cast mode"
    (let [db (-> (th/create-test-db {:mana {:colorless 1 :red 1 :blue 1}}) th/add-opponent)
          [db recoup-id] (th/add-card-to-zone db :recoup :hand :player-1)
          [db [sorcery-id]] (th/add-cards-to-graveyard db [:careful-study] :player-1)
          db-cast (th/cast-with-target db :player-1 recoup-id sorcery-id)
          db-recoup-resolved (:db (resolution/resolve-one-item db-cast))
          ;; Verify sorcery has granted flashback
          _ (is (= 1 (count (grants/get-grants-by-type db-recoup-resolved sorcery-id :alternate-cost)))
                "Sorcery should have flashback grant")
          ;; Get casting modes for sorcery in graveyard
          modes (rules/get-casting-modes db-recoup-resolved :player-1 sorcery-id)
          flashback-mode (first (filter #(= :granted-flashback (:mode/id %)) modes))]
      ;; Mode should exist and have :exile on-resolve
      (is (= :exile (:mode/on-resolve flashback-mode)) "Mode should have :exile on-resolve")
      ;; Cast with the flashback mode — rules/cast-spell-mode required (flashback alternate-cost mode)
      (let [db-fb-cast (rules/cast-spell-mode db-recoup-resolved :player-1 sorcery-id flashback-mode)
            ;; Verify cast mode stored on object
            sorcery-obj (q/get-object db-fb-cast sorcery-id)
            stored-mode (:object/cast-mode sorcery-obj)]
        (is (= :exile (:mode/on-resolve stored-mode)) "Stored mode should have :exile on-resolve")))))


;; === Fizzle Tests ===

(deftest test-recoup-fizzles-if-target-removed
  (testing "Recoup fizzles if target is removed before resolution"
    (let [db (-> (th/create-test-db {:mana {:colorless 1 :red 1}}) th/add-opponent)
          [db recoup-id] (th/add-card-to-zone db :recoup :hand :player-1)
          [db [sorcery-id]] (th/add-cards-to-graveyard db [:careful-study] :player-1)
          db-cast (th/cast-with-target db :player-1 recoup-id sorcery-id)
          ;; Remove the target before resolution (simulate Tormod's Crypt)
          db-target-exiled (zones/move-to-zone* db-cast sorcery-id :exile)
          db-resolved (:db (resolution/resolve-one-item db-target-exiled))]
      ;; Recoup should be in graveyard (fizzled, not flashback cast)
      (is (= :graveyard (th/get-object-zone db-resolved recoup-id))
          "Recoup should go to graveyard after fizzling")
      ;; Target should have no grants (Recoup effect didn't happen)
      (is (empty? (q/get-grants db-resolved sorcery-id))
          "Target should have no grants (Recoup fizzled)"))))


(deftest test-recoup-still-resolves-if-other-sorceries-exist
  (testing "Fizzle is per-target, not based on other cards existing"
    ;; If target is removed, Recoup fizzles even if other valid sorceries exist
    (let [db (-> (th/create-test-db {:mana {:colorless 1 :red 1}}) th/add-opponent)
          [db recoup-id] (th/add-card-to-zone db :recoup :hand :player-1)
          [db [sorcery1-id sorcery2-id]] (th/add-cards-to-graveyard db [:careful-study :merchant-scroll] :player-1)
          ;; Target sorcery1
          db-cast (th/cast-with-target db :player-1 recoup-id sorcery1-id)
          ;; Remove sorcery1 (even though sorcery2 still exists)
          db-target-exiled (zones/move-to-zone* db-cast sorcery1-id :exile)
          db-resolved (:db (resolution/resolve-one-item db-target-exiled))]
      ;; Recoup fizzles (target invalid)
      (is (= :graveyard (th/get-object-zone db-resolved recoup-id))
          "Recoup should fizzle when its specific target is removed")
      ;; sorcery2 should NOT have grants
      (is (empty? (q/get-grants db-resolved sorcery2-id))
          "Other sorceries should not gain flashback when Recoup fizzles"))))


;; === Cleanup Expiration Tests ===

(deftest test-granted-flashback-expires-at-cleanup
  (testing "Granted flashback is removed at cleanup phase"
    (let [db (-> (th/create-test-db {:mana {:colorless 1 :red 1}}) th/add-opponent)
          [db recoup-id] (th/add-card-to-zone db :recoup :hand :player-1)
          [db [sorcery-id]] (th/add-cards-to-graveyard db [:careful-study] :player-1)
          db-cast (th/cast-with-target db :player-1 recoup-id sorcery-id)
          db-resolved (:db (resolution/resolve-one-item db-cast))
          ;; Verify grant exists
          _ (is (= 1 (count (q/get-grants db-resolved sorcery-id)))
                "Grant should exist before cleanup")
          db-expired (grants/expire-grants db-resolved 1 :cleanup)]
      (is (empty? (q/get-grants db-expired sorcery-id))
          "Grant should be removed at cleanup phase"))))


(deftest test-granted-flashback-does-not-expire-before-cleanup
  (testing "Granted flashback remains active during main phase"
    (let [db (-> (th/create-test-db {:mana {:colorless 1 :red 1}}) th/add-opponent)
          [db recoup-id] (th/add-card-to-zone db :recoup :hand :player-1)
          [db [sorcery-id]] (th/add-cards-to-graveyard db [:careful-study] :player-1)
          db-cast (th/cast-with-target db :player-1 recoup-id sorcery-id)
          db-resolved (:db (resolution/resolve-one-item db-cast))
          db-not-expired (grants/expire-grants db-resolved 1 :main1)]
      (is (= 1 (count (q/get-grants db-not-expired sorcery-id)))
          "Grant should NOT be removed during main phase"))))


;; === Flashback Recoup Tests ===

(deftest test-flashback-recoup-castable-from-graveyard
  (testing "Recoup can be cast from graveyard via flashback"
    (let [db (-> (th/create-test-db {:mana {:colorless 3 :red 1}}) th/add-opponent)
          [db recoup-id] (th/add-card-to-zone db :recoup :graveyard :player-1)
          ;; Add a sorcery target (required for Recoup)
          [db _sorcery-id] (th/add-cards-to-graveyard db [:careful-study] :player-1)]
      (is (rules/can-cast? db :player-1 recoup-id)
          "Recoup should be castable from graveyard with {3}{R}")
      (let [modes (rules/get-casting-modes db :player-1 recoup-id)
            flashback-mode (first modes)]
        (is (= :flashback (:mode/id flashback-mode))
            "Mode should be flashback")))))


(deftest test-flashback-recoup-exiles-after-resolution
  (testing "Recoup cast via flashback exiles after resolution"
    (let [db (-> (th/create-test-db {:mana {:colorless 3 :red 1}}) th/add-opponent)
          [db recoup-id] (th/add-card-to-zone db :recoup :graveyard :player-1)
          [db [sorcery-id]] (th/add-cards-to-graveyard db [:careful-study] :player-1)
          db-cast (th/cast-with-target db :player-1 recoup-id sorcery-id)
          ;; Verify it's on stack with flashback mode
          _ (is (= :flashback (:mode/id (:object/cast-mode (q/get-object db-cast recoup-id))))
                "Cast mode should be flashback")
          db-resolved (:db (resolution/resolve-one-item db-cast))]
      (is (= :exile (th/get-object-zone db-resolved recoup-id))
          "Flashback Recoup should exile after resolution"))))


(deftest test-flashback-recoup-can-target-sorceries
  (testing "Flashback Recoup can still target sorceries in graveyard"
    (let [db (-> (th/create-test-db {:mana {:colorless 3 :red 1}}) th/add-opponent)
          [db recoup-id] (th/add-card-to-zone db :recoup :graveyard :player-1)
          [db [sorcery-id]] (th/add-cards-to-graveyard db [:careful-study] :player-1)
          db-cast (th/cast-with-target db :player-1 recoup-id sorcery-id)
          db-resolved (:db (resolution/resolve-one-item db-cast))
          flashback-grants (grants/get-grants-by-type db-resolved sorcery-id :alternate-cost)]
      (is (= 1 (count flashback-grants))
          "Target should have flashback grant from flashback Recoup"))))


;; === Integration Tests ===

(deftest test-full-flow-cast-recoup-grant-flashback-use-it
  (testing "Complete flow: cast Recoup, grant flashback, cast target via flashback"
    (let [db (-> (th/create-test-db {:mana {:colorless 1 :red 1 :blue 1}}) th/add-opponent)
          [db recoup-id] (th/add-card-to-zone db :recoup :hand :player-1)
          [db [sorcery-id]] (th/add-cards-to-graveyard db [:careful-study] :player-1)
          db-cast (th/cast-with-target db :player-1 recoup-id sorcery-id)
          db-resolved (:db (resolution/resolve-one-item db-cast))
          ;; Verify Recoup in graveyard, Careful Study has flashback
          _ (is (= :graveyard (th/get-object-zone db-resolved recoup-id))
                "Recoup should be in graveyard after resolution")
          _ (is (= 1 (count (q/get-grants db-resolved sorcery-id)))
                "Careful Study should have flashback grant")
          _ (is (= :graveyard (th/get-object-zone db-resolved sorcery-id))
                "Careful Study should still be in graveyard")
          modes (rules/get-casting-modes db-resolved :player-1 sorcery-id)]
      (is (= 1 (count modes))
          "Careful Study should have exactly 1 casting mode from graveyard")
      (is (some #(= :granted-flashback (:mode/id %)) modes)
          "One mode should be :granted-flashback"))))


;; === Flashback Zone Restriction Tests ===

(deftest test-flashback-from-exile-blocked
  ;; Bug caught: Allowing flashback from wrong zone (exile instead of graveyard)
  (testing "Flashback only works from graveyard, not exile"
    (let [db (-> (th/create-test-db {:mana {:colorless 3 :red 1}}) th/add-opponent)
          ;; Add Recoup directly to EXILE (not graveyard)
          [db recoup-id] (th/add-card-to-zone db :recoup :exile :player-1)
          ;; Add a sorcery target in graveyard (required for Recoup)
          [db _sorcery-id] (th/add-cards-to-graveyard db [:careful-study] :player-1)
          modes (rules/get-casting-modes db :player-1 recoup-id)]
      (is (empty? modes)
          "No casting modes should be available from exile")
      (is (false? (rules/can-cast? db :player-1 recoup-id))
          "Flashback should not work from exile zone"))))
