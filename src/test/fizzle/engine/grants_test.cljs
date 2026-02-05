(ns fizzle.engine.grants-test
  "Tests for the grant system.

   Grants are temporary modifications to card instances (objects).
   They support:
   - Adding alternate costs (flashback granted by Recoup)
   - Expiration at specific turn/phase
   - Source tracking for removal"
  (:require
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [datascript.core :as d]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.db.queries :as q]
    [fizzle.engine.events :as game-events]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.trigger-dispatch :as dispatch]
    [fizzle.engine.trigger-registry :as registry]
    [fizzle.engine.turn-based :as turn-based]))


;; === Test Helpers ===

(defn add-card-to-zone
  "Add a card and create an object in specified zone.
   Returns [obj-id db] tuple."
  [db player-id card zone]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)]
    (when-not (d/q '[:find ?e .
                     :in $ ?cid
                     :where [?e :card/id ?cid]]
                   @conn (:card/id card))
      (d/transact! conn [card]))
    (let [card-eid (d/q '[:find ?e .
                          :in $ ?cid
                          :where [?e :card/id ?cid]]
                        @conn (:card/id card))
          obj-id (random-uuid)
          obj {:object/id obj-id
               :object/card card-eid
               :object/zone zone
               :object/owner player-eid
               :object/controller player-eid
               :object/tapped false}]
      (d/transact! conn [obj])
      [obj-id @conn])))


(defn add-player
  "Add a player to the db with default attributes.
   Returns updated db."
  [db player-id]
  (let [conn (d/conn-from-db db)]
    (d/transact! conn [{:player/id player-id
                        :player/name (str "Player " player-id)
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])
    @conn))


(def test-sorcery
  {:card/id :test-sorcery
   :card/name "Test Sorcery"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :black 1}
   :card/colors #{:black}
   :card/types #{:sorcery}
   :card/text "Test sorcery for grants."
   :card/effects []})


;; === Add Grant Tests ===

(deftest add-grant-test
  (testing "add-grant adds a grant to an object"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          grant {:grant/id (random-uuid)
                 :grant/type :alternate-cost
                 :grant/source (random-uuid)
                 :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                 :grant/data {:alternate/id :granted-flashback
                              :alternate/zone :graveyard
                              :alternate/mana-cost {:colorless 1 :black 1}
                              :alternate/on-resolve :exile}}
          db' (grants/add-grant db obj-id grant)
          obj-grants (grants/get-grants db' obj-id)]
      (is (= 1 (count obj-grants))
          "Object should have one grant")
      (is (= :alternate-cost (:grant/type (first obj-grants)))
          "Grant type should be :alternate-cost"))))


(deftest add-multiple-grants-test
  (testing "add-grant can add multiple grants to same object"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          grant1 {:grant/id (random-uuid)
                  :grant/type :alternate-cost
                  :grant/source (random-uuid)
                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                  :grant/data {:alternate/id :flashback-1}}
          grant2 {:grant/id (random-uuid)
                  :grant/type :keyword
                  :grant/source (random-uuid)
                  :grant/expires {:expires/turn 2 :expires/phase :cleanup}
                  :grant/data {:keyword :haste}}
          db' (-> db
                  (grants/add-grant obj-id grant1)
                  (grants/add-grant obj-id grant2))
          obj-grants (grants/get-grants db' obj-id)]
      (is (= 2 (count obj-grants))
          "Object should have two grants"))))


;; === Get Grants Tests ===

(deftest get-grants-empty-test
  (testing "get-grants returns empty vector for object without grants"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          obj-grants (grants/get-grants db obj-id)]
      (is (= [] obj-grants)
          "Object without grants should return empty vector"))))


(deftest get-grants-by-type-test
  (testing "get-grants-by-type filters by grant type"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          grant1 {:grant/id (random-uuid)
                  :grant/type :alternate-cost
                  :grant/source (random-uuid)
                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                  :grant/data {:alternate/id :flashback}}
          grant2 {:grant/id (random-uuid)
                  :grant/type :keyword
                  :grant/source (random-uuid)
                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                  :grant/data {:keyword :haste}}
          db' (-> db
                  (grants/add-grant obj-id grant1)
                  (grants/add-grant obj-id grant2))
          alt-cost-grants (grants/get-grants-by-type db' obj-id :alternate-cost)]
      (is (= 1 (count alt-cost-grants))
          "Should return only alternate-cost grants")
      (is (= :alternate-cost (:grant/type (first alt-cost-grants)))
          "Returned grant should be alternate-cost type"))))


