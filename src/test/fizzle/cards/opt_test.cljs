(ns fizzle.cards.opt-test
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
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.events.game :as game]
    [fizzle.events.selection.library :as library]
    [fizzle.test-helpers :as th]))


;; === Card Definition Tests ===

(deftest test-opt-card-definition
  (testing "Opt type and cost"
    (is (= #{:instant} (:card/types cards/opt))
        "Opt should be an instant")
    (is (= {:blue 1} (:card/mana-cost cards/opt))
        "Opt should cost {U}"))

  (testing "Opt has scry 1 first, then draw 1"
    (let [effects (:card/effects cards/opt)]
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
          db-after-cast (rules/cast-spell db'' :player-1 opt-id)
          _ (is (= :stack (th/get-object-zone db-after-cast opt-id))
                "Opt should be on stack after casting")
          ;; Resolve spell - should create scry selection
          result (game/resolve-one-item db-after-cast :player-1)]
      ;; Selection type should be :scry
      (is (= :scry (get-in result [:pending-selection :selection/type]))
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
          db-after-cast (rules/cast-spell db'' :player-1 opt-id)
          result (game/resolve-one-item db-after-cast :player-1)
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
          [db' _lib-ids] (th/add-cards-to-library db
                                                  [:dark-ritual :cabal-ritual :brain-freeze]
                                                  :player-1)
          ;; Add Opt to hand
          [db'' opt-id] (th/add-card-to-zone db' :opt :hand :player-1)
          initial-hand (th/get-hand-count db'' :player-1)
          _ (is (= 1 initial-hand) "Precondition: hand has 1 card (Opt)")
          ;; Cast Opt
          db-after-cast (rules/cast-spell db'' :player-1 opt-id)
          ;; Resolve spell - creates scry selection
          result (game/resolve-one-item db-after-cast :player-1)
          scry-selection (:pending-selection result)
          top-card (first (:selection/cards scry-selection))
          ;; Simulate scry choice: put card on bottom
          db-before-confirm (:db result)
          scry-state (assoc scry-selection
                            :selection/top-pile []
                            :selection/bottom-pile [top-card])
          ;; Create app-db with pending selection for confirm-scry-selection
          app-db {:game/db db-before-confirm
                  :game/pending-selection scry-state}
          ;; Confirm scry selection - should trigger draw
          result-app-db (library/confirm-scry-selection app-db)
          result-db (:game/db result-app-db)]
      ;; After scry + draw, hand should have 1 card (drew the top card after reorder)
      (is (= 1 (th/get-hand-count result-db :player-1))
          "After scry confirm and draw, hand should have 1 card")
      ;; Should NOT have pending selection (draw is automatic, not selection-based)
      (is (nil? (:game/pending-selection result-app-db))
          "Should not have pending selection after draw"))))


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
          db-after-cast (rules/cast-spell db' :player-1 opt-id)
          ;; Resolve spell - scry with empty library
          result (game/resolve-one-item db-after-cast :player-1)]
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
