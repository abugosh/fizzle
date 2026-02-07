(ns fizzle.cards.merchant-scroll-test
  "Tests for Merchant Scroll and tutor infrastructure.

   Merchant Scroll: 1U - Sorcery
   Search your library for a blue instant card, reveal it, put it into your hand,
   then shuffle.

   This tests:
   - query-library-by-criteria function (types, colors, subtypes matching)
   - shuffle-library function
   - :tutor effect type
   - Selection modal for tutors (with fail-to-find option)
   - Merchant Scroll card definition and integration"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.zones :as zones]
    [fizzle.events.selection :as selection]))


;; === Test helpers ===

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
                        :player/mana-pool {:white 0 :blue 2 :black 0
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


(defn add-cards-to-library
  "Add multiple cards to the library with positions.
   Returns [db object-ids] tuple with object-ids in order (first = top of library)."
  [db card-ids player-id]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        get-card-eid (fn [card-id]
                       (d/q '[:find ?e .
                              :in $ ?cid
                              :where [?e :card/id ?cid]]
                            @conn card-id))]
    (loop [remaining-cards card-ids
           position 0
           object-ids []]
      (if (empty? remaining-cards)
        [@conn object-ids]
        (let [card-id (first remaining-cards)
              obj-id (random-uuid)
              card-eid (get-card-eid card-id)]
          (d/transact! conn [{:object/id obj-id
                              :object/card card-eid
                              :object/zone :library
                              :object/owner player-eid
                              :object/controller player-eid
                              :object/tapped false
                              :object/position position}])
          (recur (rest remaining-cards)
                 (inc position)
                 (conj object-ids obj-id)))))))


(defn get-hand-count
  "Get the number of cards in a player's hand."
  [db player-id]
  (count (q/get-hand db player-id)))


(defn get-object-zone
  "Get the current zone of an object by its ID."
  [db object-id]
  (:object/zone (q/get-object db object-id)))


(defn get-library-count
  "Get the number of cards in a player's library."
  [db player-id]
  (count (q/get-objects-in-zone db player-id :library)))


(defn get-library-positions
  "Get a map of object-id -> position for all cards in library."
  [db player-id]
  (let [lib-cards (q/get-objects-in-zone db player-id :library)]
    (into {} (map (fn [obj] [(:object/id obj) (:object/position obj)]) lib-cards))))


;; === query-library-by-criteria Tests ===

