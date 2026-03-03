(ns fizzle.cards.blue.cunning-wish-test
  "Tests for Cunning Wish card definition and behavior.

   Cunning Wish: 2U - Instant
   Oracle: You may reveal an instant card you own from outside the game
   and put it into your hand. Exile Cunning Wish.

   Rulings:
   (2009-10-01): In a sanctioned event, a card that's 'outside the game'
   is one that's in your sideboard.
   (2009-10-01): You can't acquire exiled cards because those cards are
   still in one of the game's zones."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.cunning-wish :as cunning-wish]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition Tests
;; =====================================================

;; Oracle: Cunning Wish is an Instant costing {2}{U}
(deftest test-card-definition
  (testing "Cunning Wish card attributes match Scryfall data"
    (let [card cunning-wish/card]
      (is (= :cunning-wish (:card/id card)))
      (is (= "Cunning Wish" (:card/name card)))
      (is (= 3 (:card/cmc card)))
      (is (= {:colorless 2 :blue 1} (:card/mana-cost card)))
      (is (= #{:blue} (:card/colors card)))
      (is (= #{:instant} (:card/types card)))
      (is (= "You may reveal an instant card you own from outside the game and put it into your hand. Exile Cunning Wish."
             (:card/text card))))))


;; Oracle: effects are exile-self then tutor from sideboard
(deftest test-card-effects-structure
  (testing "Cunning Wish effects: exile-self then tutor from sideboard"
    (let [effects (:card/effects cunning-wish/card)]
      (is (= 2 (count effects))
          "Should have 2 effects")
      (let [[exile-effect tutor-effect] effects]
        (is (= :exile-self (:effect/type exile-effect))
            "First effect should exile self")
        (is (= :tutor (:effect/type tutor-effect))
            "Second effect should be tutor")
        (is (= :sideboard (:effect/source-zone tutor-effect))
            "Tutor should search sideboard")
        (is (= #{:instant} (get-in tutor-effect [:effect/criteria :match/types]))
            "Tutor should search for instants only")
        (is (= :hand (:effect/target-zone tutor-effect))
            "Tutor should put card into hand")
        (is (false? (:effect/shuffle? tutor-effect))
            "Tutor should not shuffle library")))))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

;; Oracle: "reveal an instant card you own from outside the game and put it into your hand"
;; Ruling (2009-10-01): "outside the game" = sideboard
(deftest test-cast-resolve-finds-instant-from-sideboard
  (testing "Cunning Wish finds an instant from sideboard and puts it in hand"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          [db sb-id] (th/add-card-to-zone db :brain-freeze :sideboard :player-1)
          [db wish-id] (th/add-card-to-zone db :cunning-wish :hand :player-1)
          ;; Cast Cunning Wish
          db-cast (rules/cast-spell db :player-1 wish-id)
          ;; Resolve — exile-self executes, then tutor pauses for selection
          {:keys [db selection]} (th/resolve-top db-cast)
          _ (is (= :tutor (:selection/type selection))
                "Should produce tutor selection")
          _ (is (= :sideboard (:selection/zone selection))
                "Selection should search sideboard")
          ;; Confirm selecting Brain Freeze
          {:keys [db]} (th/confirm-selection db selection #{sb-id})]
      ;; Brain Freeze should be in hand
      (is (= :hand (th/get-object-zone db sb-id))
          "Selected instant should be in hand")
      ;; Cunning Wish should be exiled (not in graveyard)
      (is (= :exile (th/get-object-zone db wish-id))
          "Cunning Wish should be exiled"))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

;; Oracle: Cunning Wish costs {2}{U}
(deftest test-cannot-cast-insufficient-mana
  (testing "Cannot cast Cunning Wish without enough mana"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db wish-id] (th/add-card-to-zone db :cunning-wish :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 wish-id))
          "Should not be castable with only 1 blue mana"))))


(deftest test-cannot-cast-from-wrong-zone
  (testing "Cannot cast Cunning Wish from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          [db wish-id] (th/add-card-to-zone db :cunning-wish :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 wish-id))
          "Should not be castable from graveyard"))))


;; =====================================================
;; D. Storm Count
;; =====================================================

;; Oracle: Cunning Wish is an instant spell; casting it increments storm count
(deftest test-storm-count-increments
  (testing "Casting Cunning Wish increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          [db _] (th/add-card-to-zone db :brain-freeze :sideboard :player-1)
          [db wish-id] (th/add-card-to-zone db :cunning-wish :hand :player-1)
          storm-before (q/get-storm-count db :player-1)
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
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          ;; No sideboard cards
          [db wish-id] (th/add-card-to-zone db :cunning-wish :hand :player-1)
          db-cast (rules/cast-spell db :player-1 wish-id)
          {:keys [db selection]} (th/resolve-top db-cast)
          _ (is (empty? (:selection/candidates selection))
                "Should have no candidates")
          ;; Confirm empty selection (fail-to-find)
          {:keys [db]} (th/confirm-selection db selection #{})]
      ;; Wish should still be exiled (exile-self runs before tutor)
      (is (= :exile (th/get-object-zone db wish-id))
          "Cunning Wish should be exiled even on fail-to-find"))))


;; Oracle: "an instant card" — sorceries in sideboard are not valid
(deftest test-fail-to-find-no-instants-in-sideboard
  (testing "No candidates when sideboard has only sorceries"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          ;; Sideboard has only sorceries
          [db _] (th/add-card-to-zone db :careful-study :sideboard :player-1)
          [db _] (th/add-card-to-zone db :deep-analysis :sideboard :player-1)
          [db wish-id] (th/add-card-to-zone db :cunning-wish :hand :player-1)
          db-cast (rules/cast-spell db :player-1 wish-id)
          {:keys [selection]} (th/resolve-top db-cast)]
      (is (empty? (:selection/candidates selection))
          "Should have no candidates — sorceries don't match instant criteria"))))


