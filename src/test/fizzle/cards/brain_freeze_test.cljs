(ns fizzle.cards.brain-freeze-test
  "Tests for Brain Freeze - {1}{U} instant with storm, mills opponent 3.
   Covers the full storm pipeline: cast -> storm trigger -> copy creation ->
   copy resolution -> copies cease to exist."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.db.schema :refer [schema]]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.triggers :as triggers]
    [fizzle.events.game :as game]))


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


(defn add-cards-to-library
  "Add N cards to a player's library with sequential positions.
   Position 0 = top of library. Uses :dark-ritual card entity for all.
   Returns updated db."
  [db player-id n]
  (let [conn (d/conn-from-db db)
        player-eid (q/get-player-eid db player-id)
        card-eid (d/q '[:find ?e .
                        :where [?e :card/id :dark-ritual]]
                      db)]
    (doseq [idx (range n)]
      (d/transact! conn [{:object/id (random-uuid)
                          :object/card card-eid
                          :object/zone :library
                          :object/owner player-eid
                          :object/controller player-eid
                          :object/position idx
                          :object/tapped false}]))
    @conn))


(defn get-zone-count
  "Count objects in a zone for a player."
  [db player-id zone]
  (count (or (q/get-objects-in-zone db player-id zone) [])))


;; === Card definition test ===

