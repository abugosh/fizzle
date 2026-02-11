(ns fizzle.cards.recoup-test
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
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.cards.recoup :as recoup]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.zones :as zones]
    [fizzle.events.selection :as selection]))


;; === Test Helpers ===

(defn create-test-db
  "Create a game state with all card definitions loaded."
  []
  (let [conn (d/create-conn schema)]
    ;; Transact all card definitions
    (d/transact! conn cards/all-cards)
    ;; Transact player
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}
                       {:player/id :player-2
                        :player/name "Opponent"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])
    ;; Transact game state
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}]))
    @conn))


(defn add-card-to-zone
  "Add a card object to a zone for a player.
   Returns [db object-id] tuple."
  [db card-id zone player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      db card-id)
        obj-id (random-uuid)]
    (d/transact! conn [{:object/id obj-id
                        :object/card card-eid
                        :object/zone zone
                        :object/owner player-eid
                        :object/controller player-eid
                        :object/tapped false}])
    [@conn obj-id]))


(defn add-cards-to-graveyard
  "Add multiple cards to a player's graveyard.
   Returns [db object-ids] tuple."
  [db card-ids player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    (loop [remaining-cards card-ids
           object-ids []]
      (if (empty? remaining-cards)
        [@conn object-ids]
        (let [card-id (first remaining-cards)
              obj-id (random-uuid)
              card-eid (d/q '[:find ?e .
                              :in $ ?cid
                              :where [?e :card/id ?cid]]
                            @conn card-id)]
          (d/transact! conn [{:object/id obj-id
                              :object/card card-eid
                              :object/zone :graveyard
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/tapped false}])
          (recur (rest remaining-cards)
                 (conj object-ids obj-id)))))))


(defn set-mana-pool
  "Set a player's mana pool to specific values."
  [db player-id mana-pool]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    (d/transact! conn [[:db/add player-eid :player/mana-pool mana-pool]])
    @conn))


(defn get-object-zone
  "Get the current zone of an object by its ID."
  [db object-id]
  (:object/zone (q/get-object db object-id)))


;; === Card Definition Tests ===