;; Oracle: "You may" — player can decline even when valid targets exist
(deftest test-fail-to-find-player-declines
  (testing "Player can decline to find even with valid instants in sideboard"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          [db sb-id] (th/add-card-to-zone db :brain-freeze :sideboard :player-1)
          [db wish-id] (th/add-card-to-zone db :cunning-wish :hand :player-1)
          db-cast (rules/cast-spell db :player-1 wish-id)
          {:keys [db selection]} (th/resolve-top db-cast)
          _ (is (= 1 (count (:selection/candidates selection)))
                "Should have exactly 1 candidate available")
          ;; Confirm with empty selection (player declines)
          {:keys [db]} (th/confirm-selection db selection #{})]
      ;; Brain Freeze stays in sideboard
      (is (= :sideboard (th/get-object-zone db sb-id))
          "Declined card should stay in sideboard")
      ;; Wish is still exiled
      (is (= :exile (th/get-object-zone db wish-id))
          "Cunning Wish should be exiled regardless"))))


;; =====================================================
;; F. Edge Cases
;; =====================================================

;; Oracle: "an instant card" — only instants, not sorceries
(deftest test-sideboard-mix-of-types-only-instants-offered
  (testing "Only instants from sideboard are offered as candidates"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          ;; Sideboard has mix: instant, sorcery, land
          [db bf-id] (th/add-card-to-zone db :brain-freeze :sideboard :player-1)
          [db _] (th/add-card-to-zone db :careful-study :sideboard :player-1)
          [db _] (th/add-card-to-zone db :island :sideboard :player-1)
          [db wish-id] (th/add-card-to-zone db :cunning-wish :hand :player-1)
          db-cast (rules/cast-spell db :player-1 wish-id)
          {:keys [selection]} (th/resolve-top db-cast)]
      (is (= 1 (count (:selection/candidates selection)))
          "Should have exactly 1 candidate (Brain Freeze)")
      (is (contains? (:selection/candidates selection) bf-id)
          "Brain Freeze (instant) should be the candidate"))))


;; Oracle: "Exile Cunning Wish" — exile happens regardless of tutor result
(deftest test-wish-exiled-before-tutor-selection
  (testing "Cunning Wish is already exiled when tutor selection appears"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          [db _] (th/add-card-to-zone db :brain-freeze :sideboard :player-1)
          [db wish-id] (th/add-card-to-zone db :cunning-wish :hand :player-1)
          db-cast (rules/cast-spell db :player-1 wish-id)
          {:keys [db]} (th/resolve-top db-cast)]
      ;; Wish should already be in exile at this point (exile-self ran first)
      (is (= :exile (th/get-object-zone db wish-id))
          "Cunning Wish should be exiled before tutor selection"))))


;; Ruling (2009-10-01): "outside the game" = sideboard, not library/graveyard/exile
(deftest test-does-not-search-library
  (testing "Cunning Wish does not find cards in library"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          ;; Put an instant in library but NOT sideboard
          [db _] (th/add-cards-to-library db [:brain-freeze] :player-1)
          [db wish-id] (th/add-card-to-zone db :cunning-wish :hand :player-1)
          db-cast (rules/cast-spell db :player-1 wish-id)
          {:keys [selection]} (th/resolve-top db-cast)]
      (is (empty? (:selection/candidates selection))
          "Should have no candidates — library is not 'outside the game'"))))


;; Oracle: no shuffle — wishes don't shuffle the library
(deftest test-no-library-shuffle
  (testing "Cunning Wish does not shuffle library"
    (let [db (th/create-test-db {:mana {:colorless 2 :blue 1}})
          ;; Add cards with known library positions
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual :opt] :player-1)
          [db sb-id] (th/add-card-to-zone db :brain-freeze :sideboard :player-1)
          [db wish-id] (th/add-card-to-zone db :cunning-wish :hand :player-1)
          ;; Record library positions before
          lib-before (q/get-objects-in-zone db :player-1 :library)
          positions-before (into {} (map (fn [o] [(:object/id o) (:object/position o)]) lib-before))
          ;; Cast and resolve
          db-cast (rules/cast-spell db :player-1 wish-id)
          {:keys [db selection]} (th/resolve-top db-cast)
          {:keys [db]} (th/confirm-selection db selection #{sb-id})
          ;; Check library positions after
          lib-after (q/get-objects-in-zone db :player-1 :library)
          positions-after (into {} (map (fn [o] [(:object/id o) (:object/position o)]) lib-after))]
      (is (= positions-before positions-after)
          "Library positions should be unchanged — no shuffle"))))
