(ns fizzle.cards.red.burning-wish-test
  "Tests for Burning Wish card definition and behavior.

   Burning Wish: 1R - Sorcery
   Oracle: You may reveal a sorcery card you own from outside the game
   and put it into your hand. Exile Burning Wish.

   Rulings:
   (2009-10-01): In a sanctioned event, a card that's 'outside the game'
   is one that's in your sideboard.
   (2009-10-01): You can't acquire exiled cards because those cards are
   still in one of the game's zones."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.red.burning-wish :as burning-wish]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition Tests
;; =====================================================

;; Oracle: Burning Wish is a Sorcery costing {1}{R}
(deftest test-card-definition
  (testing "Burning Wish card attributes match Scryfall data"
    (let [card burning-wish/card]
      (is (= :burning-wish (:card/id card)))
      (is (= "Burning Wish" (:card/name card)))
      (is (= 2 (:card/cmc card)))
      (is (= {:colorless 1 :red 1} (:card/mana-cost card)))
      (is (= #{:red} (:card/colors card)))
      (is (= #{:sorcery} (:card/types card)))
      (is (= "You may reveal a sorcery card you own from outside the game and put it into your hand. Exile Burning Wish."
             (:card/text card))))))


;; Oracle: effects are exile-self then tutor from sideboard for sorceries
(deftest test-card-effects-structure
  (testing "Burning Wish effects: exile-self then tutor sorceries from sideboard"
    (let [effects (:card/effects burning-wish/card)]
      (is (= 2 (count effects))
          "Should have 2 effects")
      (let [[exile-effect tutor-effect] effects]
        (is (= :exile-self (:effect/type exile-effect))
            "First effect should exile self")
        (is (= :tutor (:effect/type tutor-effect))
            "Second effect should be tutor")
        (is (= :sideboard (:effect/source-zone tutor-effect))
            "Tutor should search sideboard")
        (is (= #{:sorcery} (get-in tutor-effect [:effect/criteria :match/types]))
            "Tutor should search for sorceries only")
        (is (= :hand (:effect/target-zone tutor-effect))
            "Tutor should put card into hand")
        (is (false? (:effect/shuffle? tutor-effect))
            "Tutor should not shuffle library")))))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

;; Oracle: "reveal a sorcery card you own from outside the game and put it into your hand"
;; Ruling (2009-10-01): "outside the game" = sideboard
(deftest test-cast-resolve-finds-sorcery-from-sideboard
  (testing "Burning Wish finds a sorcery from sideboard and puts it in hand"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          [db sb-id] (th/add-card-to-zone db :recoup :sideboard :player-1)
          [db wish-id] (th/add-card-to-zone db :burning-wish :hand :player-1)
          ;; Cast Burning Wish
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 wish-id)
          ;; Resolve — exile-self executes, then tutor pauses for selection
          {:keys [db selection]} (th/resolve-top db-cast)
          _ (is (= :tutor (:selection/domain selection))
                "Should produce tutor selection")
          _ (is (= :sideboard (:selection/zone selection))
                "Selection should search sideboard")
          ;; Confirm selecting Recoup
          {:keys [db]} (th/confirm-selection db selection #{sb-id})]
      ;; Recoup should be in hand
      (is (= :hand (th/get-object-zone db sb-id))
          "Selected sorcery should be in hand")
      ;; Burning Wish should be exiled (not in graveyard)
      (is (= :exile (th/get-object-zone db wish-id))
          "Burning Wish should be exiled"))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

;; Oracle: Burning Wish costs {1}{R}
(deftest test-cannot-cast-insufficient-mana
  (testing "Cannot cast Burning Wish without enough mana"
    (let [db (th/create-test-db {:mana {:red 1}})
          [db wish-id] (th/add-card-to-zone db :burning-wish :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 wish-id))
          "Should not be castable with only 1 red mana"))))


(deftest test-cannot-cast-from-wrong-zone
  (testing "Cannot cast Burning Wish from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          [db wish-id] (th/add-card-to-zone db :burning-wish :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 wish-id))
          "Should not be castable from graveyard"))))


;; =====================================================
;; D. Storm Count
;; =====================================================

;; Oracle: Burning Wish is a sorcery spell; casting it increments storm count
(deftest test-storm-count-increments
  (testing "Casting Burning Wish increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          [db _] (th/add-card-to-zone db :recoup :sideboard :player-1)
          [db wish-id] (th/add-card-to-zone db :burning-wish :hand :player-1)
          storm-before (q/get-storm-count db :player-1)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 wish-id)
          storm-after (q/get-storm-count db-cast :player-1)]
      (is (= (inc storm-before) storm-after)
          "Storm count should increment by 1 after casting"))))


;; =====================================================
;; E. Selection / Fail-to-Find Tests
;; =====================================================

;; Oracle: "You may reveal" — the "may" allows fail-to-find
(deftest test-fail-to-find-empty-sideboard
  (testing "Fail-to-find when sideboard is empty"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          [db wish-id] (th/add-card-to-zone db :burning-wish :hand :player-1)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 wish-id)
          {:keys [db selection]} (th/resolve-top db-cast)
          _ (is (empty? (:selection/candidates selection))
                "Should have no candidates")
          {:keys [db]} (th/confirm-selection db selection #{})]
      (is (= :exile (th/get-object-zone db wish-id))
          "Burning Wish should be exiled even on fail-to-find"))))


