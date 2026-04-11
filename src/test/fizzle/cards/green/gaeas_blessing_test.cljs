(ns fizzle.cards.green.gaeas-blessing-test
  "Tests for Gaea's Blessing card.

   Gaea's Blessing: {1}{G} - Sorcery (Weatherlight)
   Oracle text (Scryfall-verified):
   'Target player shuffles up to three target cards from their graveyard into
   their library. Draw a card. When this card is put into your graveyard from
   your library, shuffle your graveyard into your library.'

   Categories:
   A. Card definition
   B. Cast-resolve happy path
   C. Cannot-cast guards
   D. Storm count
   E. Selection tests (up to 3, zero, capped by gy size)
   F. Targeting tests (target self, target opponent)
   I. Library trigger tests (fires on mill, NOT on discard, NOT on wrong card)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.green.gaeas-blessing :as gaeas-blessing]
    [fizzle.db.queries :as q]
    [fizzle.engine.card-spec :as card-spec]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.trigger-db :as trigger-db]
    [fizzle.engine.zone-change-dispatch :as zone-change-dispatch]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Fixture helpers
;; =====================================================

(defn- get-stack-items
  "Return all stack items by position."
  [db]
  (d/q '[:find [(pull ?e [*]) ...]
         :where [?e :stack-item/position _]]
       db))


