(ns fizzle.cards.brain-freeze-test
  "Tests for Brain Freeze - {1}{U} instant with storm, mills target player 3.
   Covers the full storm pipeline: cast -> storm trigger -> copy creation ->
   copy resolution -> copies cease to exist.

   Brain Freeze is a targeted spell: player chooses opponent or self at cast
   time. Storm copies inherit the chosen target."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.iggy-pop :as cards]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.resolution :as engine-resolution]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.triggers :as triggers]
    [fizzle.events.game :as game]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.events.selection.targeting :as sel-targeting]
    [fizzle.test-helpers :as th]))


;; === File-specific helpers ===

(defn cast-brain-freeze-with-target
  "Cast Brain Freeze through the targeting flow, selecting the given target.
   Pays mana, moves to stack, stores target on stack-item.
   Returns updated db."
  [db player-id object-id target-player-id]
  (let [card cards/brain-freeze
        target-req (first (:card/targeting card))
        modes (rules/get-casting-modes db player-id object-id)
        mode (first (filter #(= :primary (:mode/id %)) modes))
        selection {:selection/type :cast-time-targeting
                   :selection/player-id player-id
                   :selection/object-id object-id
                   :selection/mode mode
                   :selection/target-requirement target-req
                   :selection/selected #{target-player-id}}]
    (sel-targeting/confirm-cast-time-target db selection)))


(defn resolve-brain-freeze
  "Resolve Brain Freeze through the engine resolution multimethod.
   Returns updated db with spell moved to graveyard/removed."
  [db player-id object-id]
  (let [obj-eid (d/q '[:find ?e . :in $ ?oid
                       :where [?e :object/id ?oid]]
                     db object-id)
        stack-item (stack/get-stack-item-by-object-ref db obj-eid)
        result (engine-resolution/resolve-stack-item db player-id stack-item)
        stack-item-eid (:db/id stack-item)]
    (stack/remove-stack-item (:db result) stack-item-eid)))


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
        (is (= :any-player (:effect/target effect))
            "Effect target should be :any-player (resolved from stack-item at resolution)"))))

  (testing "Brain Freeze has cast-time player targeting"
    (let [targeting (:card/targeting cards/brain-freeze)]
      (is (vector? targeting)
          "Targeting must be a vector")
      (is (= 1 (count targeting))
          "Should have exactly one target requirement")
      (let [req (first targeting)]
        (is (= :player (:target/id req)))
        (is (= :player (:target/type req)))
        (is (contains? (:target/options req) :any-player))
        (is (:target/required req))))))


;; === Cast-time targeting tests ===

(deftest brain-freeze-cast-triggers-target-selection-test
  (testing "Casting Brain Freeze triggers cast-time targeting selection"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db' obj-id] (th/add-card-to-zone db :brain-freeze :hand :player-1)
          db-with-mana (mana/add-mana db' :player-1 {:blue 1 :colorless 1})
          result (sel-targeting/cast-spell-with-targeting db-with-mana :player-1 obj-id)]
      (is (some? (:pending-target-selection result))
          "Should return pending target selection")
      (let [sel (:pending-target-selection result)]
        (is (= :cast-time-targeting (:selection/type sel))
            "Selection type should be :cast-time-targeting")
        (is (= :player-1 (:selection/player-id sel)))
        (is (= obj-id (:selection/object-id sel)))
        (is (= #{} (:selection/selected sel))
            "No target selected yet")))))


(deftest brain-freeze-targeting-self-mills-self-test
  (testing "Brain Freeze targeting self mills self's library"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db' _] (th/add-cards-to-library db (vec (repeat 5 :dark-ritual)) :player-1)
          [db'' obj-id] (th/add-card-to-zone db' :brain-freeze :hand :player-1)
          db-with-mana (mana/add-mana db'' :player-1 {:blue 1 :colorless 1})
          _ (is (= 5 (th/get-zone-count db-with-mana :library :player-1))
                "Precondition: player has 5 library cards")
          _ (is (= 0 (th/get-zone-count db-with-mana :graveyard :player-1))
                "Precondition: player has 0 graveyard cards")
          db-cast (cast-brain-freeze-with-target db-with-mana :player-1 obj-id :player-1)
          db-resolved (resolve-brain-freeze db-cast :player-1 obj-id)]
      (is (= 2 (th/get-zone-count db-resolved :library :player-1))
          "Player should have 2 library cards remaining (milled 3)")
      (is (>= (th/get-zone-count db-resolved :graveyard :player-1) 3)
          "Player should have at least 3 cards in graveyard (milled + Brain Freeze)")
      (is (= :graveyard (:object/zone (q/get-object db-resolved obj-id)))
          "Brain Freeze should be in graveyard after resolution"))))