;; Oracle: "a sorcery card" — instants in sideboard are not valid
(deftest test-fail-to-find-no-sorceries-in-sideboard
  (testing "No candidates when sideboard has only instants"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          ;; Sideboard has only instants
          [db _] (th/add-card-to-zone db :brain-freeze :sideboard :player-1)
          [db _] (th/add-card-to-zone db :dark-ritual :sideboard :player-1)
          [db wish-id] (th/add-card-to-zone db :burning-wish :hand :player-1)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 wish-id)
          {:keys [selection]} (th/resolve-top db-cast)]
      (is (empty? (:selection/candidates selection))
          "Should have no candidates — instants don't match sorcery criteria"))))


;; Oracle: "You may" — player can decline even when valid targets exist
(deftest test-fail-to-find-player-declines
  (testing "Player can decline to find even with valid sorceries in sideboard"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          [db sb-id] (th/add-card-to-zone db :recoup :sideboard :player-1)
          [db wish-id] (th/add-card-to-zone db :burning-wish :hand :player-1)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 wish-id)
          {:keys [db selection]} (th/resolve-top db-cast)
          _ (is (= 1 (count (:selection/candidates selection)))
                "Should have exactly 1 candidate available")
          {:keys [db]} (th/confirm-selection db selection #{})]
      (is (= :sideboard (th/get-object-zone db sb-id))
          "Declined card should stay in sideboard")
      (is (= :exile (th/get-object-zone db wish-id))
          "Burning Wish should be exiled regardless"))))


;; =====================================================
;; F. Edge Cases
;; =====================================================

;; Oracle: "a sorcery card" — only sorceries, not instants or other types
(deftest test-sideboard-mix-of-types-only-sorceries-offered
  (testing "Only sorceries from sideboard are offered as candidates"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          ;; Sideboard has mix: sorcery, instant, land
          [db recoup-id] (th/add-card-to-zone db :recoup :sideboard :player-1)
          [db _] (th/add-card-to-zone db :brain-freeze :sideboard :player-1)
          [db _] (th/add-card-to-zone db :island :sideboard :player-1)
          [db wish-id] (th/add-card-to-zone db :burning-wish :hand :player-1)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 wish-id)
          {:keys [selection]} (th/resolve-top db-cast)]
      (is (= 1 (count (:selection/candidates selection)))
          "Should have exactly 1 candidate (Recoup)")
      (is (contains? (:selection/candidates selection) recoup-id)
          "Recoup (sorcery) should be the candidate"))))


;; Oracle: "Exile Burning Wish" — exile happens regardless of tutor result
(deftest test-wish-exiled-before-tutor-selection
  (testing "Burning Wish is already exiled when tutor selection appears"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          [db _] (th/add-card-to-zone db :recoup :sideboard :player-1)
          [db wish-id] (th/add-card-to-zone db :burning-wish :hand :player-1)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 wish-id)
          {:keys [db]} (th/resolve-top db-cast)]
      (is (= :exile (th/get-object-zone db wish-id))
          "Burning Wish should be exiled before tutor selection"))))


;; Ruling (2009-10-01): "outside the game" = sideboard, not library/graveyard/exile
(deftest test-does-not-search-library
  (testing "Burning Wish does not find cards in library"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          [db _] (th/add-cards-to-library db [:careful-study] :player-1)
          [db wish-id] (th/add-card-to-zone db :burning-wish :hand :player-1)
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 wish-id)
          {:keys [selection]} (th/resolve-top db-cast)]
      (is (empty? (:selection/candidates selection))
          "Should have no candidates — library is not 'outside the game'"))))


;; Oracle: no shuffle — wishes don't shuffle the library
(deftest test-no-library-shuffle
  (testing "Burning Wish does not shuffle library"
    (let [db (th/create-test-db {:mana {:colorless 1 :red 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual :opt] :player-1)
          [db sb-id] (th/add-card-to-zone db :recoup :sideboard :player-1)
          [db wish-id] (th/add-card-to-zone db :burning-wish :hand :player-1)
          lib-before (q/get-objects-in-zone db :player-1 :library)
          positions-before (into {} (map (fn [o] [(:object/id o) (:object/position o)]) lib-before))
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 wish-id)
          {:keys [db selection]} (th/resolve-top db-cast)
          {:keys [db]} (th/confirm-selection db selection #{sb-id})
          lib-after (q/get-objects-in-zone db :player-1 :library)
          positions-after (into {} (map (fn [o] [(:object/id o) (:object/position o)]) lib-after))]
      (is (= positions-before positions-after)
          "Library positions should be unchanged — no shuffle"))))