;; === Remove Grant Tests ===

(deftest remove-grant-test
  (testing "remove-grant removes a specific grant by ID"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          grant-id (random-uuid)
          grant {:grant/id grant-id
                 :grant/type :alternate-cost
                 :grant/source (random-uuid)
                 :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                 :grant/data {:alternate/id :flashback}}
          db' (grants/add-grant db obj-id grant)
          db'' (grants/remove-grant db' obj-id grant-id)
          obj-grants (grants/get-grants db'' obj-id)]
      (is (= 0 (count obj-grants))
          "Grant should be removed"))))


(deftest remove-grants-by-source-test
  (testing "remove-grants-by-source removes all grants from a source"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          source-id (random-uuid)
          other-source-id (random-uuid)
          grant1 {:grant/id (random-uuid)
                  :grant/type :alternate-cost
                  :grant/source source-id
                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                  :grant/data {:alternate/id :flashback-1}}
          grant2 {:grant/id (random-uuid)
                  :grant/type :keyword
                  :grant/source source-id
                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                  :grant/data {:keyword :haste}}
          grant3 {:grant/id (random-uuid)
                  :grant/type :alternate-cost
                  :grant/source other-source-id
                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                  :grant/data {:alternate/id :flashback-other}}
          db' (-> db
                  (grants/add-grant obj-id grant1)
                  (grants/add-grant obj-id grant2)
                  (grants/add-grant obj-id grant3))
          db'' (grants/remove-grants-by-source db' source-id)
          obj-grants (grants/get-grants db'' obj-id)]
      (is (= 1 (count obj-grants))
          "Only grant from other source should remain")
      (is (= other-source-id (:grant/source (first obj-grants)))
          "Remaining grant should be from other source"))))


;; === Integration with Casting System ===

(deftest granted-alternate-cost-appears-in-casting-modes-test
  (testing "Granted alternate cost appears in get-casting-modes"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          ;; Grant flashback to the sorcery in graveyard
          grant {:grant/id (random-uuid)
                 :grant/type :alternate-cost
                 :grant/source (random-uuid)
                 :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                 :grant/data {:alternate/id :granted-flashback
                              :alternate/zone :graveyard
                              :alternate/mana-cost {:colorless 1 :black 1}
                              :alternate/on-resolve :exile}}
          db' (grants/add-grant db obj-id grant)
          ;; Get casting modes - should include the granted flashback
          modes (rules/get-casting-modes db' :player-1 obj-id)]
      (is (= 1 (count modes))
          "Should have one casting mode (granted flashback)")
      (is (= :granted-flashback (:mode/id (first modes)))
          "Mode should be the granted flashback"))))


(deftest granted-alternate-cost-castable-from-graveyard-test
  (testing "Card with granted flashback is castable from graveyard"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          ;; Grant flashback
          grant {:grant/id (random-uuid)
                 :grant/type :alternate-cost
                 :grant/source (random-uuid)
                 :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                 :grant/data {:alternate/id :granted-flashback
                              :alternate/zone :graveyard
                              :alternate/mana-cost {:colorless 1 :black 1}
                              :alternate/on-resolve :exile}}
          db' (-> db
                  (grants/add-grant obj-id grant)
                  (mana/add-mana :player-1 {:colorless 1 :black 1}))]
      (is (rules/can-cast? db' :player-1 obj-id)
          "Should be castable with granted flashback"))))