(deftest test-recoup-card-definition
  (testing "Recoup has correct mana cost, type, and color"
    (is (= {:colorless 1 :red 1} (:card/mana-cost recoup/recoup))
        "Recoup should cost {1}{R}")
    (is (= 2 (:card/cmc recoup/recoup))
        "Recoup should have CMC 2")
    (is (= #{:sorcery} (:card/types recoup/recoup))
        "Recoup should be a sorcery")
    (is (= #{:red} (:card/colors recoup/recoup))
        "Recoup should be red")))


(deftest test-recoup-has-targeting-requirement
  (testing "Recoup has :card/targeting for graveyard sorcery"
    (let [reqs (targeting/get-targeting-requirements recoup/recoup)]
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
      (is (= {:card/types #{:sorcery}} (:target/criteria (first reqs)))
          "Target criteria should require sorcery type")
      (is (true? (:target/required (first reqs)))
          "Target should be required"))))


(deftest test-recoup-has-grant-flashback-effect
  (testing "Recoup has :grant-flashback effect"
    (let [effects (:card/effects recoup/recoup)
          effect (first effects)]
      (is (= 1 (count effects))
          "Should have exactly 1 effect")
      (is (= :grant-flashback (:effect/type effect))
          "Effect type should be :grant-flashback")
      (is (= :graveyard-sorcery (:effect/target-ref effect))
          "Effect target-ref should match targeting id"))))


(deftest test-recoup-has-own-flashback
  (testing "Recoup has its own flashback for {3}{R}"
    (let [flashback (first (:card/alternate-costs recoup/recoup))]
      (is (some? flashback)
          "Recoup should have alternate costs")
      (is (= :flashback (:alternate/id flashback))
          "Alternate should be flashback")
      (is (= :graveyard (:alternate/zone flashback))
          "Flashback should be castable from graveyard")
      (is (= {:colorless 3 :red 1} (:alternate/mana-cost flashback))
          "Flashback should cost {3}{R}")
      (is (= :exile (:alternate/on-resolve flashback))
          "Flashback should exile on resolve"))))


;; === Cast Requirements Tests ===

(deftest test-recoup-requires-valid-target
  (testing "Recoup cannot be cast without sorcery in graveyard"
    (let [db (create-test-db)
          [db recoup-id] (add-card-to-zone db :recoup :hand :player-1)
          db (set-mana-pool db :player-1 {:colorless 1 :red 1})]
      ;; No sorceries in graveyard
      (is (false? (targeting/has-valid-targets? db :player-1 recoup/recoup))
          "Should not have valid targets with empty graveyard")
      (is (false? (rules/can-cast? db :player-1 recoup-id))
          "Recoup should not be castable without valid target"))))


(deftest test-recoup-finds-valid-sorcery-target
  (testing "Recoup can target sorcery in graveyard"
    (let [db (create-test-db)
          [db _recoup-id] (add-card-to-zone db :recoup :hand :player-1)
          ;; Add a sorcery to graveyard (careful-study is a sorcery)
          [db [sorcery-id]] (add-cards-to-graveyard db [:careful-study] :player-1)
          db (set-mana-pool db :player-1 {:colorless 1 :red 1})]
      (is (targeting/has-valid-targets? db :player-1 recoup/recoup)
          "Should have valid target with sorcery in graveyard")
      (let [req (first (targeting/get-targeting-requirements recoup/recoup))
            targets (targeting/find-valid-targets db :player-1 req)]
        (is (= [sorcery-id] targets)
            "Should find the sorcery as valid target")))))


(deftest test-recoup-does-not-target-instants
  (testing "Recoup cannot target instants in graveyard"
    (let [db (create-test-db)
          [db _recoup-id] (add-card-to-zone db :recoup :hand :player-1)
          ;; Add an instant to graveyard (dark-ritual is instant)
          [db _] (add-cards-to-graveyard db [:dark-ritual] :player-1)
          db (set-mana-pool db :player-1 {:colorless 1 :red 1})]
      (is (false? (targeting/has-valid-targets? db :player-1 recoup/recoup))
          "Should not have valid target with only instant in graveyard"))))


(deftest test-recoup-does-not-target-opponent-graveyard
  (testing "Recoup only targets own graveyard"
    (let [db (create-test-db)
          [db _recoup-id] (add-card-to-zone db :recoup :hand :player-1)
          ;; Add sorcery to opponent's graveyard
          [db _] (add-cards-to-graveyard db [:careful-study] :player-2)
          db (set-mana-pool db :player-1 {:colorless 1 :red 1})]
      (is (false? (targeting/has-valid-targets? db :player-1 recoup/recoup))
          "Should not target sorcery in opponent's graveyard"))))


;; === Grant Flashback Tests ===

(deftest test-recoup-grants-flashback-on-resolution
  (testing "After Recoup resolves, target has granted flashback"
    (let [db (create-test-db)
          [db recoup-id] (add-card-to-zone db :recoup :hand :player-1)
          [db [sorcery-id]] (add-cards-to-graveyard db [:careful-study] :player-1)
          db (set-mana-pool db :player-1 {:colorless 1 :red 1})
          ;; Cast Recoup with targeting
          result (selection/cast-spell-with-targeting db :player-1 recoup-id)
          selection (assoc (:pending-target-selection result)
                           :selection/selected #{sorcery-id})
          db-cast (selection/confirm-cast-time-target (:db result) selection)
          ;; Resolve Recoup
          resolve-result (selection/resolve-spell-with-selection db-cast :player-1 recoup-id)
          db-resolved (:db resolve-result)
          ;; Check grants on target
          flashback-grants (grants/get-grants-by-type db-resolved sorcery-id :alternate-cost)]
      (is (= 1 (count flashback-grants))
          "Target should have 1 alternate-cost grant")
      (is (= :granted-flashback (get-in (first flashback-grants) [:grant/data :alternate/id]))
          "Grant should be :granted-flashback"))))


(deftest test-granted-flashback-uses-original-mana-cost
  (testing "Granted flashback cost equals target's mana cost"
    (let [db (create-test-db)
          [db recoup-id] (add-card-to-zone db :recoup :hand :player-1)
          ;; Add careful-study (costs {U})
          [db [sorcery-id]] (add-cards-to-graveyard db [:careful-study] :player-1)
          db (set-mana-pool db :player-1 {:colorless 1 :red 1})
          ;; Cast and resolve Recoup
          result (selection/cast-spell-with-targeting db :player-1 recoup-id)
          selection (assoc (:pending-target-selection result)
                           :selection/selected #{sorcery-id})
          db-cast (selection/confirm-cast-time-target (:db result) selection)
          resolve-result (selection/resolve-spell-with-selection db-cast :player-1 recoup-id)
          db-resolved (:db resolve-result)
          ;; Get the granted flashback
          flashback-grants (grants/get-grants-by-type db-resolved sorcery-id :alternate-cost)
          granted-cost (get-in (first flashback-grants) [:grant/data :alternate/mana-cost])]
      ;; Careful Study costs {U} = {:blue 1}
      (is (= {:blue 1} granted-cost)
          "Granted flashback should cost the target's mana cost {:blue 1}"))))


(deftest test-granted-flashback-exiles-on-resolve
  (testing "Card cast via granted flashback exiles after resolution"
    (let [db (create-test-db)
          [db recoup-id] (add-card-to-zone db :recoup :hand :player-1)
          [db [sorcery-id]] (add-cards-to-graveyard db [:careful-study] :player-1)
          db (set-mana-pool db :player-1 {:colorless 1 :red 1})
          ;; Cast and resolve Recoup
          result (selection/cast-spell-with-targeting db :player-1 recoup-id)
          selection (assoc (:pending-target-selection result)
                           :selection/selected #{sorcery-id})
          db-cast (selection/confirm-cast-time-target (:db result) selection)
          resolve-result (selection/resolve-spell-with-selection db-cast :player-1 recoup-id)
          db-resolved (:db resolve-result)]
      ;; Check grant has exile on resolve
      (let [flashback-grants (grants/get-grants-by-type db-resolved sorcery-id :alternate-cost)
            on-resolve (get-in (first flashback-grants) [:grant/data :alternate/on-resolve])]
        (is (= :exile on-resolve)
            "Granted flashback should exile on resolve")))))


(deftest test-granted-flashback-full-flow-exile
  (testing "Full flow: Cast via granted flashback stores :exile on-resolve in cast mode"
    (let [db (create-test-db)
          [db recoup-id] (add-card-to-zone db :recoup :hand :player-1)
          [db [sorcery-id]] (add-cards-to-graveyard db [:careful-study] :player-1)
          db (set-mana-pool db :player-1 {:colorless 1 :red 1 :blue 1})
          ;; Cast and resolve Recoup
          result (selection/cast-spell-with-targeting db :player-1 recoup-id)
          selection (assoc (:pending-target-selection result)
                           :selection/selected #{sorcery-id})
          db-cast (selection/confirm-cast-time-target (:db result) selection)
          resolve-result (selection/resolve-spell-with-selection db-cast :player-1 recoup-id)
          db-recoup-resolved (:db resolve-result)
          ;; Verify sorcery has granted flashback
          _ (is (= 1 (count (grants/get-grants-by-type db-recoup-resolved sorcery-id :alternate-cost)))
                "Sorcery should have flashback grant")
          ;; Get casting modes for sorcery in graveyard
          modes (rules/get-casting-modes db-recoup-resolved :player-1 sorcery-id)
          flashback-mode (first (filter #(= :granted-flashback (:mode/id %)) modes))]
      ;; Mode should exist and have :exile on-resolve
      (is (some? flashback-mode) "Should find granted-flashback mode")
      (is (= :exile (:mode/on-resolve flashback-mode)) "Mode should have :exile on-resolve")
      ;; Cast with the flashback mode
      (let [db-fb-cast (rules/cast-spell-mode db-recoup-resolved :player-1 sorcery-id flashback-mode)
            ;; Verify cast mode stored on object
            sorcery-obj (q/get-object db-fb-cast sorcery-id)
            stored-mode (:object/cast-mode sorcery-obj)]
        (is (some? stored-mode) "Cast mode should be stored on object")
        (is (= :exile (:mode/on-resolve stored-mode)) "Stored mode should have :exile on-resolve")))))


;; === Fizzle Tests ===

(deftest test-recoup-fizzles-if-target-removed
  (testing "Recoup fizzles if target is removed before resolution"
    (let [db (create-test-db)
          [db recoup-id] (add-card-to-zone db :recoup :hand :player-1)
          [db [sorcery-id]] (add-cards-to-graveyard db [:careful-study] :player-1)
          db (set-mana-pool db :player-1 {:colorless 1 :red 1})
          ;; Cast Recoup with targeting
          result (selection/cast-spell-with-targeting db :player-1 recoup-id)
          selection (assoc (:pending-target-selection result)
                           :selection/selected #{sorcery-id})
          db-cast (selection/confirm-cast-time-target (:db result) selection)
          ;; Remove the target before resolution (simulate Tormod's Crypt)
          db-target-exiled (zones/move-to-zone db-cast sorcery-id :exile)
          ;; Try to resolve Recoup
          resolve-result (selection/resolve-spell-with-selection db-target-exiled :player-1 recoup-id)
          db-resolved (:db resolve-result)]
      ;; Recoup should be in graveyard (fizzled, not flashback cast)
      (is (= :graveyard (get-object-zone db-resolved recoup-id))
          "Recoup should go to graveyard after fizzling")
      ;; Target should have no grants (Recoup effect didn't happen)
      (let [target-grants (grants/get-grants db-resolved sorcery-id)]
        (is (empty? target-grants)
            "Target should have no grants (Recoup fizzled)")))))


(deftest test-recoup-still-resolves-if-other-sorceries-exist
  (testing "Fizzle is per-target, not based on other cards existing"
    ;; If target is removed, Recoup fizzles even if other valid sorceries exist
    (let [db (create-test-db)
          [db recoup-id] (add-card-to-zone db :recoup :hand :player-1)
          [db [sorcery1-id sorcery2-id]] (add-cards-to-graveyard db [:careful-study :merchant-scroll] :player-1)
          db (set-mana-pool db :player-1 {:colorless 1 :red 1})
          ;; Target sorcery1
          result (selection/cast-spell-with-targeting db :player-1 recoup-id)
          selection (assoc (:pending-target-selection result)
                           :selection/selected #{sorcery1-id})
          db-cast (selection/confirm-cast-time-target (:db result) selection)
          ;; Remove sorcery1 (even though sorcery2 still exists)
          db-target-exiled (zones/move-to-zone db-cast sorcery1-id :exile)
          ;; Resolve - should fizzle
          resolve-result (selection/resolve-spell-with-selection db-target-exiled :player-1 recoup-id)
          db-resolved (:db resolve-result)]
      ;; Recoup fizzles (target invalid)
      (is (= :graveyard (get-object-zone db-resolved recoup-id))
          "Recoup should fizzle when its specific target is removed")
      ;; sorcery2 should NOT have grants
      (is (empty? (grants/get-grants db-resolved sorcery2-id))
          "Other sorceries should not gain flashback when Recoup fizzles"))))


;; === Cleanup Expiration Tests ===

(deftest test-granted-flashback-expires-at-cleanup
  (testing "Granted flashback is removed at cleanup phase"
    (let [db (create-test-db)
          [db recoup-id] (add-card-to-zone db :recoup :hand :player-1)
          [db [sorcery-id]] (add-cards-to-graveyard db [:careful-study] :player-1)
          db (set-mana-pool db :player-1 {:colorless 1 :red 1})
          ;; Cast and resolve Recoup
          result (selection/cast-spell-with-targeting db :player-1 recoup-id)
          selection (assoc (:pending-target-selection result)
                           :selection/selected #{sorcery-id})
          db-cast (selection/confirm-cast-time-target (:db result) selection)
          resolve-result (selection/resolve-spell-with-selection db-cast :player-1 recoup-id)
          db-resolved (:db resolve-result)
          ;; Verify grant exists
          _ (is (= 1 (count (grants/get-grants db-resolved sorcery-id)))
                "Grant should exist before cleanup")
          ;; Run grant expiration (at turn 1, cleanup phase)
          db-expired (grants/expire-grants db-resolved 1 :cleanup)]
      ;; Grant should be removed
      (is (empty? (grants/get-grants db-expired sorcery-id))
          "Grant should be removed at cleanup phase"))))


(deftest test-granted-flashback-does-not-expire-before-cleanup
  (testing "Granted flashback remains active during main phase"
    (let [db (create-test-db)
          [db recoup-id] (add-card-to-zone db :recoup :hand :player-1)
          [db [sorcery-id]] (add-cards-to-graveyard db [:careful-study] :player-1)
          db (set-mana-pool db :player-1 {:colorless 1 :red 1})
          ;; Cast and resolve Recoup
          result (selection/cast-spell-with-targeting db :player-1 recoup-id)
          selection (assoc (:pending-target-selection result)
                           :selection/selected #{sorcery-id})
          db-cast (selection/confirm-cast-time-target (:db result) selection)
          resolve-result (selection/resolve-spell-with-selection db-cast :player-1 recoup-id)
          db-resolved (:db resolve-result)
          ;; Try to expire at main1 phase (before cleanup)
          db-not-expired (grants/expire-grants db-resolved 1 :main1)]
      ;; Grant should still exist
      (is (= 1 (count (grants/get-grants db-not-expired sorcery-id)))
          "Grant should NOT be removed during main phase"))))


;; === Flashback Recoup Tests ===

(deftest test-flashback-recoup-castable-from-graveyard
  (testing "Recoup can be cast from graveyard via flashback"
    (let [db (create-test-db)
          [db recoup-id] (add-card-to-zone db :recoup :graveyard :player-1)
          ;; Add a sorcery target (required for Recoup)
          [db _sorcery-id] (add-cards-to-graveyard db [:careful-study] :player-1)
          db (set-mana-pool db :player-1 {:colorless 3 :red 1})]
      (is (rules/can-cast? db :player-1 recoup-id)
          "Recoup should be castable from graveyard with {3}{R}")
      (let [modes (rules/get-casting-modes db :player-1 recoup-id)
            flashback-mode (first modes)]
        (is (= :flashback (:mode/id flashback-mode))
            "Mode should be flashback")))))


(deftest test-flashback-recoup-exiles-after-resolution
  (testing "Recoup cast via flashback exiles after resolution"
    (let [db (create-test-db)
          [db recoup-id] (add-card-to-zone db :recoup :graveyard :player-1)
          [db [sorcery-id]] (add-cards-to-graveyard db [:careful-study] :player-1)
          db (set-mana-pool db :player-1 {:colorless 3 :red 1})
          ;; Cast Recoup via flashback
          result (selection/cast-spell-with-targeting db :player-1 recoup-id)
          selection (assoc (:pending-target-selection result)
                           :selection/selected #{sorcery-id})
          db-cast (selection/confirm-cast-time-target (:db result) selection)
          ;; Verify it's on stack with flashback mode
          _ (is (= :flashback (:mode/id (:object/cast-mode (q/get-object db-cast recoup-id))))
                "Cast mode should be flashback")
          ;; Resolve
          resolve-result (selection/resolve-spell-with-selection db-cast :player-1 recoup-id)
          db-resolved (:db resolve-result)]
      (is (= :exile (get-object-zone db-resolved recoup-id))
          "Flashback Recoup should exile after resolution"))))


(deftest test-flashback-recoup-can-target-sorceries
  (testing "Flashback Recoup can still target sorceries in graveyard"
    (let [db (create-test-db)
          [db recoup-id] (add-card-to-zone db :recoup :graveyard :player-1)
          [db [sorcery-id]] (add-cards-to-graveyard db [:careful-study] :player-1)
          db (set-mana-pool db :player-1 {:colorless 3 :red 1})
          ;; Cast and resolve flashback Recoup
          result (selection/cast-spell-with-targeting db :player-1 recoup-id)
          selection (assoc (:pending-target-selection result)
                           :selection/selected #{sorcery-id})
          db-cast (selection/confirm-cast-time-target (:db result) selection)
          resolve-result (selection/resolve-spell-with-selection db-cast :player-1 recoup-id)
          db-resolved (:db resolve-result)
          ;; Check target got flashback
          flashback-grants (grants/get-grants-by-type db-resolved sorcery-id :alternate-cost)]
      (is (= 1 (count flashback-grants))
          "Target should have flashback grant from flashback Recoup"))))


;; === Integration Tests ===

(deftest test-full-flow-cast-recoup-grant-flashback-use-it
  (testing "Complete flow: cast Recoup, grant flashback, cast target via flashback"
    (let [db (create-test-db)
          [db recoup-id] (add-card-to-zone db :recoup :hand :player-1)
          ;; Add careful-study (costs {U}) to graveyard
          [db [sorcery-id]] (add-cards-to-graveyard db [:careful-study] :player-1)
          ;; Give player mana for Recoup ({1}{R}) and later for Careful Study ({U})
          db (set-mana-pool db :player-1 {:colorless 1 :red 1 :blue 1})
          ;; Step 1: Cast Recoup targeting Careful Study
          result (selection/cast-spell-with-targeting db :player-1 recoup-id)
          selection (assoc (:pending-target-selection result)
                           :selection/selected #{sorcery-id})
          db-cast (selection/confirm-cast-time-target (:db result) selection)
          ;; Step 2: Resolve Recoup
          resolve-result (selection/resolve-spell-with-selection db-cast :player-1 recoup-id)
          db-resolved (:db resolve-result)
          ;; Verify Recoup in graveyard, Careful Study has flashback
          _ (is (= :graveyard (get-object-zone db-resolved recoup-id))
                "Recoup should be in graveyard after resolution")
          _ (is (= 1 (count (grants/get-grants db-resolved sorcery-id)))
                "Careful Study should have flashback grant")
          ;; Step 3: Verify Careful Study can be cast from graveyard
          _ (is (= :graveyard (get-object-zone db-resolved sorcery-id))
                "Careful Study should still be in graveyard")
          modes (rules/get-casting-modes db-resolved :player-1 sorcery-id)]
      ;; Should have granted flashback mode available
      (is (seq modes)
          "Careful Study should have casting modes from graveyard")
      (is (some #(= :granted-flashback (:mode/id %)) modes)
          "One mode should be :granted-flashback"))))


;; === Flashback Zone Restriction Tests ===

(deftest test-flashback-from-exile-blocked
  ;; Bug caught: Allowing flashback from wrong zone (exile instead of graveyard)
  (testing "Flashback only works from graveyard, not exile"
    (let [db (create-test-db)
          ;; Add Recoup directly to EXILE (not graveyard)
          [db recoup-id] (add-card-to-zone db :recoup :exile :player-1)
          ;; Add a sorcery target in graveyard (required for Recoup)
          [db _sorcery-id] (add-cards-to-graveyard db [:careful-study] :player-1)
          ;; Add mana to pay flashback cost {3}{R}
          db (set-mana-pool db :player-1 {:colorless 3 :red 1})
          ;; Get casting modes - should be empty since card is in exile
          modes (rules/get-casting-modes db :player-1 recoup-id)]
      ;; Flashback should NOT be available from exile
      (is (empty? modes)
          "No casting modes should be available from exile")
      ;; Verify can-cast? also returns false
      (is (false? (rules/can-cast? db :player-1 recoup-id))
          "Flashback should not work from exile zone"))))