(deftest brain-freeze-card-definition-test
  (testing "Brain Freeze card data is correct"
    (let [card cards/brain-freeze]
      (is (= :brain-freeze (:card/id card)))
      (is (= "Brain Freeze" (:card/name card)))
      (is (= 2 (:card/cmc card)))
      (is (= {:colorless 1 :blue 1} (:card/mana-cost card)))
      (is (= #{:instant} (:card/types card)))
      (is (= #{:blue} (:card/colors card)))
      (is (= #{:storm} (:card/keywords card))
          "Brain Freeze must have :storm keyword")
      (is (= 1 (count (:card/effects card))))
      (let [effect (first (:card/effects card))]
        (is (= :mill (:effect/type effect)))
        (is (= 3 (:effect/amount effect)))
        (is (= :opponent (:effect/target effect)))))))


;; === Cast-resolve integration tests ===

(deftest brain-freeze-mills-opponent-3-cards-test
  (testing "Brain Freeze mills 3 cards from opponent's library"
    (let [db (-> (create-test-db)
                 (add-opponent))
          db' (add-cards-to-library db :opponent 5)
          [db'' obj-id] (add-card-to-zone db' :brain-freeze :hand :player-1)
          db-with-mana (mana/add-mana db'' :player-1 {:blue 1 :colorless 1})
          _ (is (= 5 (get-zone-count db-with-mana :opponent :library))
                "Precondition: opponent has 5 library cards")
          db-cast (rules/cast-spell db-with-mana :player-1 obj-id)
          db-resolved (rules/resolve-spell db-cast :player-1 obj-id)]
      (is (= 2 (get-zone-count db-resolved :opponent :library))
          "Opponent should have 2 library cards remaining (milled 3)")
      (is (= 3 (get-zone-count db-resolved :opponent :graveyard))
          "Opponent should have 3 cards in graveyard")
      (is (= :graveyard (:object/zone (q/get-object db-resolved obj-id)))
          "Brain Freeze should be in player's graveyard after resolution")
      (is (= 1 (q/get-storm-count db-resolved :player-1))
          "Storm count should be 1"))))


(deftest brain-freeze-cannot-cast-without-mana-test
  (testing "Brain Freeze cannot be cast without {1}{U} mana"
    (let [db (create-test-db)
          [db' obj-id] (add-card-to-zone db :brain-freeze :hand :player-1)]
      (is (false? (rules/can-cast? db' :player-1 obj-id))
          "Should not be able to cast without mana"))))


(deftest brain-freeze-creates-storm-trigger-test
  (testing "Brain Freeze creates storm trigger with correct copy count"
    (let [db (create-test-db)
          ;; Cast 2 Dark Rituals first to build storm count
          [db' dr1-id] (add-card-to-zone db :dark-ritual :hand :player-1)
          db-m1 (mana/add-mana db' :player-1 {:black 1})
          db-cast1 (rules/cast-spell db-m1 :player-1 dr1-id)
          db-res1 (rules/resolve-spell db-cast1 :player-1 dr1-id)
          [db-r1 dr2-id] (add-card-to-zone db-res1 :dark-ritual :hand :player-1)
          db-cast2 (rules/cast-spell db-r1 :player-1 dr2-id)
          db-res2 (rules/resolve-spell db-cast2 :player-1 dr2-id)
          _ (is (= 2 (q/get-storm-count db-res2 :player-1))
                "Precondition: storm count is 2 after 2 rituals")
          ;; Cast Brain Freeze as 3rd spell
          [db-r2 bf-id] (add-card-to-zone db-res2 :brain-freeze :hand :player-1)
          db-bf-mana (mana/add-mana db-r2 :player-1 {:blue 1 :colorless 1})
          db-bf-cast (rules/cast-spell db-bf-mana :player-1 bf-id)
          ;; Storm count should now be 3
          _ (is (= 3 (q/get-storm-count db-bf-cast :player-1))
                "Storm count should be 3 after casting Brain Freeze")
          ;; Storm trigger should be on the stack
          all-items (stack/get-all-stack-items db-bf-cast)
          storm-items (filter #(= :storm (:stack-item/type %)) all-items)]
      (is (= 1 (count storm-items))
          "One storm trigger should be on the stack")
      (let [storm-item (first storm-items)]
        (is (= :storm (:stack-item/type storm-item))
            "Trigger should be a storm trigger")
        (is (= 2 (get-in storm-item [:stack-item/effects 0 :effect/count]))
            "Storm trigger should have count=2 (spells cast before Brain Freeze)")))))


(deftest brain-freeze-storm-copies-mill-test
  (testing "Full storm pipeline: cast with storm count 2 -> trigger -> 2 copies -> mill 9 total"
    (let [db (-> (create-test-db)
                 (add-opponent))
          ;; Add 15 cards to opponent library for milling
          db' (add-cards-to-library db :opponent 15)
          ;; Cast 2 Dark Rituals first
          [db1 dr1-id] (add-card-to-zone db' :dark-ritual :hand :player-1)
          db1m (mana/add-mana db1 :player-1 {:black 1})
          db1c (rules/cast-spell db1m :player-1 dr1-id)
          db1r (rules/resolve-spell db1c :player-1 dr1-id)
          [db2 dr2-id] (add-card-to-zone db1r :dark-ritual :hand :player-1)
          db2c (rules/cast-spell db2 :player-1 dr2-id)
          db2r (rules/resolve-spell db2c :player-1 dr2-id)
          ;; Cast Brain Freeze (storm count becomes 3)
          [db3 bf-id] (add-card-to-zone db2r :brain-freeze :hand :player-1)
          db3m (mana/add-mana db3 :player-1 {:blue 1 :colorless 1})
          db3c (rules/cast-spell db3m :player-1 bf-id)
          ;; Resolve storm stack-item - creates 2 copies on stack
          db4 (game/resolve-top-of-stack db3c :player-1)
          ;; Find the copies on the stack (is-copy = true)
          stack-objects (q/get-objects-in-zone db4 :player-1 :stack)
          copies (filter :object/is-copy stack-objects)
          copy-ids (mapv :object/id copies)]
      (is (= 2 (count copies))
          "Should have 2 storm copies on stack")
      ;; Resolve copies first (LIFO - they're on top), then original
      (let [db5 (reduce (fn [d cid] (rules/resolve-spell d :player-1 cid))
                        db4
                        copy-ids)
            db6 (rules/resolve-spell db5 :player-1 bf-id)]
        ;; 3 instances * 3 cards each = 9 total milled
        (is (= 6 (get-zone-count db6 :opponent :library))
            "Opponent should have 6 library cards remaining (15 - 9 milled)")
        (is (= 9 (get-zone-count db6 :opponent :graveyard))
            "Opponent should have 9 cards in graveyard")
        ;; Copies should be completely removed from db
        (doseq [cid copy-ids]
          (is (nil? (q/get-object db6 cid))
              "Storm copy should cease to exist (removed from db)"))
        ;; Original should be in graveyard
        (is (= :graveyard (:object/zone (q/get-object db6 bf-id)))
            "Original Brain Freeze should be in graveyard")))))


(deftest brain-freeze-storm-zero-previous-spells-test
  (testing "Brain Freeze as first spell creates storm trigger with count=0"
    (let [db (-> (create-test-db)
                 (add-opponent))
          db' (add-cards-to-library db :opponent 5)
          [db'' bf-id] (add-card-to-zone db' :brain-freeze :hand :player-1)
          db-m (mana/add-mana db'' :player-1 {:blue 1 :colorless 1})
          db-cast (rules/cast-spell db-m :player-1 bf-id)
          ;; Storm count = 1, so trigger count = dec(1) = 0
          _ (is (= 1 (q/get-storm-count db-cast :player-1))
                "Precondition: storm count is 1")
          storm-trigger (first (filter #(= :storm (:stack-item/type %))
                                       (stack/get-all-stack-items db-cast)))]
      (is (= 0 (get-in storm-trigger [:stack-item/effects 0 :effect/count]))
          "Storm trigger count should be 0 (no previous spells)")
      ;; Resolve storm stack-item (creates 0 copies) then resolve original
      (let [db-after-trigger (game/resolve-top-of-stack db-cast :player-1)
            stack-objects (q/get-objects-in-zone db-after-trigger :player-1 :stack)
            copies (filter :object/is-copy stack-objects)]
        (is (= 0 (count copies))
            "No copies should be created with count=0")
        (let [db-resolved (rules/resolve-spell db-after-trigger :player-1 bf-id)]
          (is (= 2 (get-zone-count db-resolved :opponent :library))
              "Only 3 cards milled (original only, no copies)")
          (is (= 3 (get-zone-count db-resolved :opponent :graveyard))
              "3 cards in opponent graveyard"))))))


;; === Storm copy regression tests (commit 46a281f) ===

(deftest storm-copy-ceases-to-exist-on-resolve-test
  (testing "Storm copy is removed from db when resolved (not moved to graveyard)"
    (let [db (create-test-db)
          [db' source-id] (add-card-to-zone db :brain-freeze :stack :player-1)
          ;; Create a copy of the spell
          db-with-copy (triggers/create-spell-copy db' source-id :player-1)
          ;; Find the copy
          stack-objects (q/get-objects-in-zone db-with-copy :player-1 :stack)
          copy (first (filter :object/is-copy stack-objects))
          copy-id (:object/id copy)]
      (is (true? (:object/is-copy copy)) "Copy should have :object/is-copy true")
      ;; Resolve the copy
      (let [db-resolved (rules/resolve-spell db-with-copy :player-1 copy-id)]
        (is (nil? (q/get-object db-resolved copy-id))
            "Copy should be completely removed from db (cease to exist)")
        ;; Verify it's NOT in graveyard
        (let [gy-objects (q/get-objects-in-zone db-resolved :player-1 :graveyard)
              gy-ids (set (map :object/id gy-objects))]
          (is (not (contains? gy-ids copy-id))
              "Copy should NOT be in graveyard"))))))


(deftest storm-copy-ceases-to-exist-when-countered-test
  (testing "Storm copy is removed from db when moved off stack (countered/fizzled)"
    (let [db (create-test-db)
          [db' source-id] (add-card-to-zone db :dark-ritual :stack :player-1)
          db-with-copy (triggers/create-spell-copy db' source-id :player-1)
          stack-objects (q/get-objects-in-zone db-with-copy :player-1 :stack)
          copy (first (filter :object/is-copy stack-objects))
          copy-id (:object/id copy)]
      (is (some? copy) "Precondition: copy exists")
      (let [db-countered (rules/move-spell-off-stack db-with-copy :player-1 copy-id)]
        (is (nil? (q/get-object db-countered copy-id))
            "Copy should cease to exist when countered/moved off stack")))))


(deftest storm-copies-dont-trigger-storm-test
  (testing "Storm copies (is-copy=true) do not create additional storm triggers"
    (let [db (create-test-db)
          [db' source-id] (add-card-to-zone db :brain-freeze :stack :player-1)
          ;; Create a copy
          db-with-copy (triggers/create-spell-copy db' source-id :player-1)
          stack-objects (q/get-objects-in-zone db-with-copy :player-1 :stack)
          copy (first (filter :object/is-copy stack-objects))
          ;; Verify the copy has storm keyword (inherited from source card)
          copy-card (:object/card copy)
          _ (is (some #{:storm} (:card/keywords copy-card))
                "Precondition: copy's card has :storm keyword")
          ;; The storm trigger creation check should skip copies
          ;; Use maybe-create-storm-trigger indirectly via cast-spell-mode?
          ;; Actually, copies are created by triggers, not cast-spell-mode,
          ;; so the protection is in maybe-create-storm-trigger checking is-copy.
          ;; Verify by checking no storm triggers appeared after copy creation.
          triggers-before (filter #(= :storm (:stack-item/type %))
                                  (stack/get-all-stack-items db-with-copy))
          ;; Cast-spell on the copy would increment storm and check for storm trigger
          ;; but copies shouldn't be cast in normal game flow. The real protection
          ;; is that create-spell-copy puts them on stack directly without calling cast.
          ;; So verify: no storm triggers exist after copy creation.
          ]
      (is (empty? triggers-before)
          "No storm triggers should be created from copy creation"))))


;; === Mill effect edge cases ===

(deftest mill-with-fewer-cards-than-amount-test
  (testing "Mill gracefully handles library with fewer cards than mill amount"
    (let [db (-> (create-test-db)
                 (add-opponent))
          ;; Add only 1 card to opponent library
          db' (add-cards-to-library db :opponent 1)
          [db'' bf-id] (add-card-to-zone db' :brain-freeze :hand :player-1)
          db-m (mana/add-mana db'' :player-1 {:blue 1 :colorless 1})
          db-cast (rules/cast-spell db-m :player-1 bf-id)
          db-resolved (rules/resolve-spell db-cast :player-1 bf-id)]
      (is (= 0 (get-zone-count db-resolved :opponent :library))
          "All available cards should be milled")
      (is (= 1 (get-zone-count db-resolved :opponent :graveyard))
          "Only 1 card milled (all that was available)"))))


(deftest brain-freeze-mills-opponent-empty-library-test
  (testing "Milling opponent with empty library is no-op, no loss condition"
    (let [db (-> (create-test-db)
                 (add-opponent))
          ;; Opponent has 0 library cards
          _ (is (= 0 (get-zone-count db :opponent :library))
                "Precondition: opponent library is empty")
          [db' bf-id] (add-card-to-zone db :brain-freeze :hand :player-1)
          db-m (mana/add-mana db' :player-1 {:blue 1 :colorless 1})
          db-cast (rules/cast-spell db-m :player-1 bf-id)
          db-resolved (rules/resolve-spell db-cast :player-1 bf-id)]
      (is (= 0 (get-zone-count db-resolved :opponent :library))
          "Opponent library should still be empty")
      (is (= 0 (get-zone-count db-resolved :opponent :graveyard))
          "Nothing should be milled to graveyard")
      (is (nil? (:game/loss-condition (q/get-game-state db-resolved)))
          "Mill should not set loss condition (only draw does)"))))