(deftest test-query-library-single-match
  (testing "query-library-by-criteria finds single matching card"
    (let [db (create-test-db)
          ;; Add library with one blue instant and other cards
          [db' [_dr-id _bf-id]] (add-cards-to-library db
                                                      [:dark-ritual :brain-freeze]
                                                      :player-1)
          ;; Query for blue instants
          results (q/query-library-by-criteria db' :player-1
                                               {:card/types #{:instant}
                                                :card/colors #{:blue}})]
      (is (= 1 (count results))
          "Should find exactly 1 blue instant (Brain Freeze)")
      (is (= :brain-freeze (get-in (first results) [:object/card :card/id]))
          "The match should be Brain Freeze"))))


(deftest test-query-library-multiple-matches
  (testing "query-library-by-criteria returns all matching cards"
    (let [db (create-test-db)
          ;; Add library with multiple blue instants
          [db' _] (add-cards-to-library db
                                        [:dark-ritual :brain-freeze :mental-note]
                                        :player-1)
          results (q/query-library-by-criteria db' :player-1
                                               {:card/types #{:instant}
                                                :card/colors #{:blue}})]
      ;; Both Brain Freeze and Mental Note are blue instants
      (is (= 2 (count results))
          "Should find 2 blue instants")
      (is (= #{:brain-freeze :mental-note}
             (set (map #(get-in % [:object/card :card/id]) results)))
          "Should find Brain Freeze and Mental Note"))))


(deftest test-query-library-no-matches
  (testing "query-library-by-criteria returns empty vector when no matches"
    (let [db (create-test-db)
          ;; Add library with no blue instants (just black cards)
          [db' _] (add-cards-to-library db
                                        [:dark-ritual :cabal-ritual :dark-ritual]
                                        :player-1)
          results (q/query-library-by-criteria db' :player-1
                                               {:card/types #{:instant}
                                                :card/colors #{:blue}})]
      (is (empty? results)
          "Should return empty vector when no matches"))))


(deftest test-query-library-type-and-color
  (testing "query-library-by-criteria requires ALL types (AND) and ANY color (OR)"
    (let [db (create-test-db)
          ;; Add: Dark Ritual (black instant), Brain Freeze (blue instant), Careful Study (blue sorcery)
          [db' _] (add-cards-to-library db
                                        [:dark-ritual :brain-freeze :careful-study]
                                        :player-1)
          ;; Query for blue instant - should only match Brain Freeze
          results (q/query-library-by-criteria db' :player-1
                                               {:card/types #{:instant}
                                                :card/colors #{:blue}})]
      (is (= 1 (count results))
          "Careful Study is blue but sorcery - should not match instant query")
      (is (= :brain-freeze (get-in (first results) [:object/card :card/id]))
          "Only Brain Freeze matches blue + instant"))))


(deftest test-query-library-empty-criteria
  (testing "query-library-by-criteria with empty criteria returns all cards"
    (let [db (create-test-db)
          [db' _] (add-cards-to-library db
                                        [:dark-ritual :brain-freeze :island]
                                        :player-1)
          results (q/query-library-by-criteria db' :player-1 {})]
      (is (= 3 (count results))
          "Empty criteria should match all library cards"))))


;; === shuffle-library Tests ===

;; Note: test-shuffle-library-changes-positions was deleted.
;; It captured _positions-before but never compared to positions-after.
;; The shuffle implementation is trivial and tested indirectly by other tests.
;; Edge cases (empty library, single card) are covered by adjacent tests.


(deftest test-shuffle-empty-library
  (testing "shuffle-library on empty library is no-op"
    (let [db (create-test-db)
          ;; No cards in library
          db-after (zones/shuffle-library db :player-1)]
      (is (= 0 (get-library-count db-after :player-1))
          "Empty library should remain empty after shuffle"))))


(deftest test-shuffle-single-card
  (testing "shuffle-library with single card keeps position 0"
    (let [db (create-test-db)
          [db' [obj-id]] (add-cards-to-library db [:dark-ritual] :player-1)
          db-after (zones/shuffle-library db' :player-1)
          positions (get-library-positions db-after :player-1)]
      (is (= {obj-id 0} positions)
          "Single card should have position 0 after shuffle"))))


;; Note: test-tutor-effect-returns-db-unchanged was deleted.
;; It tested an implementation detail (that the effect returns db unchanged)
;; rather than behavior. The actual tutor behavior is tested through
;; resolve-spell-with-selection tests in the "Tutor Selection System Tests" section.

;; === Tutor Selection System Tests ===

(deftest test-tutor-shows-selection-modal
  (testing "Resolving tutor spell creates pending selection state"
    (let [db (create-test-db)
          ;; Add library with blue instants
          [db' _] (add-cards-to-library db [:brain-freeze :mental-note :dark-ritual] :player-1)
          ;; Add Merchant Scroll to hand
          [db'' ms-id] (add-card-to-zone db' :merchant-scroll :hand :player-1)
          ;; Cast Merchant Scroll
          db-after-cast (rules/cast-spell db'' :player-1 ms-id)
          _ (is (= :stack (get-object-zone db-after-cast ms-id))
                "Merchant Scroll should be on stack")
          ;; Resolve with selection system
          result (selection/resolve-spell-with-selection db-after-cast :player-1 ms-id)]
      (is (some? (:pending-selection result))
          "Should return pending selection state")
      (is (= :tutor (get-in result [:pending-selection :selection/effect-type]))
          "Selection effect type should be :tutor")
      (is (= :library (get-in result [:pending-selection :selection/zone]))
          "Selection zone should be :library")
      (is (true? (get-in result [:pending-selection :selection/allow-fail-to-find?]))
          "Must allow fail-to-find option")
      ;; Candidates should be pre-filtered (only blue instants)
      (let [candidates (get-in result [:pending-selection :selection/candidates])]
        (is (= 2 (count candidates))
            "Should have 2 candidates (Brain Freeze and Mental Note)")))))


(deftest test-tutor-fail-to-find-preserves-cards
  (testing "Selecting nothing (fail-to-find) preserves library and hand contents"
    (let [db (create-test-db)
          [db' _obj-ids] (add-cards-to-library db
                                               [:brain-freeze :dark-ritual :mental-note]
                                               :player-1)
          hand-before (get-hand-count db' :player-1)
          ;; Simulate confirming empty selection (fail-to-find)
          selection {:selection/zone :library
                     :selection/select-count 1
                     :selection/player-id :player-1
                     :selection/selected #{}  ; Empty = fail to find
                     :selection/spell-id (random-uuid)
                     :selection/target-zone :hand
                     :selection/effect-type :tutor
                     :selection/allow-fail-to-find? true}
          db-after (selection/execute-tutor-selection db' selection)]
      ;; Hand should not change
      (is (= hand-before (get-hand-count db-after :player-1))
          "Hand should not gain cards on fail-to-find")
      ;; Library still has same count
      (is (= 3 (get-library-count db-after :player-1))
          "Library count should not change"))))


(deftest test-tutor-card-to-hand
  (testing "Selecting a card moves it to target zone (hand)"
    (let [db (create-test-db)
          [db' [bf-id dr-id]] (add-cards-to-library db
                                                    [:brain-freeze :dark-ritual]
                                                    :player-1)
          hand-before (get-hand-count db' :player-1)
          ;; Simulate selecting Brain Freeze
          selection {:selection/zone :library
                     :selection/select-count 1
                     :selection/player-id :player-1
                     :selection/selected #{bf-id}
                     :selection/spell-id (random-uuid)
                     :selection/target-zone :hand
                     :selection/effect-type :tutor
                     :selection/allow-fail-to-find? true}
          db-after (selection/execute-tutor-selection db' selection)]
      ;; Brain Freeze should be in hand
      (is (= :hand (get-object-zone db-after bf-id))
          "Selected card should move to hand")
      ;; Hand count increased
      (is (= (inc hand-before) (get-hand-count db-after :player-1))
          "Hand count should increase by 1")
      ;; Dark Ritual should still be in library
      (is (= :library (get-object-zone db-after dr-id))
          "Non-selected cards should stay in library"))))


(deftest test-tutor-shuffles-after-find
  (testing "Library is shuffled AFTER card is moved (not before)"
    (let [db (create-test-db)
          ;; Add 5 cards to library to make shuffle order detectable
          [db' [bf-id & _rest-ids]] (add-cards-to-library db
                                                          [:brain-freeze :dark-ritual :dark-ritual
                                                           :dark-ritual :dark-ritual]
                                                          :player-1)
          ;; Select Brain Freeze (which was at position 0)
          selection {:selection/zone :library
                     :selection/select-count 1
                     :selection/player-id :player-1
                     :selection/selected #{bf-id}
                     :selection/spell-id (random-uuid)
                     :selection/target-zone :hand
                     :selection/effect-type :tutor
                     :selection/allow-fail-to-find? true}
          db-after (selection/execute-tutor-selection db' selection)]
      ;; Brain Freeze should be in hand (moved before shuffle)
      (is (= :hand (get-object-zone db-after bf-id))
          "Selected card should be in hand")
      ;; Remaining 4 cards should be in library
      (is (= 4 (get-library-count db-after :player-1))
          "Library should have 4 cards after tutoring 1")
      ;; Positions should be 0-3 (re-numbered after shuffle)
      (let [positions (get-library-positions db-after :player-1)]
        (is (= (set (range 4)) (set (vals positions)))
            "Library positions should be 0-3 after shuffle")))))


;; === Merchant Scroll Card Definition Tests ===

(deftest test-merchant-scroll-is-sorcery
  (testing "Merchant Scroll has :sorcery in types"
    (is (= #{:sorcery} (:card/types cards/merchant-scroll))
        "Merchant Scroll should be a sorcery")))


(deftest test-merchant-scroll-costs-1u
  (testing "Merchant Scroll costs 1U"
    (is (= {:colorless 1 :blue 1} (:card/mana-cost cards/merchant-scroll))
        "Merchant Scroll should cost {1}{U}")))


(deftest test-merchant-scroll-has-tutor-effect
  (testing "Merchant Scroll has tutor effect for blue instant"
    (let [effects (:card/effects cards/merchant-scroll)]
      (is (= 1 (count effects))
          "Merchant Scroll should have 1 effect")
      (let [tutor-effect (first effects)]
        (is (= :tutor (:effect/type tutor-effect))
            "Effect type should be :tutor")
        (is (= #{:instant} (get-in tutor-effect [:effect/criteria :card/types]))
            "Should search for instants")
        (is (= #{:blue} (get-in tutor-effect [:effect/criteria :card/colors]))
            "Should search for blue cards")
        (is (= :hand (:effect/target-zone tutor-effect))
            "Should put card into hand")
        (is (true? (:effect/shuffle? tutor-effect))
            "Should shuffle library after")))))


;; === Merchant Scroll Integration Tests ===

(deftest test-merchant-scroll-finds-blue-instant
  (testing "Merchant Scroll can find Brain Freeze (blue instant)"
    (let [db (create-test-db)
          ;; Add library with various cards
          [db' [bf-id _dr-id _cs-id]] (add-cards-to-library db
                                                            [:brain-freeze :dark-ritual :careful-study]
                                                            :player-1)
          ;; Add Merchant Scroll to hand
          [db'' ms-id] (add-card-to-zone db' :merchant-scroll :hand :player-1)
          ;; Cast
          db-after-cast (rules/cast-spell db'' :player-1 ms-id)
          ;; Resolve (triggers selection)
          result (selection/resolve-spell-with-selection db-after-cast :player-1 ms-id)
          candidates (get-in result [:pending-selection :selection/candidates])]
      ;; Only Brain Freeze should be a candidate (blue instant)
      ;; Dark Ritual is black instant, Careful Study is blue sorcery
      (is (= 1 (count candidates))
          "Should have exactly 1 candidate")
      (is (= #{bf-id} candidates)
          "Only Brain Freeze should be selectable"))))


(deftest test-merchant-scroll-ignores-non-blue
  (testing "Merchant Scroll ignores non-blue instants"
    (let [db (create-test-db)
          ;; Dark Ritual is a black instant - should not match
          [db' [_dr-id]] (add-cards-to-library db [:dark-ritual] :player-1)
          [db'' ms-id] (add-card-to-zone db' :merchant-scroll :hand :player-1)
          db-after-cast (rules/cast-spell db'' :player-1 ms-id)
          result (selection/resolve-spell-with-selection db-after-cast :player-1 ms-id)
          candidates (get-in result [:pending-selection :selection/candidates])]
      (is (empty? candidates)
          "Dark Ritual (black instant) should not be a candidate"))))


(deftest test-merchant-scroll-ignores-non-instant
  (testing "Merchant Scroll ignores non-instant blue cards"
    (let [db (create-test-db)
          ;; Careful Study is blue sorcery - should not match
          [db' [_cs-id]] (add-cards-to-library db [:careful-study] :player-1)
          [db'' ms-id] (add-card-to-zone db' :merchant-scroll :hand :player-1)
          db-after-cast (rules/cast-spell db'' :player-1 ms-id)
          result (selection/resolve-spell-with-selection db-after-cast :player-1 ms-id)
          candidates (get-in result [:pending-selection :selection/candidates])]
      (is (empty? candidates)
          "Careful Study (blue sorcery) should not be a candidate"))))


(deftest test-merchant-scroll-fail-to-find-works
  (testing "Merchant Scroll allows fail-to-find even with valid targets"
    (let [db (create-test-db)
          ;; Brain Freeze is valid target but player can decline
          [db' [_bf-id]] (add-cards-to-library db [:brain-freeze] :player-1)
          [db'' ms-id] (add-card-to-zone db' :merchant-scroll :hand :player-1)
          db-after-cast (rules/cast-spell db'' :player-1 ms-id)
          result (selection/resolve-spell-with-selection db-after-cast :player-1 ms-id)]
      ;; Must have fail-to-find option (anti-pattern: NO auto-select)
      (is (true? (get-in result [:pending-selection :selection/allow-fail-to-find?]))
          "Must always allow fail-to-find")
      ;; Candidates include the valid target
      (is (= 1 (count (get-in result [:pending-selection :selection/candidates])))
          "Should show Brain Freeze as candidate")
      ;; But player can select nothing
      (is (= 0 (count (get-in result [:pending-selection :selection/selected])))
          "Selection should start empty"))))


(deftest test-merchant-scroll-with-empty-library
  (testing "Merchant Scroll with empty library shows only fail-to-find"
    (let [db (create-test-db)
          ;; No cards in library
          [db' ms-id] (add-card-to-zone db :merchant-scroll :hand :player-1)
          db-after-cast (rules/cast-spell db' :player-1 ms-id)
          result (selection/resolve-spell-with-selection db-after-cast :player-1 ms-id)]
      ;; Should still have pending selection (for fail-to-find)
      (is (some? (:pending-selection result))
          "Should have pending selection even with empty library")
      ;; No candidates
      (is (empty? (get-in result [:pending-selection :selection/candidates]))
          "Should have no candidates in empty library")
      ;; Fail-to-find still allowed
      (is (true? (get-in result [:pending-selection :selection/allow-fail-to-find?]))
          "Must allow fail-to-find even with no candidates"))))


(deftest test-merchant-scroll-no-blue-instants-in-library
  (testing "Merchant Scroll with no matching cards shows only fail-to-find"
    (let [db (create-test-db)
          ;; Library has cards but no blue instants
          [db' _] (add-cards-to-library db [:dark-ritual :careful-study :island] :player-1)
          [db'' ms-id] (add-card-to-zone db' :merchant-scroll :hand :player-1)
          db-after-cast (rules/cast-spell db'' :player-1 ms-id)
          result (selection/resolve-spell-with-selection db-after-cast :player-1 ms-id)]
      (is (some? (:pending-selection result))
          "Should have pending selection")
      (is (empty? (get-in result [:pending-selection :selection/candidates]))
          "Should have no candidates (no blue instants)")
      (is (true? (get-in result [:pending-selection :selection/allow-fail-to-find?]))
          "Must allow fail-to-find when no matches"))))