(deftest sorcery-not-castable-from-graveyard-without-grant-test
  (testing "Sorcery without flashback is not castable from graveyard"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          db' (mana/add-mana db :player-1 {:colorless 1 :black 1})]
      (is (not (rules/can-cast? db' :player-1 obj-id))
          "Should not be castable without flashback"))))


;; === Grant Expiration Tests ===

(deftest grant-expired-same-turn-same-phase-test
  (testing "Grant expires when current turn/phase matches expiration"
    (let [grant {:grant/id (random-uuid)
                 :grant/expires {:expires/turn 1 :expires/phase :cleanup}}]
      (is (grants/grant-expired? grant 1 :cleanup)
          "Grant should be expired at turn 1 cleanup"))))


(deftest grant-expired-past-turn-test
  (testing "Grant expires when current turn is past expiration turn"
    (let [grant {:grant/id (random-uuid)
                 :grant/expires {:expires/turn 1 :expires/phase :cleanup}}]
      (is (grants/grant-expired? grant 2 :main1)
          "Grant should be expired on turn 2"))))


(deftest grant-not-expired-same-turn-earlier-phase-test
  (testing "Grant not expired when same turn but earlier phase"
    (let [grant {:grant/id (random-uuid)
                 :grant/expires {:expires/turn 1 :expires/phase :cleanup}}]
      (is (not (grants/grant-expired? grant 1 :main1))
          "Grant should not be expired at main1 on turn 1"))))


(deftest grant-not-expired-earlier-turn-test
  (testing "Grant not expired when on earlier turn"
    (let [grant {:grant/id (random-uuid)
                 :grant/expires {:expires/turn 3 :expires/phase :cleanup}}]
      (is (not (grants/grant-expired? grant 2 :cleanup))
          "Grant should not be expired on turn 2"))))


(deftest grant-permanent-never-expires-test
  (testing "Permanent grant never expires"
    (let [grant {:grant/id (random-uuid)
                 :grant/expires {:expires/permanent true}}]
      (is (not (grants/grant-expired? grant 100 :cleanup))
          "Permanent grant should never expire"))))


(deftest expire-grants-removes-expired-test
  (testing "expire-grants removes expired grants from all objects"
    (let [db (init-game-state)
          [obj-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          grant1 {:grant/id (random-uuid)
                  :grant/type :alternate-cost
                  :grant/source (random-uuid)
                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                  :grant/data {:alternate/id :flashback-expires}}
          grant2 {:grant/id (random-uuid)
                  :grant/type :alternate-cost
                  :grant/source (random-uuid)
                  :grant/expires {:expires/turn 2 :expires/phase :cleanup}
                  :grant/data {:alternate/id :flashback-persists}}
          db' (-> db
                  (grants/add-grant obj-id grant1)
                  (grants/add-grant obj-id grant2))
          ;; Expire at turn 1, cleanup phase
          db'' (grants/expire-grants db' 1 :cleanup)
          remaining (grants/get-grants db'' obj-id)]
      (is (= 1 (count remaining))
          "Should have one remaining grant")
      (is (= :flashback-persists (get-in (first remaining) [:grant/data :alternate/id]))
          "Only the turn 2 grant should remain"))))


(deftest expire-grants-across-multiple-objects-test
  (testing "expire-grants handles multiple objects"
    (let [db (init-game-state)
          [obj-id1 db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          [obj-id2 db] (add-card-to-zone db :player-1
                                         (assoc test-sorcery :card/id :test-sorcery-2)
                                         :graveyard)
          grant1 {:grant/id (random-uuid)
                  :grant/type :alternate-cost
                  :grant/source (random-uuid)
                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                  :grant/data {:alternate/id :flashback-1}}
          grant2 {:grant/id (random-uuid)
                  :grant/type :alternate-cost
                  :grant/source (random-uuid)
                  :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                  :grant/data {:alternate/id :flashback-2}}
          db' (-> db
                  (grants/add-grant obj-id1 grant1)
                  (grants/add-grant obj-id2 grant2))
          db'' (grants/expire-grants db' 1 :cleanup)]
      (is (= 0 (count (grants/get-grants db'' obj-id1)))
          "First object should have no grants")
      (is (= 0 (count (grants/get-grants db'' obj-id2)))
          "Second object should have no grants"))))


;; === End-of-turn cleanup integration ===

(defn reset-registry
  "Fixture to clear trigger registry before/after each test."
  [f]
  (registry/clear-registry!)
  (f)
  (registry/clear-registry!))


(use-fixtures :each reset-registry)


(deftest cleanup-phase-expires-grants-integration-test
  (testing "Cleanup phase event dispatches trigger that expires grants"
    (turn-based/register-turn-based-actions!)
    (let [db (init-game-state)
          ;; Set game to turn 1
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          db (d/db-with db [[:db/add game-eid :game/turn 1]])
          ;; Add a card with a grant that expires at turn 1 cleanup
          [obj-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          grant {:grant/id (random-uuid)
                 :grant/type :alternate-cost
                 :grant/source (random-uuid)
                 :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                 :grant/data {:alternate/id :granted-flashback
                              :alternate/zone :graveyard
                              :alternate/mana-cost {:colorless 1 :black 1}
                              :alternate/on-resolve :exile}}
          db (grants/add-grant db obj-id grant)
          ;; Add mana so the card would be castable
          db (mana/add-mana db :player-1 {:colorless 1 :black 1})
          ;; Verify grant exists and card is castable before cleanup
          _ (is (= 1 (count (grants/get-grants db obj-id)))
                "Grant should exist before cleanup")
          _ (is (rules/can-cast? db :player-1 obj-id)
                "Card should be castable via granted flashback before cleanup")
          ;; Dispatch cleanup phase event
          event (game-events/phase-entered-event :cleanup 1 :player-1)
          db' (dispatch/dispatch-event db event)]
      ;; Grant should be removed
      (is (= 0 (count (grants/get-grants db' obj-id)))
          "Grant should be expired after cleanup phase")
      ;; Card should no longer be castable from graveyard
      (is (not (rules/can-cast? db' :player-1 obj-id))
          "Card should not be castable after grant expires"))))


(deftest cleanup-phase-preserves-future-grants-integration-test
  (testing "Cleanup phase preserves grants that expire on future turns"
    (turn-based/register-turn-based-actions!)
    (let [db (init-game-state)
          ;; Set game to turn 1
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          db (d/db-with db [[:db/add game-eid :game/turn 1]])
          ;; Add a card with a grant that expires at turn 2 cleanup (not yet)
          [obj-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          grant {:grant/id (random-uuid)
                 :grant/type :alternate-cost
                 :grant/source (random-uuid)
                 :grant/expires {:expires/turn 2 :expires/phase :cleanup}
                 :grant/data {:alternate/id :granted-flashback
                              :alternate/zone :graveyard
                              :alternate/mana-cost {:colorless 1 :black 1}
                              :alternate/on-resolve :exile}}
          db (grants/add-grant db obj-id grant)
          db (mana/add-mana db :player-1 {:colorless 1 :black 1})
          ;; Dispatch cleanup phase event for turn 1
          event (game-events/phase-entered-event :cleanup 1 :player-1)
          db' (dispatch/dispatch-event db event)]
      ;; Grant should still exist (expires turn 2)
      (is (= 1 (count (grants/get-grants db' obj-id)))
          "Grant should still exist (expires on turn 2)")
      ;; Card should still be castable
      (is (rules/can-cast? db' :player-1 obj-id)
          "Card should still be castable"))))


;; =====================================================
;; Player Grant Tests
;; =====================================================
;; Player grants extend the grants system to player entities.
;; This enables restrictions like "can't cast spells" from Orim's Chant.

(deftest add-player-grant-test
  (testing "adds grant to player with no existing grants"
    (let [db (init-game-state)
          test-grant {:grant/id (random-uuid)
                      :grant/type :restriction
                      :grant/source (random-uuid)
                      :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                      :grant/data {:restriction/type :cannot-cast-spells}}
          db' (grants/add-player-grant db :player-1 test-grant)]
      (is (= 1 (count (grants/get-player-grants db' :player-1)))
          "Player should have one grant")
      (is (= :restriction (:grant/type (first (grants/get-player-grants db' :player-1))))
          "Grant type should be :restriction"))))


(deftest get-player-grants-by-type-test
  (testing "filters grants by type"
    (let [db (init-game-state)
          db' (-> db
                  (grants/add-player-grant :player-1 {:grant/id (random-uuid)
                                                      :grant/type :restriction
                                                      :grant/source (random-uuid)
                                                      :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                                                      :grant/data {}})
                  (grants/add-player-grant :player-1 {:grant/id (random-uuid)
                                                      :grant/type :alternate-cost
                                                      :grant/source (random-uuid)
                                                      :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                                                      :grant/data {}}))]
      (is (= 1 (count (grants/get-player-grants-by-type db' :player-1 :restriction)))
          "Should return only restriction grants")
      (is (= 1 (count (grants/get-player-grants-by-type db' :player-1 :alternate-cost)))
          "Should return only alternate-cost grants"))))


(deftest remove-player-grant-test
  (testing "removes specific grant by id"
    (let [db (init-game-state)
          grant-id (random-uuid)
          db' (-> db
                  (grants/add-player-grant :player-1 {:grant/id grant-id
                                                      :grant/type :restriction
                                                      :grant/source (random-uuid)
                                                      :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                                                      :grant/data {}})
                  (grants/remove-player-grant :player-1 grant-id))]
      (is (= 0 (count (grants/get-player-grants db' :player-1)))
          "Grant should be removed"))))


(deftest expire-grants-includes-players-test
  (testing "expired player grants removed at cleanup"
    (let [db (init-game-state)
          expired-grant {:grant/id (random-uuid)
                         :grant/type :restriction
                         :grant/source (random-uuid)
                         :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                         :grant/data {:restriction/type :cannot-cast-spells}}
          db' (-> db
                  (grants/add-player-grant :player-1 expired-grant)
                  ;; Expire at turn 1, cleanup (should remove)
                  (grants/expire-grants 1 :cleanup))]
      (is (= 0 (count (grants/get-player-grants db' :player-1)))
          "Player grant should be expired"))))


(deftest non-expired-player-grants-preserved-test
  (testing "player grants expiring later are not removed"
    (let [db (init-game-state)
          future-grant {:grant/id (random-uuid)
                        :grant/type :restriction
                        :grant/source (random-uuid)
                        :grant/expires {:expires/turn 5 :expires/phase :cleanup}
                        :grant/data {:restriction/type :cannot-cast-spells}}
          db' (-> db
                  (grants/add-player-grant :player-1 future-grant)
                  ;; Expire at turn 2 (should NOT remove turn 5 grant)
                  (grants/expire-grants 2 :cleanup))]
      (is (= 1 (count (grants/get-player-grants db' :player-1)))
          "Player grant should not be expired (expires turn 5)"))))


(deftest independent-player-grants-test
  (testing "grants on one player don't affect another"
    (let [db (-> (init-game-state)
                 (add-player :opponent))
          db' (-> db
                  (grants/add-player-grant :player-1 {:grant/id (random-uuid)
                                                      :grant/type :restriction
                                                      :grant/source (random-uuid)
                                                      :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                                                      :grant/data {}})
                  (grants/add-player-grant :opponent {:grant/id (random-uuid)
                                                      :grant/type :restriction
                                                      :grant/source (random-uuid)
                                                      :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                                                      :grant/data {}}))]
      (is (= 1 (count (grants/get-player-grants db' :player-1)))
          "Player 1 should have one grant")
      (is (= 1 (count (grants/get-player-grants db' :opponent)))
          "Opponent should have one grant"))))


(deftest get-player-grants-empty-test
  (testing "player with no grants returns empty vector"
    (let [db (init-game-state)]
      (is (= [] (grants/get-player-grants db :player-1))
          "Player without grants should return empty vector"))))


(deftest expire-grants-handles-both-objects-and-players-test
  (testing "expire-grants removes expired grants from both objects and players"
    (let [db (init-game-state)
          ;; Add object with grant
          [obj-id db] (add-card-to-zone db :player-1 test-sorcery :graveyard)
          expired-object-grant {:grant/id (random-uuid)
                                :grant/type :alternate-cost
                                :grant/source (random-uuid)
                                :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                                :grant/data {:alternate/id :flashback}}
          expired-player-grant {:grant/id (random-uuid)
                                :grant/type :restriction
                                :grant/source (random-uuid)
                                :grant/expires {:expires/turn 1 :expires/phase :cleanup}
                                :grant/data {:restriction/type :cannot-cast-spells}}
          db' (-> db
                  (grants/add-grant obj-id expired-object-grant)
                  (grants/add-player-grant :player-1 expired-player-grant)
                  (grants/expire-grants 1 :cleanup))]
      (is (= 0 (count (grants/get-grants db' obj-id)))
          "Object grant should be expired")
      (is (= 0 (count (grants/get-player-grants db' :player-1)))
          "Player grant should be expired"))))
