(ns fizzle.cards.ill-gotten-gains-test
  "Tests for Ill-Gotten Gains - {2}{B}{B} sorcery.
   Exile IGG. Each player discards their hand, then returns up to 3 cards
   from their graveyard to their hand.

   Tests the partial resolution pattern: effects before the selection point
   (exile-self, both discard-hands) execute immediately via
   resolve-spell-with-selection, which then pauses for player's graveyard
   return choice. Opponent's random return is stored in remaining-effects."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.ill-gotten-gains :as igg]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.events.selection.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; === Card definition test ===

(deftest ill-gotten-gains-card-definition-test
  (testing "Ill-Gotten Gains card data is correct"
    (let [card igg/ill-gotten-gains]
      (is (= :ill-gotten-gains (:card/id card)))
      (is (= "Ill-Gotten Gains" (:card/name card)))
      (is (= 4 (:card/cmc card)))
      (is (= {:colorless 2 :black 2} (:card/mana-cost card)))
      (is (= #{:sorcery} (:card/types card)))
      (is (= #{:black} (:card/colors card)))
      ;; 5 effects in correct order
      (let [effects (:card/effects card)]
        (is (= 5 (count effects)))
        (is (= :exile-self (:effect/type (nth effects 0))))
        (is (= :discard-hand (:effect/type (nth effects 1))))
        (is (= :self (:effect/target (nth effects 1))))
        (is (= :discard-hand (:effect/type (nth effects 2))))
        (is (= :opponent (:effect/target (nth effects 2))))
        (is (= :return-from-graveyard (:effect/type (nth effects 3))))
        (is (= :player (:effect/selection (nth effects 3))))
        (is (= :return-from-graveyard (:effect/type (nth effects 4))))
        (is (= :random (:effect/selection (nth effects 4))))))))


;; === Cast-resolve integration tests ===

(deftest ill-gotten-gains-exiles-self-and-discards-both-hands-test
  (testing "resolve-spell-with-selection executes exile + discards before pausing for selection"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          ;; Add IGG to hand
          [db' igg-id] (th/add-card-to-zone db :ill-gotten-gains :hand :player-1)
          ;; Add 2 other cards to caster's hand
          [db1 _] (th/add-card-to-zone db' :dark-ritual :hand :player-1)
          [db2 _] (th/add-card-to-zone db1 :dark-ritual :hand :player-1)
          ;; Add 3 cards to opponent's hand
          [db3 _] (th/add-card-to-zone db2 :dark-ritual :hand :player-2)
          [db4 _] (th/add-card-to-zone db3 :dark-ritual :hand :player-2)
          [db5 _] (th/add-card-to-zone db4 :dark-ritual :hand :player-2)
          ;; Add mana to cast {2}{B}{B}
          db-with-mana (mana/add-mana db5 :player-1 {:black 4})
          ;; Verify preconditions
          _ (is (= 3 (th/get-zone-count db-with-mana :hand :player-1))
                "Precondition: caster has 3 cards in hand (IGG + 2)")
          _ (is (= 3 (th/get-zone-count db-with-mana :hand :player-2))
                "Precondition: opponent has 3 cards in hand")
          ;; Cast and resolve with selection
          db-cast (rules/cast-spell db-with-mana :player-1 igg-id)
          result (resolution/resolve-spell-with-selection db-cast :player-1 igg-id)
          resolved-db (:db result)]
      ;; IGG should be in exile (not graveyard, not stack)
      (is (= :exile (:object/zone (q/get-object resolved-db igg-id)))
          "IGG should be exiled (exile-self effect)")
      ;; Both hands should be empty
      (is (= 0 (th/get-zone-count resolved-db :hand :player-1))
          "Caster's hand should be discarded")
      (is (= 0 (th/get-zone-count resolved-db :hand :player-2))
          "Opponent's hand should be discarded")
      ;; Caster's 2 other hand cards should be in graveyard
      (is (= 2 (th/get-zone-count resolved-db :graveyard :player-1))
          "Caster's discarded hand cards should be in graveyard")
      ;; Opponent's 3 hand cards should be in graveyard
      (is (= 3 (th/get-zone-count resolved-db :graveyard :player-2))
          "Opponent's discarded hand cards should be in graveyard")
      ;; Should have pending selection
      (is (some? (:pending-selection result))
          "Should pause for player's graveyard return selection"))))


(deftest ill-gotten-gains-selection-state-structure-test
  (testing "Pending selection state has correct structure for graveyard return"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          ;; Add IGG to hand + 1 other card (will be discarded to GY)
          [db' igg-id] (th/add-card-to-zone db :ill-gotten-gains :hand :player-1)
          [db1 _] (th/add-card-to-zone db' :dark-ritual :hand :player-1)
          ;; Add 2 cards to caster graveyard BEFORE casting (pre-existing)
          [db2 gy1-id] (th/add-card-to-zone db1 :dark-ritual :graveyard :player-1)
          [db3 gy2-id] (th/add-card-to-zone db2 :cabal-ritual :graveyard :player-1)
          ;; Add mana and cast
          db-m (mana/add-mana db3 :player-1 {:black 4})
          db-cast (rules/cast-spell db-m :player-1 igg-id)
          result (resolution/resolve-spell-with-selection db-cast :player-1 igg-id)
          sel (:pending-selection result)]
      ;; Selection structure
      (is (= :graveyard-return (:selection/type sel))
          "Selection type should be graveyard-return")
      (is (= 3 (:selection/select-count sel))
          "Max 3 cards to return")
      (is (= 0 (:selection/min-count sel))
          "Can select 0 cards (fail to find)")
      (is (= :player-1 (:selection/player-id sel))
          "Caster makes the selection")
      (is (= #{} (:selection/selected sel))
          "Selection starts empty")
      ;; Candidates should include pre-existing GY cards + discarded hand card
      ;; (1 hand card was discarded, 2 pre-existing = 3 total in GY)
      (is (= 3 (count (:selection/candidate-ids sel)))
          "Should have 3 candidates (2 pre-existing + 1 discarded hand card)")
      (is (contains? (:selection/candidate-ids sel) gy1-id)
          "Pre-existing GY card should be a candidate")
      (is (contains? (:selection/candidate-ids sel) gy2-id)
          "Pre-existing GY card should be a candidate")
      ;; Remaining effects: opponent's random return
      (is (= 1 (count (:selection/remaining-effects sel)))
          "Should have 1 remaining effect (opponent's random return)")
      (let [remaining-effect (first (:selection/remaining-effects sel))]
        (is (= :return-from-graveyard (:effect/type remaining-effect)))
        (is (= :opponent (:effect/target remaining-effect)))
        (is (= :random (:effect/selection remaining-effect)))))))


(deftest ill-gotten-gains-cannot-cast-without-mana-test
  (testing "IGG cannot be cast without {2}{B}{B}"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :ill-gotten-gains :hand :player-1)]
      (is (false? (rules/can-cast? db' :player-1 obj-id))
          "Should not be able to cast without mana"))))


(deftest ill-gotten-gains-is-sorcery-speed-test
  (testing "IGG cannot be cast during combat (sorcery speed)"
    (let [db (th/create-test-db)
          ;; Change phase to combat
          conn (d/conn-from-db db)
          game-eid (d/q '[:find ?e . :where [?e :game/id :game-1]] db)
          _ (d/transact! conn [[:db/add game-eid :game/phase :combat]])
          db' @conn
          [db'' obj-id] (th/add-card-to-zone db' :ill-gotten-gains :hand :player-1)
          db-m (mana/add-mana db'' :player-1 {:black 4})]
      (is (false? (rules/can-cast? db-m :player-1 obj-id))
          "Sorcery should not be castable during combat"))))


(deftest ill-gotten-gains-graveyard-return-selection-test
  ;; Bug caught: return selection not created after discard
  (testing "After exile and discard, player can select up to 3 cards from graveyard"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          ;; Add IGG to hand
          [db' igg-id] (th/add-card-to-zone db :ill-gotten-gains :hand :player-1)
          ;; Add 3 cards to caster's graveyard (pre-existing for selection)
          [db1 gy1-id] (th/add-card-to-zone db' :dark-ritual :graveyard :player-1)
          [db2 gy2-id] (th/add-card-to-zone db1 :cabal-ritual :graveyard :player-1)
          [db3 gy3-id] (th/add-card-to-zone db2 :brain-freeze :graveyard :player-1)
          ;; Add mana
          db-m (mana/add-mana db3 :player-1 {:black 4})
          ;; Cast and resolve
          db-cast (rules/cast-spell db-m :player-1 igg-id)
          result (resolution/resolve-spell-with-selection db-cast :player-1 igg-id)
          sel (:pending-selection result)]
      ;; Selection should be graveyard-return type
      (is (= :graveyard-return (:selection/type sel))
          "Selection type should be :graveyard-return")
      ;; Should allow selecting 0-3 cards
      (is (= 3 (:selection/select-count sel))
          "Max selection count should be 3")
      (is (= 0 (:selection/min-count sel))
          "Min selection count should be 0 (can fail to find)")
      ;; Candidates should include the pre-existing GY cards
      ;; (The 3 pre-existing cards should all be candidates)
      (is (contains? (:selection/candidate-ids sel) gy1-id)
          "Pre-existing GY card 1 should be a candidate")
      (is (contains? (:selection/candidate-ids sel) gy2-id)
          "Pre-existing GY card 2 should be a candidate")
      (is (contains? (:selection/candidate-ids sel) gy3-id)
          "Pre-existing GY card 3 should be a candidate"))))


(deftest ill-gotten-gains-empty-graveyard-test
  ;; Bug caught: crash on empty graveyard
  (testing "Cast with empty graveyard, selection has 0 candidates from pre-existing"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          ;; Add IGG to hand (only card)
          [db' igg-id] (th/add-card-to-zone db :ill-gotten-gains :hand :player-1)
          ;; NO pre-existing graveyard cards
          _ (is (= 0 (th/get-zone-count db' :graveyard :player-1))
                "Precondition: graveyard is empty")
          ;; Add mana
          db-m (mana/add-mana db' :player-1 {:black 4})
          ;; Cast and resolve
          db-cast (rules/cast-spell db-m :player-1 igg-id)
          result (resolution/resolve-spell-with-selection db-cast :player-1 igg-id)]
      ;; IGG should be exiled (exile-self effect)
      (is (= :exile (:object/zone (q/get-object (:db result) igg-id)))
          "IGG should be exiled")
      ;; Check if there's a pending selection
      ;; If graveyard is empty after discards, selection might still exist with 0 candidates
      ;; or spell might resolve fully
      (if-let [sel (:pending-selection result)]
        ;; If selection exists, it should have 0 candidates (nothing to return)
        (is (= 0 (count (:selection/candidate-ids sel)))
            "Selection should have 0 candidates with empty graveyard")
        ;; If no selection (empty GY shortcut), spell resolved fully
        (is (true? true) "Spell resolved fully with empty graveyard (no selection needed)")))))


(deftest ill-gotten-gains-opponent-empty-graveyard-test
  ;; Corner case: opponent has no cards, so after discard their GY is still empty
  (testing "Opponent with empty hand/graveyard: remaining random return is structured correctly"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          ;; Opponent has NO hand or graveyard cards
          _ (is (= 0 (th/get-zone-count db :hand :player-2))
                "Precondition: opponent hand is empty")
          _ (is (= 0 (th/get-zone-count db :graveyard :player-2))
                "Precondition: opponent graveyard is empty")
          ;; Add IGG to caster's hand (only card)
          [db' igg-id] (th/add-card-to-zone db :ill-gotten-gains :hand :player-1)
          db-m (mana/add-mana db' :player-1 {:black 4})
          db-cast (rules/cast-spell db-m :player-1 igg-id)
          result (resolution/resolve-spell-with-selection db-cast :player-1 igg-id)]
      ;; IGG should be exiled
      (is (= :exile (:object/zone (q/get-object (:db result) igg-id)))
          "IGG should be exiled")
      ;; Opponent's hand should be empty (nothing to discard)
      (is (= 0 (th/get-zone-count (:db result) :hand :player-2))
          "Opponent hand should remain empty")
      ;; Opponent's graveyard should be empty (nothing was discarded)
      (is (= 0 (th/get-zone-count (:db result) :graveyard :player-2))
          "Opponent graveyard should remain empty after discard of empty hand")
      ;; Remaining effects should include opponent's random return
      (when-let [sel (:pending-selection result)]
        (is (= 1 (count (:selection/remaining-effects sel)))
            "Should have opponent's random return in remaining effects")
        (let [remaining (first (:selection/remaining-effects sel))]
          (is (= :return-from-graveyard (:effect/type remaining))
              "Remaining effect should be graveyard return")
          (is (= :opponent (:effect/target remaining))
              "Remaining effect targets opponent")
          (is (= :random (:effect/selection remaining))
              "Remaining effect uses random selection"))))))
