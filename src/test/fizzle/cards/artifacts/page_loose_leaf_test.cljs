(ns fizzle.cards.artifacts.page-loose-leaf-test
  "Tests for Page, Loose Leaf card.

   Page, Loose Leaf: {2} - Legendary Artifact Creature — Construct 0/2
   {T}: Add {C}.
   Grandeur — Discard another card named Page, Loose Leaf: Reveal cards from the top
   of your library until you reveal an instant or sorcery card. Put that card into your
   hand and the rest on the bottom of your library in a random order.

   Tests cover:
   - Card definition (exact fields, ability structure, grandeur keyword)
   - Cast-resolve happy path (enters battlefield with {2} mana cost)
   - Cannot-cast guards (insufficient mana, wrong zone)
   - Storm count on cast (artifact creature spells increment storm)
   - Mana ability: tap for {C}, no stack
   - Grandeur: discard-specific cost builds correct selection
   - Grandeur: confirm discard chains to reveal-until selection
   - Grandeur: no matching copy in hand blocks activation
   - Grandeur edge cases (no instant/sorcery in library, match on top)
   - Tapped vs untapped mana activation"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.artifacts.page-loose-leaf :as page]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.events.abilities :as abilities]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition
;; =====================================================

(deftest page-loose-leaf-card-definition-test
  (testing "Page, Loose Leaf core attributes"
    (let [card page/card]
      (is (= :page-loose-leaf (:card/id card))
          "Card id should be :page-loose-leaf")
      (is (= "Page, Loose Leaf" (:card/name card))
          "Card name should be 'Page, Loose Leaf'")
      (is (= 2 (:card/cmc card))
          "CMC should be 2")
      (is (= {:colorless 2} (:card/mana-cost card))
          "Mana cost should be {:colorless 2}")
      (is (= #{} (:card/colors card))
          "Colors should be empty (colorless)")
      (is (= #{:artifact :creature} (:card/types card))
          "Types should be #{:artifact :creature}")
      (is (= #{:construct} (:card/subtypes card))
          "Subtypes should be #{:construct}")
      (is (= #{:legendary} (:card/supertypes card))
          "Supertypes should be #{:legendary}")
      (is (= 0 (:card/power card))
          "Power should be 0")
      (is (= 2 (:card/toughness card))
          "Toughness should be 2")
      (is (= #{:grandeur} (:card/keywords card))
          "Keywords should be #{:grandeur}")
      (is (= 2 (count (:card/abilities card)))
          "Should have exactly 2 abilities"))))


(deftest page-loose-leaf-ability-0-definition-test
  (testing "Ability 0: {T}: Add {C}."
    (let [a0 (first (:card/abilities page/card))]
      (is (= :mana (:ability/type a0))
          "Ability 0 should be :mana (no stack)")
      (is (= {:tap true} (:ability/cost a0))
          "Ability 0 cost is {:tap true}")
      (is (= 1 (count (:ability/effects a0)))
          "Ability 0 should have exactly 1 effect")
      (is (= :add-mana (:effect/type (first (:ability/effects a0))))
          "Ability 0 effect type should be :add-mana")
      (is (= {:colorless 1} (:effect/mana (first (:ability/effects a0))))
          "Ability 0 produces 1 colorless mana"))))


(deftest page-loose-leaf-ability-1-definition-test
  (testing "Ability 1: Grandeur — discard another copy, reveal until instant/sorcery"
    (let [a1 (second (:card/abilities page/card))]
      (is (= :activated (:ability/type a1))
          "Ability 1 should be :activated (uses stack)")
      (is (= 1 (get-in a1 [:ability/cost :discard-specific :total]))
          "Discard cost total should be 1")
      (is (= #{:page-loose-leaf}
             (get-in a1 [:ability/cost :discard-specific :groups 0 :criteria :match/card-ids]))
          "Discard criteria should match :page-loose-leaf card ids")
      (is (= 1 (count (:ability/effects a1)))
          "Ability 1 should have exactly 1 effect")
      (let [effect (first (:ability/effects a1))]
        (is (= :reveal-until (:effect/type effect))
            "Effect type should be :reveal-until")
        (is (= {:match/types #{:instant :sorcery}} (:effect/criteria effect))
            "Effect criteria should match instants and sorceries")
        (is (= :hand (:effect/found-zone effect))
            "Found card should go to :hand")
        (is (= :bottom-random (:effect/remainder effect))
            "Non-matching cards go to :bottom-random")))))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

(deftest page-loose-leaf-cast-to-battlefield-test
  (testing "Page, Loose Leaf enters battlefield when cast with {2}"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db obj-id] (th/add-card-to-zone db :page-loose-leaf :hand :player-1)
          _ (is (true? (rules/can-cast? db :player-1 obj-id))
                "Should be castable with {2}")
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= :battlefield (th/get-object-zone db-resolved obj-id))
          "Page, Loose Leaf should be on battlefield after resolve")
      (is (= 0 (:colorless (q/get-mana-pool db-resolved :player-1)))
          "Mana pool should be drained after paying {2}"))))


(deftest page-loose-leaf-mana-ability-activatable-when-untapped-test
  (testing "Page on battlefield: mana ability activatable when untapped"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :page-loose-leaf :battlefield :player-1)
          obj (q/get-object db obj-id)
          a0 (first (:card/abilities (:object/card obj)))]
      (is (false? (boolean (:object/tapped obj)))
          "Precondition: Page should not be tapped after placement")
      (is (= :mana (:ability/type a0))
          "Ability 0 is the mana ability"))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

(deftest page-loose-leaf-cannot-cast-with-insufficient-mana-test
  (testing "Cannot cast Page with only {1}"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          [db obj-id] (th/add-card-to-zone db :page-loose-leaf :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Cannot cast with only 1 colorless mana"))))


(deftest page-loose-leaf-cannot-cast-from-graveyard-test
  (testing "Cannot cast Page from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db obj-id] (th/add-card-to-zone db :page-loose-leaf :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Cannot cast from graveyard"))))


;; =====================================================
;; D. Storm Count
;; =====================================================

(deftest page-loose-leaf-cast-increments-storm-test
  (testing "Casting Page, Loose Leaf increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 2}})
          [db obj-id] (th/add-card-to-zone db :page-loose-leaf :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Precondition: storm count is 0")
          db-resolved (th/cast-and-resolve db :player-1 obj-id)]
      (is (= 1 (q/get-storm-count db-resolved :player-1))
          "Storm count should be 1 after casting Page, Loose Leaf"))))


;; =====================================================
;; E. Selection Tests: Grandeur Activation
;; =====================================================

(deftest grandeur-activation-builds-discard-selection-test
  (testing "Grandeur activation builds discard selection with :pick-from-zone mechanism and :discard-cost domain"
    (let [db (th/create-test-db)
          [db page-id] (th/add-card-to-zone db :page-loose-leaf :battlefield :player-1)
          ;; Add another copy of Page to hand (the one to discard)
          [db _hand-page-id] (th/add-card-to-zone db :page-loose-leaf :hand :player-1)
          result (abilities/activate-ability db :player-1 page-id 1)
          sel (:pending-selection result)]
      (is (some? sel)
          "Grandeur activation should produce a pending selection")
      (is (= :pick-from-zone (:selection/mechanism sel))
          "Selection mechanism should be :pick-from-zone")
      (is (= :discard-cost (:selection/domain sel))
          "Selection domain should be :discard-cost")
      (is (= :ability (:selection/source-type sel))
          "Selection source-type should be :ability")
      (is (= 1 (:selection/select-count sel))
          "Selection select-count should be 1")
      (is (= :player-1 (:selection/player-id sel))
          "Selection player-id should be :player-1"))))


(deftest grandeur-confirm-discard-puts-copy-in-graveyard-test
  (testing "Confirming Grandeur discard: copy goes to graveyard, ability on stack"
    (let [db (th/create-test-db)
          [db page-id] (th/add-card-to-zone db :page-loose-leaf :battlefield :player-1)
          [db hand-page-id] (th/add-card-to-zone db :page-loose-leaf :hand :player-1)
          _ (is (= :hand (:object/zone (q/get-object db hand-page-id)))
                "Precondition: hand copy is in hand")
          result (abilities/activate-ability db :player-1 page-id 1)
          sel (:pending-selection result)
          {:keys [db]} (th/confirm-selection (:db result) sel #{hand-page-id})]
      (is (= :graveyard (:object/zone (q/get-object db hand-page-id)))
          "Discarded copy should be in graveyard after cost payment")
      (is (some? (stack/get-top-stack-item db))
          "Ability stack item should exist after discard confirmation"))))


(deftest grandeur-resolve-reveals-until-instant-sorcery-test
  (testing "Grandeur resolve: instant/sorcery found goes to hand, rest to bottom"
    (let [db (th/create-test-db)
          [db page-id] (th/add-card-to-zone db :page-loose-leaf :battlefield :player-1)
          [db hand-page-id] (th/add-card-to-zone db :page-loose-leaf :hand :player-1)
          ;; Library: creature (pos 0), instant (pos 1)
          [db _] (th/add-cards-to-library db [:nimble-mongoose :dark-ritual] :player-1)
          initial-hand-count (th/get-hand-count db :player-1)
          ;; Activate Grandeur
          result (abilities/activate-ability db :player-1 page-id 1)
          sel (:pending-selection result)
          ;; Confirm discard
          {:keys [db selection]} (th/confirm-selection (:db result) sel #{hand-page-id})
          _ (is (nil? selection) "No chaining selection after discard confirm")
          ;; Resolve the ability — produces a reveal-until selection
          {:keys [db selection]} (th/resolve-top db)
          _ (is (some? selection) "Resolve should produce a reveal-until selection")
          _ (is (= :reveal-until (:selection/domain selection))
                "Selection domain should be :reveal-until")
          ;; Confirm the reveal: select the instant (dark-ritual)
          instant-id (first (:selection/valid-targets selection))
          {:keys [db]} (th/confirm-selection db selection #{instant-id})]
      ;; The instant should now be in hand
      (is (= (inc (- initial-hand-count 1)) (th/get-hand-count db :player-1))
          "Hand should have net same count: discarded 1 (page copy) then drew 1 (instant)")
      ;; The instant is in hand
      (is (= :hand (:object/zone (q/get-object db instant-id)))
          "The instant card should be in hand after reveal-until"))))


;; =====================================================
;; G. Edge Cases
;; =====================================================

(deftest grandeur-no-matching-copy-in-hand-cannot-activate-test
  (testing "Grandeur cannot activate when no matching Page copy in hand"
    (let [db (th/create-test-db)
          [db page-id] (th/add-card-to-zone db :page-loose-leaf :battlefield :player-1)
          ;; Only non-Page cards in hand, no copy
          [db _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          result (abilities/activate-ability db :player-1 page-id 1)]
      (is (nil? (:pending-selection result))
          "Should not show selection when no matching Page copy in hand")
      (is (nil? (stack/get-top-stack-item db))
          "No stack item should be created"))))


(deftest grandeur-no-copy-at-all-in-hand-cannot-activate-test
  (testing "Grandeur cannot activate when hand is completely empty"
    (let [db (th/create-test-db)
          [db page-id] (th/add-card-to-zone db :page-loose-leaf :battlefield :player-1)
          ;; Empty hand
          result (abilities/activate-ability db :player-1 page-id 1)]
      (is (nil? (:pending-selection result))
          "Should not show selection when hand is empty")
      (is (nil? (stack/get-top-stack-item db))
          "No stack item when hand is empty"))))


(deftest grandeur-no-instant-sorcery-in-library-test
  (testing "Grandeur reveal-until with no instant/sorcery: all cards to bottom, nothing to hand"
    (let [db (th/create-test-db)
          [db page-id] (th/add-card-to-zone db :page-loose-leaf :battlefield :player-1)
          [db hand-page-id] (th/add-card-to-zone db :page-loose-leaf :hand :player-1)
          ;; Library contains only creatures and lands (no instants/sorceries)
          [db _] (th/add-cards-to-library db [:nimble-mongoose :island :nimble-mongoose] :player-1)
          ;; Activate and confirm discard
          result (abilities/activate-ability db :player-1 page-id 1)
          sel (:pending-selection result)
          {:keys [db]} (th/confirm-selection (:db result) sel #{hand-page-id})
          ;; Resolve — produces no-match reveal-until selection (select-count=0)
          {:keys [db selection]} (th/resolve-top db)
          _ (is (some? selection) "Resolve should produce reveal-until selection")
          _ (is (= 0 (:selection/select-count selection))
                "No match: select-count should be 0")
          ;; Confirm with empty selection (no match found)
          {:keys [db]} (th/confirm-selection db selection #{})]
      ;; No cards moved to hand from library
      (is (= 0 (th/get-hand-count db :player-1))
          "Hand should be empty: no instant/sorcery found in library")
      ;; All library cards remain
      (is (= 3 (th/get-zone-count db :library :player-1))
          "All 3 library cards remain (moved to bottom)"))))


(deftest grandeur-match-on-first-card-test
  (testing "Grandeur reveal-until: match as first card goes directly to hand"
    (let [db (th/create-test-db)
          [db page-id] (th/add-card-to-zone db :page-loose-leaf :battlefield :player-1)
          [db hand-page-id] (th/add-card-to-zone db :page-loose-leaf :hand :player-1)
          ;; Top of library is an instant
          [db [instant-id _]] (th/add-cards-to-library db [:dark-ritual :nimble-mongoose] :player-1)
          ;; Activate and confirm discard
          result (abilities/activate-ability db :player-1 page-id 1)
          sel (:pending-selection result)
          {:keys [db]} (th/confirm-selection (:db result) sel #{hand-page-id})
          ;; Resolve — produces reveal-until selection with match on top
          {:keys [db selection]} (th/resolve-top db)
          _ (is (some? selection) "Should produce reveal-until selection")
          _ (is (= 1 (:selection/select-count selection))
                "Match found: select-count should be 1")
          _ (is (= #{instant-id} (:selection/valid-targets selection))
                "The instant (top of library) should be the valid target")
          ;; Confirm the instant
          {:keys [db]} (th/confirm-selection db selection #{instant-id})]
      (is (= :hand (:object/zone (q/get-object db instant-id)))
          "The instant should be in hand")
      (is (= 1 (th/get-hand-count db :player-1))
          "Hand should have exactly 1 card (the instant)"))))


(deftest grandeur-tapped-page-can-still-activate-grandeur-test
  (testing "A tapped Page can still activate Grandeur (no tap cost on Grandeur)"
    (let [db (th/create-test-db)
          [db page-id] (th/add-card-to-zone db :page-loose-leaf :battlefield :player-1)
          ;; Tap the page manually
          db (th/tap-permanent db page-id)
          _ (is (true? (:object/tapped (q/get-object db page-id)))
                "Precondition: Page is tapped")
          ;; Add a copy to hand
          [db _hand-page-id] (th/add-card-to-zone db :page-loose-leaf :hand :player-1)
          result (abilities/activate-ability db :player-1 page-id 1)
          sel (:pending-selection result)]
      (is (some? sel)
          "Grandeur should be activatable even when Page is tapped"))))


;; =====================================================
;; I. Ability Tests
;; =====================================================

(deftest page-loose-leaf-mana-ability-tap-produces-colorless-test
  (testing "Tapping Page, Loose Leaf adds 1 colorless mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :page-loose-leaf :battlefield :player-1)
          _ (is (= 0 (:colorless (q/get-mana-pool db :player-1)))
                "Precondition: colorless mana is 0")
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :colorless)]
      (is (= 1 (:colorless (q/get-mana-pool db-after :player-1)))
          "Should have 1 colorless mana after tap")
      (is (true? (:object/tapped (q/get-object db-after obj-id)))
          "Page should be tapped after mana ability")
      (is (= :battlefield (th/get-object-zone db-after obj-id))
          "Page should remain on battlefield"))))


(deftest page-loose-leaf-mana-ability-does-not-use-stack-test
  (testing "Mana ability activation does not create a stack item"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :page-loose-leaf :battlefield :player-1)
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :colorless)]
      (is (nil? (stack/get-top-stack-item db-after))
          "Mana ability must not put anything on the stack"))))


(deftest page-loose-leaf-mana-ability-does-not-increment-storm-test
  (testing "Activating mana ability does NOT increment storm count"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :page-loose-leaf :battlefield :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Precondition: storm count is 0")
          db-after (engine-mana/activate-mana-ability db :player-1 obj-id :colorless)]
      (is (= 0 (q/get-storm-count db-after :player-1))
          "Mana abilities do not increment storm"))))


(deftest page-loose-leaf-mana-ability-cannot-activate-when-tapped-test
  (testing "Already-tapped Page cannot activate mana ability"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :page-loose-leaf :battlefield :player-1)
          db-tapped (th/tap-permanent db obj-id)
          _ (is (true? (:object/tapped (q/get-object db-tapped obj-id)))
                "Precondition: Page is tapped")
          db-after (engine-mana/activate-mana-ability db-tapped :player-1 obj-id :colorless)]
      (is (= 0 (:colorless (q/get-mana-pool db-after :player-1)))
          "No mana should be added when Page is already tapped"))))


(deftest grandeur-full-chain-test
  (testing "Grandeur full chain: activate → discard → resolve → reveal → instant in hand"
    (let [db (th/create-test-db)
          [db page-id] (th/add-card-to-zone db :page-loose-leaf :battlefield :player-1)
          [db hand-page-id] (th/add-card-to-zone db :page-loose-leaf :hand :player-1)
          ;; Library: creature then instant
          [db [_creature-id instant-id]] (th/add-cards-to-library
                                           db [:nimble-mongoose :dark-ritual] :player-1)
          _ (is (= 1 (th/get-hand-count db :player-1))
                "Precondition: hand has 1 card (the Page copy to discard)")
          ;; Step 1: activate Grandeur
          result (abilities/activate-ability db :player-1 page-id 1)
          sel (:pending-selection result)
          _ (is (= :discard-cost (:selection/domain sel))
                "First selection should be discard-cost")
          ;; Step 2: confirm discard
          {:keys [db selection]} (th/confirm-selection (:db result) sel #{hand-page-id})
          _ (is (nil? selection) "No chaining after discard; stack item created")
          _ (is (= :graveyard (:object/zone (q/get-object db hand-page-id)))
                "Hand copy should be in graveyard")
          ;; Step 3: resolve ability → reveal-until selection
          {:keys [db selection]} (th/resolve-top db)
          _ (is (= :reveal-until (:selection/domain selection))
                "Post-resolve selection should be :reveal-until")
          ;; Step 4: confirm the instant
          {:keys [db]} (th/confirm-selection db selection #{instant-id})]
      (is (= :hand (:object/zone (q/get-object db instant-id)))
          "Instant should be in hand after grandeur resolves")
      (is (= 1 (th/get-zone-count db :library :player-1))
          "One non-matching library card remains (at bottom)"))))
