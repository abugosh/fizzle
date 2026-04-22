(ns fizzle.engine.static-abilities-test
  "Unit tests for engine/static_abilities.cljs.

   Tests cover the new :untap-restriction functions:
   - get-untap-restrictions
   - permanent-untap-restricted?"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.static-abilities :as sa]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Test Helpers
;; =====================================================

(defn- add-restriction-card
  "Add a synthetic permanent with a :untap-restriction static ability to the battlefield.
   criteria: criteria map, e.g. {:match/types #{:land} :match/has-ability-type :activated}
   Returns [db object-id]."
  [db player-id criteria]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-id (keyword (str "test-restriction-" (random-uuid)))
        ability {:static/type :untap-restriction
                 :modifier/criteria criteria}
        _ (d/transact! conn [{:card/id card-id
                              :card/name "Test Restriction Card"
                              :card/cmc 2
                              :card/mana-cost {:colorless 2}
                              :card/colors #{}
                              :card/types #{:artifact}
                              :card/text "Test"
                              :card/static-abilities [ability]}])
        card-eid (d/q '[:find ?e .
                        :in $ ?cid
                        :where [?e :card/id ?cid]]
                      @conn card-id)
        object-id (random-uuid)
        _ (d/transact! conn [{:object/id object-id
                              :object/card card-eid
                              :object/zone :battlefield
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/tapped false}])]
    [@conn object-id]))


;; =====================================================
;; get-untap-restrictions tests
;; =====================================================

(deftest get-untap-restrictions-empty-battlefield-returns-empty
  (testing "No permanents on battlefield returns empty vector"
    (let [db (th/create-test-db)
          result (sa/get-untap-restrictions db)]
      (is (= [] result)
          "Expected empty vector when battlefield is empty"))))


(deftest get-untap-restrictions-only-non-restriction-statics-returns-empty
  (testing "Sphere of Resistance (:cost-modifier) on battlefield does not count as untap restriction"
    (let [db (th/create-test-db)
          ;; Add Sphere of Resistance (a :cost-modifier static ability) to battlefield
          [db _sphere-id] (th/add-card-to-zone db :sphere-of-resistance :battlefield :player-1)
          result (sa/get-untap-restrictions db)]
      (is (= [] result)
          "cost-modifier statics must not be returned as untap restrictions"))))


(deftest get-untap-restrictions-restriction-card-returns-one-entry
  (testing "Synthetic :untap-restriction card on battlefield returns exactly 1 entry"
    (let [db (th/create-test-db)
          criteria {:match/types #{:land} :match/has-ability-type :activated}
          [db _obj-id] (add-restriction-card db :player-1 criteria)
          result (sa/get-untap-restrictions db)]
      (is (= 1 (count result))
          "Should find exactly 1 untap restriction")
      ;; Assert the returned entry has the expected metadata shape
      (let [entry (first result)]
        (is (contains? entry :static-ability)
            "Entry must have :static-ability key")
        (is (contains? entry :source/controller)
            "Entry must have :source/controller key")
        (is (contains? entry :source/object-id)
            "Entry must have :source/object-id key")
        (is (= :untap-restriction (:static/type (:static-ability entry)))
            "Static ability type must be :untap-restriction")
        (is (= criteria (:modifier/criteria (:static-ability entry)))
            "Criteria must be preserved exactly")))))


;; =====================================================
;; permanent-untap-restricted? tests
;; =====================================================

(deftest permanent-untap-restricted?-no-restrictions-returns-false
  (testing "Returns false when no restrictions are on battlefield"
    (let [db (th/create-test-db)
          ;; Add Wasteland to battlefield — a land with :activated ability
          [db wasteland-id] (th/add-card-to-zone db :wasteland :battlefield :player-1)
          wasteland-eid (q/get-object-eid db wasteland-id)
          result (sa/permanent-untap-restricted? db wasteland-eid)]
      (is (= false result)
          "No restrictions active → permanent must not be restricted"))))


(deftest permanent-untap-restricted?-land-with-activated-matches-tsabos-web-style
  (testing "Wasteland (land with :activated ability) is restricted by Tsabo's Web style criteria"
    (let [db (th/create-test-db)
          ;; Tsabo's Web style restriction: land + has activated ability
          criteria {:match/types #{:land} :match/has-ability-type :activated}
          [db _restriction-id] (add-restriction-card db :player-1 criteria)
          ;; Wasteland: land with :activated ability — should be restricted
          [db wasteland-id] (th/add-card-to-zone db :wasteland :battlefield :player-1)
          wasteland-eid (q/get-object-eid db wasteland-id)
          ;; Island: land with only :mana ability — must NOT be restricted (contrasting object)
          [db island-id] (th/add-card-to-zone db :island :battlefield :player-1)
          island-eid (q/get-object-eid db island-id)]
      (is (= true (sa/permanent-untap-restricted? db wasteland-eid))
          "Wasteland (land with :activated) must be restricted")
      ;; Contrasting check: Island (no :activated) must not be restricted
      (is (= false (sa/permanent-untap-restricted? db island-eid))
          "Island (only :mana) must not be restricted — contrasting case"))))


(deftest permanent-untap-restricted?-island-does-not-match-tsabos-web-style
  (testing "Island (land with only :mana ability) is NOT restricted by Tsabo's Web style criteria"
    (let [db (th/create-test-db)
          criteria {:match/types #{:land} :match/has-ability-type :activated}
          [db _restriction-id] (add-restriction-card db :player-1 criteria)
          ;; Island: only has :mana ability
          [db island-id] (th/add-card-to-zone db :island :battlefield :player-1)
          island-eid (q/get-object-eid db island-id)
          ;; Wasteland: contrasting object that DOES match (prevents vacuous pass)
          [db wasteland-id] (th/add-card-to-zone db :wasteland :battlefield :player-1)
          wasteland-eid (q/get-object-eid db wasteland-id)]
      ;; First confirm the positive case works (contrasting object)
      (is (= true (sa/permanent-untap-restricted? db wasteland-eid))
          "Wasteland must match (contrasting object ensures Island's false is meaningful)")
      (is (= false (sa/permanent-untap-restricted? db island-eid))
          "Island with only :mana ability must not be restricted"))))


(deftest permanent-untap-restricted?-multiple-restrictions-or-semantics
  (testing "OR semantics: any matching restriction restricts the permanent"
    (let [db (th/create-test-db)
          ;; Restriction A: match on types only (lands)
          [db _restr-a] (add-restriction-card db :player-1 {:match/types #{:land}})
          ;; Restriction B: match on ability type only
          [db _restr-b] (add-restriction-card db :player-1 {:match/has-ability-type :activated})
          [db wasteland-id] (th/add-card-to-zone db :wasteland :battlefield :player-1)
          wasteland-eid (q/get-object-eid db wasteland-id)
          ;; Island: matches restriction A (is a land) but not restriction B (no :activated)
          [db island-id] (th/add-card-to-zone db :island :battlefield :player-1)
          island-eid (q/get-object-eid db island-id)]
      (is (= 2 (count (sa/get-untap-restrictions db)))
          "Should see both restrictions on battlefield")
      ;; Wasteland matches both A and B — OR semantics → restricted
      (is (= true (sa/permanent-untap-restricted? db wasteland-eid))
          "Wasteland matches both restrictions → true")
      ;; Island matches only restriction A (it's a land) — OR means it's still restricted
      (is (= true (sa/permanent-untap-restricted? db island-eid))
          "Island matches restriction A (land type) → true via OR semantics"))))