;; === Cast-resolve integration tests ===

(deftest brain-freeze-mills-opponent-3-cards-test
  (testing "Brain Freeze targeting opponent mills 3 cards from opponent's library"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db' _] (th/add-cards-to-library db (vec (repeat 5 :dark-ritual)) :player-2)
          [db'' obj-id] (th/add-card-to-zone db' :brain-freeze :hand :player-1)
          db-with-mana (mana/add-mana db'' :player-1 {:blue 1 :colorless 1})
          _ (is (= 5 (th/get-zone-count db-with-mana :library :player-2))
                "Precondition: opponent has 5 library cards")
          db-cast (cast-brain-freeze-with-target db-with-mana :player-1 obj-id :player-2)
          db-resolved (resolve-brain-freeze db-cast :player-1 obj-id)]
      (is (= 2 (th/get-zone-count db-resolved :library :player-2))
          "Opponent should have 2 library cards remaining (milled 3)")
      (is (= 3 (th/get-zone-count db-resolved :graveyard :player-2))
          "Opponent should have 3 cards in graveyard")
      (is (= :graveyard (:object/zone (q/get-object db-resolved obj-id)))
          "Brain Freeze should be in player's graveyard after resolution")
      (is (= 1 (q/get-storm-count db-resolved :player-1))
          "Storm count should be 1"))))