(defn- add-gb-to-library-with-trigger
  "Add a Gaea's Blessing object to library and register its triggers via
   the production create-triggers-for-card-tx path. Reads :card/triggers
   from the card definition — if the card changes, this helper stays in sync.

   Returns [db obj-id]."
  [db owner]
  (let [[db obj-id] (th/add-card-to-zone db :gaeas-blessing :library owner)
        obj-eid (d/q '[:find ?e . :in $ ?oid :where [?e :object/id ?oid]] db obj-id)
        player-eid (q/get-player-eid db owner)
        db (d/db-with db (trigger-db/create-triggers-for-card-tx
                           db obj-eid player-eid
                           (:card/triggers gaeas-blessing/card)))]
    [db obj-id]))


;; =====================================================
;; A. Card Definition Tests
;; =====================================================

(deftest gaeas-blessing-card-definition-test
  (testing "card has correct oracle properties"
    (let [card gaeas-blessing/card]
      (is (= :gaeas-blessing (:card/id card))
          "Card ID should be :gaeas-blessing")
      (is (= "Gaea's Blessing" (:card/name card))
          "Card name should match oracle")
      (is (= 2 (:card/cmc card))
          "CMC should be 2")
      (is (= {:colorless 1 :green 1} (:card/mana-cost card))
          "Mana cost should be {1}{G}")
      (is (= #{:green} (:card/colors card))
          "Card should be green")
      (is (= #{:sorcery} (:card/types card))
          "Card should be a sorcery")
      (is (= "Target player shuffles up to three target cards from their graveyard into their library. Draw a card. When this card is put into your graveyard from your library, shuffle your graveyard into your library."
             (:card/text card))
          "Card text should match Scryfall oracle exactly")))

  (testing "card has correct targeting (any player)"
    (let [targeting (:card/targeting gaeas-blessing/card)]
      (is (vector? targeting)
          "Targeting must be a vector")
      (is (= 1 (count targeting))
          "Should have exactly one target requirement")
      (let [req (first targeting)]
        (is (= :player (:target/id req))
            "Target ID should be :player")
        (is (= :player (:target/type req))
            "Target type should be :player")
        (is (= #{:any-player} (:target/options req))
            "Should allow targeting any player (self or opponent)")
        (is (true? (:target/required req))
            "Target should be required"))))

  (testing "card has correct sorcery effects"
    (let [effects (:card/effects gaeas-blessing/card)]
      (is (= 2 (count effects))
          "Should have exactly 2 effects (shuffle and draw)")
      (let [shuffle-effect (first effects)
            draw-effect (second effects)]
        (is (= :shuffle-from-graveyard-to-library (:effect/type shuffle-effect))
            "First effect should be :shuffle-from-graveyard-to-library")
        (is (= :targeted-player (:effect/target shuffle-effect))
            "Shuffle effect targets the targeted player")
        (is (= 3 (:effect/count shuffle-effect))
            "Shuffle effect allows up to 3 cards")
        (is (= :player (:effect/selection shuffle-effect))
            "Shuffle effect is interactive (:player selection)")
        (is (= :draw (:effect/type draw-effect))
            "Second effect should be :draw")
        (is (= 1 (:effect/amount draw-effect))
            "Draw exactly 1 card"))))

  (testing "card has correct library zone-change trigger"
    (let [triggers (:card/triggers gaeas-blessing/card)]
      (is (= 1 (count triggers))
          "Should have exactly 1 trigger")
      (let [trigger (first triggers)]
        (is (= :zone-change (:trigger/type trigger))
            "Trigger type should be :zone-change")
        (is (= {:event/from-zone :library
                :event/to-zone :graveyard
                :event/object-id :self}
               (:trigger/match trigger))
            "Trigger match should fire only for library->graveyard of :self")
        (let [effects (:trigger/effects trigger)]
          (is (= 1 (count effects))
              "Trigger should have 1 effect")
          (let [trigger-effect (first effects)]
            (is (= :shuffle-from-graveyard-to-library (:effect/type trigger-effect))
                "Trigger effect type should be :shuffle-from-graveyard-to-library")
            (is (= :all (:effect/count trigger-effect))
                "Trigger effect should use :all (entire graveyard)")
            (is (= :auto (:effect/selection trigger-effect))
                "Trigger effect is non-interactive (:auto)")
            (is (= :self-controller (:effect/target trigger-effect))
                "Trigger effect targets self-controller"))))))

  (testing "card passes card spec validation"
    (is (true? (card-spec/valid-card? gaeas-blessing/card))
        "Gaea's Blessing must pass card-spec/valid-card?")))


;; =====================================================
;; B. Cast-Resolve Happy Path
;; =====================================================

;; Oracle: "Target player shuffles up to three target cards from their graveyard
;; into their library. Draw a card."
;;
;; Note: The draw effect fires AFTER the shuffle selection is confirmed.
;; GB goes to graveyard (sorcery cleanup) during confirm, so the total
;; graveyard count stays the same (ritual leaves, GB arrives). We test:
;;   1. Ritual left the graveyard (not= :graveyard)
;;   2. Caster's hand grew by 1 (draw fired) — measured from post-cast count
;;      because GB is on stack (not in hand) after casting.
(deftest gaeas-blessing-cast-resolve-happy-path-test
  (testing "cast and resolve against own graveyard: ritual shuffled to library + caster draws"
    (let [db (th/create-test-db {:mana {:colorless 1 :green 1}})
          db (th/add-opponent db)
          [db gb-id] (th/add-card-to-zone db :gaeas-blessing :hand :player-1)
          ;; Add cards to graveyard (select target)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          ;; Add extra library cards so draw fires cleanly (not the ritual)
          [db _lib-1] (th/add-card-to-zone db :dark-ritual :library :player-1)
          [db _lib-2] (th/add-card-to-zone db :dark-ritual :library :player-1)
          ;; Cast targeting self — GB leaves hand, goes on stack
          db-cast (th/cast-with-target db :player-1 gb-id :player-1)
          ;; Hand count after casting: GB is on stack (not in hand)
          hand-count-post-cast (th/get-hand-count db-cast :player-1)
          ;; Resolve: zone-pick selection appears for graveyard cards
          {:keys [db selection]} (th/resolve-top db-cast)]
      ;; Selection should be the shuffle-from-graveyard-to-library type
      (is (= :shuffle-from-graveyard-to-library (:selection/type selection))
          "Should enter shuffle-from-graveyard-to-library selection")
      (is (= :player-1 (:selection/player-id selection))
          "Selection player-id should be player-1 (the targeted player)")
      (is (contains? (:selection/candidate-ids selection) ritual-id)
          "Dark Ritual should be a candidate in the selection")
      ;; Confirm selecting dark-ritual
      (let [{:keys [db]} (th/confirm-selection db selection #{ritual-id})]
        ;; Dark Ritual left the graveyard — not= :graveyard since draw may pull it to hand
        (is (not= :graveyard (th/get-object-zone db ritual-id))
            "Dark Ritual should not remain in graveyard after being selected for shuffle")
        ;; The card is now in library or hand (not graveyard)
        (is (contains? #{:library :hand} (th/get-object-zone db ritual-id))
            "Dark Ritual should be in library or hand (draw may pull it back from library)")
        ;; Caster drew a card (draw fires after confirm — library had extra cards)
        ;; Baseline: hand count after casting (GB on stack = 0 hand cards)
        (is (= (inc hand-count-post-cast)
               (th/get-hand-count db :player-1))
            "Player-1 should have drawn 1 card (draw effect fires after shuffle)")))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

(deftest gaeas-blessing-cannot-cast-from-graveyard-test
  (testing "Cannot cast Gaea's Blessing from graveyard"
    (let [db (th/create-test-db {:mana {:colorless 1 :green 1}})
          db (th/add-opponent db)
          [db gb-id] (th/add-card-to-zone db :gaeas-blessing :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 gb-id))
          "Cannot cast Gaea's Blessing from graveyard"))))


(deftest gaeas-blessing-cannot-cast-without-green-mana-test
  (testing "Cannot cast Gaea's Blessing without {1}{G} (only {1} available)"
    (let [db (th/create-test-db {:mana {:colorless 1}})
          db (th/add-opponent db)
          [db gb-id] (th/add-card-to-zone db :gaeas-blessing :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 gb-id))
          "Cannot cast Gaea's Blessing with only {1} mana"))))


(deftest gaeas-blessing-cannot-cast-without-colorless-mana-test
  (testing "Cannot cast Gaea's Blessing without {1}{G} (only {G} available)"
    (let [db (th/create-test-db {:mana {:green 1}})
          db (th/add-opponent db)
          [db gb-id] (th/add-card-to-zone db :gaeas-blessing :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 gb-id))
          "Cannot cast Gaea's Blessing with only {G} mana"))))


;; =====================================================
;; D. Storm Count
;; =====================================================

(deftest gaeas-blessing-increments-storm-count-test
  (testing "Casting Gaea's Blessing increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 1 :green 1}})
          db (th/add-opponent db)
          [db gb-id] (th/add-card-to-zone db :gaeas-blessing :hand :player-1)
          ;; Pre-populate library so draw can fire (avoids drew-from-empty side effect)
          [db _lib] (th/add-card-to-zone db :dark-ritual :library :player-1)
          storm-before (q/get-storm-count db :player-1)
          ;; Cast targeting self (graveyard is empty — legal per "up to three")
          db-cast (th/cast-with-target db :player-1 gb-id :player-1)
          {:keys [db selection]} (th/resolve-top db-cast)
          ;; Confirm with 0 selected (empty graveyard)
          {:keys [db]} (th/confirm-selection db selection #{})]
      (is (= (inc storm-before) (q/get-storm-count db :player-1))
          "Storm count must increment by 1 when Gaea's Blessing is cast"))))


;; =====================================================
;; E. Selection Tests
;; =====================================================

;; Oracle: "up to three target cards from their graveyard"
(deftest gaeas-blessing-selection-up-to-3-validation-test
  (testing "Selection shows :at-most validation with select-count 3, all 5 GY cards offered"
    (let [db (th/create-test-db {:mana {:colorless 1 :green 1}})
          db (th/add-opponent db)
          [db gb-id] (th/add-card-to-zone db :gaeas-blessing :hand :player-1)
          ;; Add 5 cards to graveyard
          [db id-1] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          [db id-2] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          [db id-3] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          [db id-4] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          [db _id-5] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          ;; Library cards so draw fires cleanly
          [db _lib] (th/add-card-to-zone db :dark-ritual :library :player-1)
          db-cast (th/cast-with-target db :player-1 gb-id :player-1)
          {:keys [db selection]} (th/resolve-top db-cast)
          sel selection]
      (is (= :shuffle-from-graveyard-to-library (:selection/type sel))
          "Selection type should be :shuffle-from-graveyard-to-library")
      (is (= :at-most (:selection/validation sel))
          "Validation should be :at-most (not :exact)")
      (is (= 3 (:selection/select-count sel))
          "Select count should be 3 (up to three)")
      (is (= 5 (count (:selection/candidate-ids sel)))
          "All 5 graveyard cards should be candidates")
      ;; Confirm with 3 selected — id-4 must remain in graveyard
      ;; Note: draw fires after confirm so library card goes to hand (not the selected ones)
      (let [{:keys [db]} (th/confirm-selection db sel #{id-1 id-2 id-3})]
        ;; All 3 selected cards left the graveyard (may be in library or hand after draw)
        (is (not= :graveyard (th/get-object-zone db id-1)) "Card 1 left graveyard")
        (is (not= :graveyard (th/get-object-zone db id-2)) "Card 2 left graveyard")
        (is (not= :graveyard (th/get-object-zone db id-3)) "Card 3 left graveyard")
        ;; Cards 4 and 5 were NOT selected and remain in graveyard
        (is (= :graveyard (th/get-object-zone db id-4)) "Card 4 stays in graveyard")
        ;; Graveyard has id-4, _id-5, and GB (cleanup) — selected cards (id-1/2/3) left
        ;; Net change: graveyard had 5, lost 3 selected, gained GB on cleanup = 3 total
        (is (= 3 (count (q/get-objects-in-zone db :player-1 :graveyard)))
            "Graveyard should have 3 cards: id-4, id-5 (not selected) + GB (cleanup)")))))


(deftest gaeas-blessing-selection-zero-cards-legal-test
  (testing "Can confirm with zero cards selected — up to three allows zero"
    (let [db (th/create-test-db {:mana {:colorless 1 :green 1}})
          db (th/add-opponent db)
          [db gb-id] (th/add-card-to-zone db :gaeas-blessing :hand :player-1)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          ;; Library card so draw fires (draw from empty library is a no-op)
          [db _lib] (th/add-card-to-zone db :dark-ritual :library :player-1)
          db-cast (th/cast-with-target db :player-1 gb-id :player-1)
          ;; Measure hand count after casting (GB on stack, not in hand)
          hand-post-cast (th/get-hand-count db-cast :player-1)
          {:keys [db selection]} (th/resolve-top db-cast)
          ;; Confirm with 0 selected — should not throw
          {:keys [db]} (th/confirm-selection db selection #{})]
      ;; Ritual should remain in graveyard (not selected)
      (is (= :graveyard (th/get-object-zone db ritual-id))
          "Dark Ritual stays in graveyard when zero selected")
      ;; Draw still fires — library had a card; baseline from post-cast hand count
      (is (= (inc hand-post-cast) (th/get-hand-count db :player-1))
          "Caster should still draw 1 card even when 0 graveyard cards selected"))))


(deftest gaeas-blessing-selection-capped-by-graveyard-size-test
  (testing "Selection candidates are capped by actual graveyard size (2, not 3)"
    (let [db (th/create-test-db {:mana {:colorless 1 :green 1}})
          db (th/add-opponent db)
          [db gb-id] (th/add-card-to-zone db :gaeas-blessing :hand :player-1)
          ;; Only 2 cards in graveyard
          [db id-1] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          [db id-2] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          db-cast (th/cast-with-target db :player-1 gb-id :player-1)
          {:keys [selection]} (th/resolve-top db-cast)
          sel selection]
      (is (= 2 (count (:selection/candidate-ids sel)))
          "Should only show 2 candidates (matching actual graveyard size)")
      (is (contains? (:selection/candidate-ids sel) id-1)
          "Card 1 should be a candidate")
      (is (contains? (:selection/candidate-ids sel) id-2)
          "Card 2 should be a candidate"))))


;; =====================================================
;; F. Targeting Tests
;; =====================================================

(deftest gaeas-blessing-targets-own-graveyard-test
  (testing "Casting targeting self: selection shows own graveyard cards only"
    (let [db (th/create-test-db {:mana {:colorless 1 :green 1}})
          db (th/add-opponent db)
          [db gb-id] (th/add-card-to-zone db :gaeas-blessing :hand :player-1)
          ;; Add cards to each player's graveyard
          [db own-id] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          [db _opp-id] (th/add-card-to-zone db :dark-ritual :graveyard :player-2)
          db-cast (th/cast-with-target db :player-1 gb-id :player-1)
          {:keys [selection]} (th/resolve-top db-cast)
          sel selection]
      (is (= :player-1 (:selection/player-id sel))
          "Selection player-id should be player-1 (self-targeted)")
      (is (contains? (:selection/candidate-ids sel) own-id)
          "Own graveyard card should be a candidate")
      ;; Candidates must not include opponent's graveyard cards
      (is (= 1 (count (:selection/candidate-ids sel)))
          "Only player-1's graveyard cards should be candidates (not opponent's)"))))


(deftest gaeas-blessing-targets-opponent-graveyard-test
  (testing "Casting targeting opponent: selection shows opponent's graveyard cards"
    (let [db (th/create-test-db {:mana {:colorless 1 :green 1}})
          db (th/add-opponent db)
          [db gb-id] (th/add-card-to-zone db :gaeas-blessing :hand :player-1)
          ;; Add cards to player-2's graveyard only
          [db opp-id] (th/add-card-to-zone db :dark-ritual :graveyard :player-2)
          ;; Cast targeting opponent (player-2)
          db-cast (th/cast-with-target db :player-1 gb-id :player-2)
          {:keys [db selection]} (th/resolve-top db-cast)
          sel selection
          opp-gy-before (count (q/get-objects-in-zone db :player-2 :graveyard))]
      (is (= :player-2 (:selection/player-id sel))
          "Selection player-id should be player-2 (opponent-targeted)")
      (is (contains? (:selection/candidate-ids sel) opp-id)
          "Opponent's graveyard card should be a candidate")
      ;; Confirm: opponent's card should leave opponent's graveyard (move to their library)
      (let [{:keys [db]} (th/confirm-selection db sel #{opp-id})]
        (is (< (count (q/get-objects-in-zone db :player-2 :graveyard))
               opp-gy-before)
            "Opponent's graveyard should shrink after selection")
        (is (= :library (th/get-object-zone db opp-id))
            "Opponent's card should be in library after shuffle selection")))))


;; =====================================================
;; I. Library Trigger Tests
;; =====================================================

;; Oracle: "When this card is put into your graveyard from your library,
;; shuffle your graveyard into your library."
(deftest gaeas-blessing-trigger-fires-on-mill-test
  (testing "Library trigger fires when GB moves library->graveyard"
    (let [db (th/create-test-db)
          [db gb-id] (add-gb-to-library-with-trigger db :player-1)
          stack-before (count (get-stack-items db))
          ;; Mill GB from library to graveyard (canonical zone-change path)
          db-after (zone-change-dispatch/move-to-zone db gb-id :graveyard)
          stack-after (get-stack-items db-after)]
      (is (= 0 stack-before) "Precondition: stack starts empty")
      (is (= 1 (count stack-after))
          "Trigger should land on stack when GB is milled from library"))))


(deftest gaeas-blessing-trigger-does-not-fire-on-discard-test
  (testing "Library trigger does NOT fire when GB moves hand->graveyard (discard)"
    (let [db (th/create-test-db)
          ;; Add GB to hand (not library) — no trigger registered
          [db gb-id] (th/add-card-to-zone db :gaeas-blessing :hand :player-1)
          stack-before (count (get-stack-items db))
          ;; Discard (hand->graveyard) — should NOT fire library trigger
          db-after (zone-change-dispatch/move-to-zone db gb-id :graveyard)
          stack-after (count (get-stack-items db-after))]
      (is (= 0 (- stack-after stack-before))
          "No trigger should fire on hand->graveyard (discard)"))))


(deftest gaeas-blessing-trigger-self-sigil-different-card-test
  (testing ":self sigil: milling a different card does NOT fire GB's trigger"
    (let [db (th/create-test-db)
          ;; Add GB to library with trigger
          [db _gb-id] (add-gb-to-library-with-trigger db :player-1)
          ;; Add a plain dark-ritual to library (no trigger)
          [db plain-id] (th/add-card-to-zone db :dark-ritual :library :player-1)
          stack-before (count (get-stack-items db))
          ;; Mill the dark-ritual (not GB) — GB's :self sigil should exclude it
          db-after (zone-change-dispatch/move-to-zone db plain-id :graveyard)
          new-items (- (count (get-stack-items db-after)) stack-before)]
      (is (= 0 new-items)
          ":self sigil: GB trigger does NOT fire when a different card is milled"))))


(deftest gaeas-blessing-trigger-resolves-shuffles-graveyard-test
  (testing "Trigger resolves: all graveyard cards (including GB) shuffle to library"
    (let [db (th/create-test-db)
          [db gb-id] (add-gb-to-library-with-trigger db :player-1)
          ;; Add 2 extra cards to graveyard before milling
          [db _id1] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          [db _id2] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          gy-count-before (count (q/get-objects-in-zone db :player-1 :graveyard))
          ;; Mill GB — trigger lands on stack
          db-after-mill (zone-change-dispatch/move-to-zone db gb-id :graveyard)
          stack-items (get-stack-items db-after-mill)
          gy-count-after-mill (count (q/get-objects-in-zone db-after-mill :player-1 :graveyard))]
      ;; Verify preconditions
      (is (= 2 gy-count-before) "Precondition: 2 cards in graveyard before mill")
      (is (= 1 (count stack-items)) "Precondition: trigger on stack")
      (is (= 3 gy-count-after-mill) "After mill: GB + 2 pre-existing = 3 cards in graveyard")
      ;; Resolve the trigger (non-interactive: :auto path)
      (let [{:keys [db]} (th/resolve-top db-after-mill)]
        (is (= 0 (count (q/get-objects-in-zone db :player-1 :graveyard)))
            "After trigger resolves: graveyard should be empty (all shuffled to library)")
        ;; GB itself shuffled back to library
        (is (= :library (th/get-object-zone db gb-id))
            "Gaea's Blessing itself shuffles back into library after trigger")))))
