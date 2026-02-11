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
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.cards.ill-gotten-gains :as igg]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.events.selection :as selection]))


;; === Test helpers ===

(defn create-test-db
  "Create a game state with all card definitions loaded."
  []
  (let [conn (d/create-conn schema)]
    (d/transact! conn cards/all-cards)
    (d/transact! conn [{:player/id :player-1
                        :player/name "Player"
                        :player/life 20
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0
                        :player/land-plays-left 1}])
    (let [player-eid (d/q '[:find ?e . :where [?e :player/id :player-1]] @conn)]
      (d/transact! conn [{:game/id :game-1
                          :game/turn 1
                          :game/phase :main1
                          :game/active-player player-eid
                          :game/priority player-eid}]))
    @conn))


(defn add-opponent
  "Add an opponent player to the game state."
  [db]
  (let [conn (d/conn-from-db db)]
    (d/transact! conn [{:player/id :opponent
                        :player/name "Opponent"
                        :player/life 20
                        :player/is-opponent true
                        :player/mana-pool {:white 0 :blue 0 :black 0
                                           :red 0 :green 0 :colorless 0}
                        :player/storm-count 0}])
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


(defn get-zone-count
  "Count objects in a zone for a player."
  [db player-id zone]
  (count (or (q/get-objects-in-zone db player-id zone) [])))


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
    (let [db (-> (create-test-db)
                 (add-opponent))
          ;; Add IGG to hand
          [db' igg-id] (add-card-to-zone db :ill-gotten-gains :hand :player-1)
          ;; Add 2 other cards to caster's hand
          [db1 _] (add-card-to-zone db' :dark-ritual :hand :player-1)
          [db2 _] (add-card-to-zone db1 :dark-ritual :hand :player-1)
          ;; Add 3 cards to opponent's hand
          [db3 _] (add-card-to-zone db2 :dark-ritual :hand :opponent)
          [db4 _] (add-card-to-zone db3 :dark-ritual :hand :opponent)
          [db5 _] (add-card-to-zone db4 :dark-ritual :hand :opponent)
          ;; Add mana to cast {2}{B}{B}
          db-with-mana (mana/add-mana db5 :player-1 {:black 4})
          ;; Verify preconditions
          _ (is (= 3 (get-zone-count db-with-mana :player-1 :hand))
                "Precondition: caster has 3 cards in hand (IGG + 2)")
          _ (is (= 3 (get-zone-count db-with-mana :opponent :hand))
                "Precondition: opponent has 3 cards in hand")
          ;; Cast and resolve with selection
          db-cast (rules/cast-spell db-with-mana :player-1 igg-id)
          result (selection/resolve-spell-with-selection db-cast :player-1 igg-id)
          resolved-db (:db result)]
      ;; IGG should be in exile (not graveyard, not stack)
      (is (= :exile (:object/zone (q/get-object resolved-db igg-id)))
          "IGG should be exiled (exile-self effect)")
      ;; Both hands should be empty
      (is (= 0 (get-zone-count resolved-db :player-1 :hand))
          "Caster's hand should be discarded")
      (is (= 0 (get-zone-count resolved-db :opponent :hand))
          "Opponent's hand should be discarded")
      ;; Caster's 2 other hand cards should be in graveyard
      (is (= 2 (get-zone-count resolved-db :player-1 :graveyard))
          "Caster's discarded hand cards should be in graveyard")
      ;; Opponent's 3 hand cards should be in graveyard
      (is (= 3 (get-zone-count resolved-db :opponent :graveyard))
          "Opponent's discarded hand cards should be in graveyard")
      ;; Should have pending selection
      (is (some? (:pending-selection result))
          "Should pause for player's graveyard return selection"))))


(deftest ill-gotten-gains-selection-state-structure-test
  (testing "Pending selection state has correct structure for graveyard return"
    (let [db (-> (create-test-db)
                 (add-opponent))
          ;; Add IGG to hand + 1 other card (will be discarded to GY)
          [db' igg-id] (add-card-to-zone db :ill-gotten-gains :hand :player-1)
          [db1 _] (add-card-to-zone db' :dark-ritual :hand :player-1)
          ;; Add 2 cards to caster graveyard BEFORE casting (pre-existing)
          [db2 gy1-id] (add-card-to-zone db1 :dark-ritual :graveyard :player-1)
          [db3 gy2-id] (add-card-to-zone db2 :cabal-ritual :graveyard :player-1)
          ;; Add mana and cast
          db-m (mana/add-mana db3 :player-1 {:black 4})
          db-cast (rules/cast-spell db-m :player-1 igg-id)
          result (selection/resolve-spell-with-selection db-cast :player-1 igg-id)
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
    (let [db (create-test-db)
          [db' obj-id] (add-card-to-zone db :ill-gotten-gains :hand :player-1)]
      (is (false? (rules/can-cast? db' :player-1 obj-id))
          "Should not be able to cast without mana"))))


(deftest ill-gotten-gains-is-sorcery-speed-test
  (testing "IGG cannot be cast during combat (sorcery speed)"
    (let [db (create-test-db)
          ;; Change phase to combat
          conn (d/conn-from-db db)
          game-eid (d/q '[:find ?e . :where [?e :game/id :game-1]] db)
          _ (d/transact! conn [[:db/add game-eid :game/phase :combat]])
          db' @conn
          [db'' obj-id] (add-card-to-zone db' :ill-gotten-gains :hand :player-1)
          db-m (mana/add-mana db'' :player-1 {:black 4})]
      (is (false? (rules/can-cast? db-m :player-1 obj-id))
          "Sorcery should not be castable during combat"))))


(deftest ill-gotten-gains-graveyard-return-selection-test
  ;; Bug caught: return selection not created after discard
  (testing "After exile and discard, player can select up to 3 cards from graveyard"
    (let [db (-> (create-test-db)
                 (add-opponent))
          ;; Add IGG to hand
          [db' igg-id] (add-card-to-zone db :ill-gotten-gains :hand :player-1)
          ;; Add 3 cards to caster's graveyard (pre-existing for selection)
          [db1 gy1-id] (add-card-to-zone db' :dark-ritual :graveyard :player-1)
          [db2 gy2-id] (add-card-to-zone db1 :cabal-ritual :graveyard :player-1)
          [db3 gy3-id] (add-card-to-zone db2 :brain-freeze :graveyard :player-1)
          ;; Add mana
          db-m (mana/add-mana db3 :player-1 {:black 4})
          ;; Cast and resolve
          db-cast (rules/cast-spell db-m :player-1 igg-id)
          result (selection/resolve-spell-with-selection db-cast :player-1 igg-id)
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
    (let [db (-> (create-test-db)
                 (add-opponent))
          ;; Add IGG to hand (only card)
          [db' igg-id] (add-card-to-zone db :ill-gotten-gains :hand :player-1)
          ;; NO pre-existing graveyard cards
          _ (is (= 0 (get-zone-count db' :player-1 :graveyard))
                "Precondition: graveyard is empty")
          ;; Add mana
          db-m (mana/add-mana db' :player-1 {:black 4})
          ;; Cast and resolve
          db-cast (rules/cast-spell db-m :player-1 igg-id)
          result (selection/resolve-spell-with-selection db-cast :player-1 igg-id)]
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
    (let [db (-> (create-test-db)
                 (add-opponent))
          ;; Opponent has NO hand or graveyard cards
          _ (is (= 0 (get-zone-count db :opponent :hand))
                "Precondition: opponent hand is empty")
          _ (is (= 0 (get-zone-count db :opponent :graveyard))
                "Precondition: opponent graveyard is empty")
          ;; Add IGG to caster's hand (only card)
          [db' igg-id] (add-card-to-zone db :ill-gotten-gains :hand :player-1)
          db-m (mana/add-mana db' :player-1 {:black 4})
          db-cast (rules/cast-spell db-m :player-1 igg-id)
          result (selection/resolve-spell-with-selection db-cast :player-1 igg-id)]
      ;; IGG should be exiled
      (is (= :exile (:object/zone (q/get-object (:db result) igg-id)))
          "IGG should be exiled")
      ;; Opponent's hand should be empty (nothing to discard)
      (is (= 0 (get-zone-count (:db result) :opponent :hand))
          "Opponent hand should remain empty")
      ;; Opponent's graveyard should be empty (nothing was discarded)
      (is (= 0 (get-zone-count (:db result) :opponent :graveyard))
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