(deftest brain-freeze-cannot-cast-without-mana-test
  (testing "Brain Freeze cannot be cast without {1}{U} mana"
    (let [db (th/create-test-db)
          [db' obj-id] (th/add-card-to-zone db :brain-freeze :hand :player-1)]
      (is (false? (rules/can-cast? db' :player-1 obj-id))
          "Should not be able to cast without mana"))))


(deftest brain-freeze-creates-storm-trigger-test
  (testing "Brain Freeze creates storm trigger with correct copy count"
    (let [db (th/create-test-db)
          ;; Cast 2 Dark Rituals first to build storm count
          [db' dr1-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db-m1 (mana/add-mana db' :player-1 {:black 1})
          db-cast1 (rules/cast-spell db-m1 :player-1 dr1-id)
          db-res1 (rules/resolve-spell db-cast1 :player-1 dr1-id)
          [db-r1 dr2-id] (th/add-card-to-zone db-res1 :dark-ritual :hand :player-1)
          db-cast2 (rules/cast-spell db-r1 :player-1 dr2-id)
          db-res2 (rules/resolve-spell db-cast2 :player-1 dr2-id)
          _ (is (= 2 (q/get-storm-count db-res2 :player-1))
                "Precondition: storm count is 2 after 2 rituals")
          ;; Cast Brain Freeze as 3rd spell (bypass targeting for storm count test)
          [db-r2 bf-id] (th/add-card-to-zone db-res2 :brain-freeze :hand :player-1)
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
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          ;; Add 15 cards to opponent library for milling
          [db' _] (th/add-cards-to-library db (vec (repeat 15 :dark-ritual)) :player-2)
          ;; Cast 2 Dark Rituals first
          [db1 dr1-id] (th/add-card-to-zone db' :dark-ritual :hand :player-1)
          db1m (mana/add-mana db1 :player-1 {:black 1})
          db1c (rules/cast-spell db1m :player-1 dr1-id)
          db1r (rules/resolve-spell db1c :player-1 dr1-id)
          [db2 dr2-id] (th/add-card-to-zone db1r :dark-ritual :hand :player-1)
          db2c (rules/cast-spell db2 :player-1 dr2-id)
          db2r (rules/resolve-spell db2c :player-1 dr2-id)
          ;; Cast Brain Freeze targeting opponent (storm count becomes 3)
          [db3 bf-id] (th/add-card-to-zone db2r :brain-freeze :hand :player-1)
          db3m (mana/add-mana db3 :player-1 {:blue 1 :colorless 1})
          db3c (cast-brain-freeze-with-target db3m :player-1 bf-id :player-2)
          ;; Resolve storm stack-item via storm-split selection
          storm-result (game/resolve-one-item db3c :player-1)
          storm-sel (:pending-selection storm-result)
          ;; Confirm storm-split (all copies to opponent)
          confirm-result (sel-core/execute-confirmed-selection (:db storm-result) storm-sel)
          db4 (:db confirm-result)
          ;; Find the copies on the stack (is-copy = true)
          stack-objects (q/get-objects-in-zone db4 :player-1 :stack)
          copies (filter :object/is-copy stack-objects)
          copy-ids (mapv :object/id copies)]
      (is (= 2 (count copies))
          "Should have 2 storm copies on stack")
      ;; Resolve copies via selection path (copies inherit target), then original
      (let [db5 (reduce (fn [d cid] (resolve-brain-freeze d :player-1 cid))
                        db4
                        copy-ids)
            db6 (resolve-brain-freeze db5 :player-1 bf-id)]
        ;; 3 instances * 3 cards each = 9 total milled
        (is (= 6 (th/get-zone-count db6 :library :player-2))
            "Opponent should have 6 library cards remaining (15 - 9 milled)")
        (is (= 9 (th/get-zone-count db6 :graveyard :player-2))
            "Opponent should have 9 cards in graveyard")
        ;; Copies should be completely removed from db
        (doseq [cid copy-ids]
          (is (nil? (q/get-object db6 cid))
              "Storm copy should cease to exist (removed from db)"))
        ;; Original should be in graveyard
        (is (= :graveyard (:object/zone (q/get-object db6 bf-id)))
            "Original Brain Freeze should be in graveyard")))))


(deftest brain-freeze-storm-copies-inherit-target-test
  (testing "Storm copies targeting self all mill self"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          ;; Add 15 cards to player's library for self-milling
          [db' _] (th/add-cards-to-library db (vec (repeat 15 :dark-ritual)) :player-1)
          ;; Cast Dark Ritual first to build storm count
          [db1 dr-id] (th/add-card-to-zone db' :dark-ritual :hand :player-1)
          db1m (mana/add-mana db1 :player-1 {:black 1})
          db1c (rules/cast-spell db1m :player-1 dr-id)
          db1r (rules/resolve-spell db1c :player-1 dr-id)
          ;; Cast Brain Freeze targeting self (storm count becomes 2)
          [db2 bf-id] (th/add-card-to-zone db1r :brain-freeze :hand :player-1)
          db2m (mana/add-mana db2 :player-1 {:blue 1 :colorless 1})
          db2c (cast-brain-freeze-with-target db2m :player-1 bf-id :player-1)
          ;; Resolve storm stack-item via storm-split selection
          storm-result (game/resolve-one-item db2c :player-1)
          storm-sel (:pending-selection storm-result)
          ;; Confirm storm-split (all copies to self)
          storm-sel (assoc storm-sel :selection/allocation
                           {:player-1 1 :player-2 0})
          confirm-result (sel-core/execute-confirmed-selection (:db storm-result) storm-sel)
          db3 (:db confirm-result)
          stack-objects (q/get-objects-in-zone db3 :player-1 :stack)
          copies (filter :object/is-copy stack-objects)
          copy-ids (mapv :object/id copies)]
      (is (= 1 (count copies))
          "Should have 1 storm copy on stack")
      ;; Resolve copy then original - both should mill self
      (let [db4 (reduce (fn [d cid] (resolve-brain-freeze d :player-1 cid))
                        db3
                        copy-ids)
            db5 (resolve-brain-freeze db4 :player-1 bf-id)]
        ;; 2 instances * 3 cards each = 6 total milled from self
        (is (= 9 (th/get-zone-count db5 :library :player-1))
            "Player should have 9 library cards remaining (15 - 6 milled)")
        (is (= 0 (th/get-zone-count db5 :library :player-2))
            "Opponent library should be untouched (0 cards)")
        (is (= 0 (th/get-zone-count db5 :graveyard :player-2))
            "Opponent graveyard should be empty")))))


(deftest brain-freeze-storm-zero-previous-spells-test
  (testing "Brain Freeze as first spell creates storm trigger with count=0"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          [db' _] (th/add-cards-to-library db (vec (repeat 5 :dark-ritual)) :player-2)
          [db'' bf-id] (th/add-card-to-zone db' :brain-freeze :hand :player-1)
          db-m (mana/add-mana db'' :player-1 {:blue 1 :colorless 1})
          db-cast (cast-brain-freeze-with-target db-m :player-1 bf-id :player-2)
          ;; Storm count = 1, so trigger count = dec(1) = 0
          _ (is (= 1 (q/get-storm-count db-cast :player-1))
                "Precondition: storm count is 1")
          storm-trigger (first (filter #(= :storm (:stack-item/type %))
                                       (stack/get-all-stack-items db-cast)))]
      (is (= 0 (get-in storm-trigger [:stack-item/effects 0 :effect/count]))
          "Storm trigger count should be 0 (no previous spells)")
      ;; Resolve storm stack-item (creates 0 copies) then resolve original
      (let [db-after-trigger (:db (game/resolve-one-item db-cast :player-1))
            stack-objects (q/get-objects-in-zone db-after-trigger :player-1 :stack)
            copies (filter :object/is-copy stack-objects)]
        (is (= 0 (count copies))
            "No copies should be created with count=0")
        (let [db-resolved (resolve-brain-freeze db-after-trigger :player-1 bf-id)]
          (is (= 2 (th/get-zone-count db-resolved :library :player-2))
              "Only 3 cards milled (original only, no copies)")
          (is (= 3 (th/get-zone-count db-resolved :graveyard :player-2))
              "3 cards in opponent graveyard"))))))


;; === Storm copy regression tests (commit 46a281f) ===

(deftest storm-copy-ceases-to-exist-on-resolve-test
  (testing "Storm copy is removed from db when resolved (not moved to graveyard)"
    (let [db (th/create-test-db)
          [db' source-id] (th/add-card-to-zone db :brain-freeze :stack :player-1)
          ;; Create a copy of the spell
          db-with-copy (triggers/create-spell-copy db' source-id :player-1)
          ;; Find the copy
          stack-objects (q/get-objects-in-zone db-with-copy :player-1 :stack)
          copy (first (filter :object/is-copy stack-objects))
          copy-id (:object/id copy)]
      (is (true? (:object/is-copy copy)) "Copy should have :object/is-copy true")
      ;; Resolve the copy - use rules/resolve-spell (copy lifecycle test, not targeting test)
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
    (let [db (th/create-test-db)
          [db' source-id] (th/add-card-to-zone db :dark-ritual :stack :player-1)
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
    (let [db (th/create-test-db)
          [db' source-id] (th/add-card-to-zone db :brain-freeze :stack :player-1)
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
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          ;; Add only 1 card to opponent library
          [db' _] (th/add-cards-to-library db [:dark-ritual] :player-2)
          [db'' bf-id] (th/add-card-to-zone db' :brain-freeze :hand :player-1)
          db-m (mana/add-mana db'' :player-1 {:blue 1 :colorless 1})
          db-cast (cast-brain-freeze-with-target db-m :player-1 bf-id :player-2)
          db-resolved (resolve-brain-freeze db-cast :player-1 bf-id)]
      (is (= 0 (th/get-zone-count db-resolved :library :player-2))
          "All available cards should be milled")
      (is (= 1 (th/get-zone-count db-resolved :graveyard :player-2))
          "Only 1 card milled (all that was available)"))))


(deftest brain-freeze-mills-opponent-empty-library-test
  (testing "Milling opponent with empty library is no-op, no loss condition"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent))
          ;; Opponent has 0 library cards
          _ (is (= 0 (th/get-zone-count db :library :player-2))
                "Precondition: opponent library is empty")
          [db' bf-id] (th/add-card-to-zone db :brain-freeze :hand :player-1)
          db-m (mana/add-mana db' :player-1 {:blue 1 :colorless 1})
          db-cast (cast-brain-freeze-with-target db-m :player-1 bf-id :player-2)
          db-resolved (resolve-brain-freeze db-cast :player-1 bf-id)]
      (is (= 0 (th/get-zone-count db-resolved :library :player-2))
          "Opponent library should still be empty")
      (is (= 0 (th/get-zone-count db-resolved :graveyard :player-2))
          "Nothing should be milled to graveyard")
      (is (nil? (:game/loss-condition (q/get-game-state db-resolved)))
          "Mill should not set loss condition (only draw does)"))))
