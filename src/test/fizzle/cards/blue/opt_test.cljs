(ns fizzle.cards.blue.opt-test
  "Tests for Opt with scry effect.

   Opt: U - Instant
   Scry 1, then draw a card.

   Tests verify:
   - Card definition (type, cost, effects)
   - Scry selection state created
   - Remaining draw effect preserved
   - Full resolution (scry confirm triggers draw)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.opt :as opt]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === Card Definition Tests ===

(deftest test-opt-card-definition
  (testing "Opt identity and core fields"
    (is (= :opt (:card/id opt/card))
        "Card id should be :opt")
    (is (= "Opt" (:card/name opt/card))
        "Card name should be 'Opt'")
    (is (= 1 (:card/cmc opt/card))
        "CMC should be 1")
    (is (= #{:blue} (:card/colors opt/card))
        "Colors should be #{:blue}")
    (is (= #{:instant} (:card/types opt/card))
        "Opt should be an instant")
    (is (= {:blue 1} (:card/mana-cost opt/card))
        "Opt should cost {U}")
    (is (= "Scry 1, then draw a card." (:card/text opt/card))
        "Card text should match"))

  (testing "Opt has scry 1 first, then draw 1"
    (let [effects (:card/effects opt/card)]
      (is (= 2 (count effects))
          "Opt should have 2 effects")
      (let [scry-effect (first effects)
            draw-effect (second effects)]
        (is (= :scry (:effect/type scry-effect))
            "First effect should be :scry")
        (is (= 1 (:effect/amount scry-effect))
            "Scry effect should scry 1")
        (is (= :draw (:effect/type draw-effect))
            "Second effect should be :draw")
        (is (= 1 (:effect/amount draw-effect))
            "Draw effect should draw 1")))))


;; === C. Cannot-Cast Guards ===

(deftest opt-cannot-cast-without-mana-test
  (testing "Cannot cast Opt without blue mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :opt :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana"))))


(deftest opt-cannot-cast-from-graveyard-test
  (testing "Cannot cast Opt from graveyard"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db obj-id] (th/add-card-to-zone db :opt :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest opt-increments-storm-count-test
  (testing "Casting Opt increments storm count"
    (let [db (th/create-test-db {:mana {:blue 1}})
          [db _] (th/add-cards-to-library db [:dark-ritual :cabal-ritual] :player-1)
          [db obj-id] (th/add-card-to-zone db :opt :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Storm count should start at 0")
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db-cast :player-1))
          "Storm count should be 1 after casting Opt"))))


;; === Scry Selection Tests ===

(deftest test-opt-creates-scry-selection-state
  ;; Bug caught: Scry effect not triggering selection
  (testing "Casting and resolving Opt creates scry selection state"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; Add cards to library for scrying
          [db' _lib-ids] (th/add-cards-to-library db
                                                  [:dark-ritual :cabal-ritual :brain-freeze]
                                                  :player-1)
          ;; Add Opt to hand
          [db'' opt-id] (th/add-card-to-zone db' :opt :hand :player-1)
          _ (is (= 1 (th/get-hand-count db'' :player-1)) "Precondition: hand has 1 card (Opt)")
          ;; Cast Opt
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-after-cast (rules/cast-spell db'' :player-1 opt-id)
          _ (is (= :stack (th/get-object-zone db-after-cast opt-id))
                "Opt should be on stack after casting")
          ;; Resolve spell - should create scry selection
          result (resolution/resolve-one-item db-after-cast)]
      ;; Selection type should be :scry
      (is (= :scry (get-in result [:pending-selection :selection/domain]))
          "Selection type should be :scry")
      ;; Should have 1 card to scry
      (is (= 1 (count (get-in result [:pending-selection :selection/cards])))
          "Scry selection should have 1 card"))))


(deftest test-opt-scry-preserves-draw-effect
  ;; Bug caught: Draw effect lost during selection chaining
  (testing "Scry selection state preserves draw as remaining effect"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; Add cards to library for scrying
          [db' _lib-ids] (th/add-cards-to-library db
                                                  [:dark-ritual :cabal-ritual :brain-freeze]
                                                  :player-1)
          ;; Add Opt to hand
          [db'' opt-id] (th/add-card-to-zone db' :opt :hand :player-1)
          ;; Cast and resolve Opt
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-after-cast (rules/cast-spell db'' :player-1 opt-id)
          result (resolution/resolve-one-item db-after-cast)
          remaining-effects (get-in result [:pending-selection :selection/remaining-effects])]
      ;; Should have 1 remaining effect (draw)
      (is (= 1 (count remaining-effects))
          "Should have 1 remaining effect")
      ;; Remaining effect should be draw 1
      (is (= :draw (:effect/type (first remaining-effects)))
          "Remaining effect should be :draw")
      (is (= 1 (:effect/amount (first remaining-effects)))
          "Remaining draw should draw 1"))))


(deftest test-opt-full-resolution-draws-card
  ;; Bug caught: Draw not executing after scry confirm
  (testing "After scry confirm, draw effect executes"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; Add 3 cards to library
          [db _lib-ids] (th/add-cards-to-library db
                                                 [:dark-ritual :cabal-ritual :brain-freeze]
                                                 :player-1)
          ;; Add Opt to hand
          [db opt-id] (th/add-card-to-zone db :opt :hand :player-1)
          initial-hand (th/get-hand-count db :player-1)
          _ (is (= 1 initial-hand) "Precondition: hand has 1 card (Opt)")
          ;; Cast Opt
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-cast (rules/cast-spell db :player-1 opt-id)
          ;; Resolve spell - creates scry selection
          {:keys [db selection]} (th/resolve-top db-cast)
          top-card (first (:selection/cards selection))
          ;; Set scry piles: put card on bottom, then confirm via production path
          scry-sel (assoc selection
                          :selection/top-pile []
                          :selection/bottom-pile [top-card])
          {:keys [db]} (th/confirm-selection db scry-sel #{})]
      ;; After scry + draw, hand should have 1 card (drew the top card after reorder)
      (is (= 1 (th/get-hand-count db :player-1))
          "After scry confirm and draw, hand should have 1 card")
      ;; Opt should be in graveyard after full resolution
      (is (= :graveyard (th/get-object-zone db opt-id))
          "Opt should be in graveyard after resolution"))))


(deftest test-opt-empty-library-scry-still-draws-attempt
  ;; Bug caught: Empty library crash or skip draw
  (testing "Empty library: scry returns nil, draw still attempts"
    (let [db (th/create-test-db {:mana {:blue 1}})
          ;; NO library cards - empty library
          ;; Add Opt to hand
          [db' opt-id] (th/add-card-to-zone db :opt :hand :player-1)
          library-count (count (q/get-objects-in-zone db' :player-1 :library))
          _ (is (= 0 library-count) "Precondition: library is empty")
          ;; Cast Opt
          ;; Interactive spell — rules/cast-spell required (selection on resolve)
          db-after-cast (rules/cast-spell db' :player-1 opt-id)
          ;; Resolve spell - scry with empty library
          result (resolution/resolve-one-item db-after-cast)]
      ;; With empty library, scry selection is nil (no cards to scry)
      ;; But the effect should handle this gracefully
      ;; If no selection needed, spell resolves directly including draw
      (if (nil? (:pending-selection result))
        ;; Direct resolution path - draw from empty library = 0 cards
        (is (= 0 (th/get-hand-count (:db result) :player-1))
            "With empty library, no cards drawn")
        ;; If somehow selection was created, it should be empty
        (is (= 0 (count (get-in result [:pending-selection :selection/cards])))
            "Scry selection should have 0 cards for empty library")))))
