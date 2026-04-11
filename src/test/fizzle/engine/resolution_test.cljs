(ns fizzle.engine.resolution-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.resolution :as engine-resolution]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.events.resolution :as resolution]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Direct multimethod tests
;; =====================================================

(deftest test-spell-no-targets-resolves-and-moves-to-graveyard
  (testing "Spell with no targeting resolves effects and moves to graveyard"
    (let [db (th/create-test-db {:mana {:black 3}})
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (rules/cast-spell db :player-1 obj-id)
          ;; Find the spell stack-item (not storm)
          spell-items (filter #(:stack-item/object-ref %) (queries/get-all-stack-items db))
          stack-item (first spell-items)
          ;; Resolve via multimethod
          result (engine-resolution/resolve-stack-item db stack-item)]
      ;; Should complete without needing selection
      (is (nil? (:needs-selection result))
          "Dark Ritual should not need selection")
      ;; Spell should be in graveyard
      (is (= :graveyard (:object/zone (queries/get-object (:db result) obj-id)))
          "Dark Ritual should be in graveyard after resolution")
      ;; Effect should have executed (started with 3, paid 1 to cast, gained 3 = 5)
      (let [pool (queries/get-mana-pool (:db result) :player-1)]
        (is (= 5 (:black pool))
            "Dark Ritual should have added 3 black mana (3 start - 1 cost + 3 effect = 5)")))))


(deftest test-spell-with-targets-uses-stack-item-targets
  (testing "Targeted spell reads targets from :stack-item/targets, not :object/targets"
    (let [db (-> (th/create-test-db {:mana {:white 1}})
                 th/add-opponent)
          [db obj-id] (th/add-card-to-zone db :orims-chant :hand :player-1)
          db (rules/cast-spell db :player-1 obj-id)
          ;; Find the spell stack-item and add targets
          obj-eid (d/q '[:find ?e . :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db obj-id)
          stack-item-raw (stack/get-stack-item-by-object-ref db obj-eid)
          si-eid (:db/id stack-item-raw)
          db (d/db-with db [[:db/add si-eid :stack-item/targets {:player :player-2}]])
          ;; Re-fetch stack-item with targets
          stack-item (stack/get-stack-item-by-object-ref db obj-eid)
          result (engine-resolution/resolve-stack-item db stack-item)]
      ;; Should complete without selection
      (is (nil? (:needs-selection result))
          "Targeted Orim's Chant should not need selection when targets stored")
      ;; Player-2 should have restriction
      (let [p2-grants (grants/get-player-grants (:db result) :player-2)]
        (is (= 1 (count p2-grants))
            "Player-2 should have exactly one grant after resolution")
        (is (some #(= :cannot-cast-spells (:restriction/type (:grant/data %))) p2-grants)
            "Player-2 should have cannot-cast-spells restriction")))))


(deftest test-spell-interactive-effect-returns-needs-selection
  (testing "Spell with tutor effect returns :needs-selection"
    (let [db (th/create-test-db {:mana {:blue 3}})
          [db obj-id] (th/add-card-to-zone db :merchant-scroll :hand :player-1)
          db (rules/cast-spell db :player-1 obj-id)
          ;; Find the spell stack-item
          spell-items (filter #(:stack-item/object-ref %) (queries/get-all-stack-items db))
          stack-item (first spell-items)
          result (engine-resolution/resolve-stack-item db stack-item)]
      ;; Should pause for selection
      (is (some? (:needs-selection result))
          "Tutor spell should need selection")
      ;; Spell should still be on stack (not moved yet)
      (is (= :stack (:object/zone (queries/get-object (:db result) obj-id)))
          "Spell should remain on stack while selection is pending"))))


(deftest test-storm-copy-resolves-and-ceases-to-exist
  (testing "Storm copy resolves and is removed from db (copies cease to exist)"
    (let [db (th/create-test-db {:mana {:black 3}})
          ;; Create a source spell for the copy
          [db src-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Create a copy object on the stack
          player-eid (queries/get-player-eid db :player-1)
          card-eid-ref (:db/id (:object/card (queries/get-object db src-id)))
          copy-id (random-uuid)
          conn (d/conn-from-db db)
          _ (d/transact! conn [{:object/id copy-id
                                :object/card card-eid-ref
                                :object/zone :stack
                                :object/owner player-eid
                                :object/controller player-eid
                                :object/tapped false
                                :object/is-copy true}])
          db @conn
          ;; Create storm-copy stack-item
          copy-eid (d/q '[:find ?e . :in $ ?oid
                          :where [?e :object/id ?oid]]
                        db copy-id)
          db (stack/create-stack-item db
                                      {:stack-item/type :storm-copy
                                       :stack-item/controller :player-1
                                       :stack-item/source src-id
                                       :stack-item/object-ref copy-eid
                                       :stack-item/is-copy true})
          stack-item (stack/get-top-stack-item db)
          result (engine-resolution/resolve-stack-item db stack-item)]
      ;; Copy should be removed from db
      (is (nil? (queries/get-object (:db result) copy-id))
          "Storm copy should cease to exist after resolution"))))


(deftest test-spell-flashback-exiles-after-resolution
  (testing "Flashback spell moves to exile after resolution"
    (let [db (th/create-test-db {:mana {:black 3}})
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (rules/cast-spell db :player-1 obj-id)
          ;; Set cast-mode with :exile on-resolve (simulating flashback)
          obj-eid (d/q '[:find ?e . :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db obj-id)
          db (d/db-with db [[:db/add obj-eid :object/cast-mode
                             {:mode/id :flashback :mode/on-resolve :exile}]])
          ;; Find the spell stack-item
          spell-items (filter #(:stack-item/object-ref %) (queries/get-all-stack-items db))
          stack-item (first spell-items)
          result (engine-resolution/resolve-stack-item db stack-item)]
      ;; Spell should be in exile
      (is (= :exile (:object/zone (queries/get-object (:db result) obj-id)))
          "Flashback spell should exile after resolution"))))


(deftest test-spell-empty-effects-still-moves-to-graveyard
  (testing "Spell with no matching effects still moves to graveyard"
    (let [db (th/create-test-db {:mana {:white 1}})
          ;; Use a card that has conditional effects that won't match (no threshold)
          [db obj-id] (th/add-card-to-zone db :cabal-ritual :hand :player-1)
          db (mana/add-mana db :player-1 {:black 2})
          db (rules/cast-spell db :player-1 obj-id)
          ;; Find the spell stack-item
          spell-items (filter #(:stack-item/object-ref %) (queries/get-all-stack-items db))
          stack-item (first spell-items)
          result (engine-resolution/resolve-stack-item db stack-item)]
      ;; Spell should still be in graveyard
      (is (= :graveyard (:object/zone (queries/get-object (:db result) obj-id)))
          "Spell should move to graveyard even if conditional effects don't match"))))


(deftest test-spell-not-on-stack-is-noop
  (testing "Spell not on stack returns {:db db} unchanged"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Create a fake stack-item pointing to an object in hand (not on stack)
          obj-eid (d/q '[:find ?e . :in $ ?oid
                         :where [?e :object/id ?oid]]
                       db obj-id)
          db (stack/create-stack-item db
                                      {:stack-item/type :spell
                                       :stack-item/controller :player-1
                                       :stack-item/source obj-id
                                       :stack-item/object-ref obj-eid})
          stack-item (stack/get-top-stack-item db)
          result (engine-resolution/resolve-stack-item db stack-item)]
      ;; Object should still be in hand (no-op)
      (is (= :hand (:object/zone (queries/get-object (:db result) obj-id)))
          "Object in hand should not be affected"))))


;; =====================================================
;; Integration: resolve-one-item uses multimethod
;; =====================================================

(deftest test-resolve-one-item-spell-uses-multimethod
  (testing "resolve-one-item delegates spell to engine/resolution multimethod"
    (let [db (th/create-test-db {:mana {:black 3}})
          db (th/add-opponent db)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (rules/cast-spell db :player-1 obj-id)
          ;; Resolve all stack items (storm + spell)
          result-loop (loop [d db
                             iterations 0]
                        (if (or (nil? (stack/get-top-stack-item d))
                                (> iterations 5))
                          d
                          (let [result (resolution/resolve-one-item d)]
                            (recur (:db result) (inc iterations)))))]
      ;; Spell should be in graveyard
      (is (= :graveyard (:object/zone (queries/get-object result-loop obj-id)))
          "Dark Ritual should be in graveyard after resolution")
      ;; Stack should be empty
      (is (nil? (stack/get-top-stack-item result-loop))
          "Stack should be empty"))))


(deftest test-spell-uses-controller-not-active-player
  (testing "Spell cast by player-1 on opponent's turn resolves for player-1"
    (let [db (-> (th/create-test-db {:mana {:black 3}})
                 th/add-opponent)
          ;; Make opponent the active player (it's their turn)
          opp-eid (queries/get-player-eid db :player-2)
          game-eid (d/q '[:find ?e . :where [?e :game/id _]] db)
          db (d/db-with db [[:db/add game-eid :game/active-player opp-eid]])
          ;; Player-1 casts Dark Ritual on opponent's turn
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db (rules/cast-spell db :player-1 obj-id)
          ;; Find the spell stack-item
          spell-items (filter #(:stack-item/object-ref %) (queries/get-all-stack-items db))
          stack-item (first spell-items)
          ;; Resolve — multimethod reads controller from stack-item, not a parameter
          result (engine-resolution/resolve-stack-item db stack-item)]
      ;; Mana should go to player-1 (the controller), NOT player-2 (active player)
      (let [p1-pool (queries/get-mana-pool (:db result) :player-1)]
        (is (= 5 (:black p1-pool))
            "Dark Ritual mana should go to controller (player-1), not active player"))
      (let [p2-pool (queries/get-mana-pool (:db result) :player-2)]
        (is (= 0 (:black p2-pool))
            "Opponent should NOT receive Dark Ritual mana")))))


(deftest test-default-defmethod-returns-db-unchanged
  (testing ":default defmethod returns {:db db} for unknown types"
    (let [db (th/create-test-db)
          stack-item {:stack-item/type :unknown-type
                      :stack-item/controller :player-1}
          result (engine-resolution/resolve-stack-item db stack-item)]
      (is (= db (:db result))
          "Default defmethod should return db unchanged"))))


;; =====================================================
;; move-resolved-spell zone guard (idempotency)
;; =====================================================

(deftest test-move-resolved-spell-spell-in-exile-is-noop
  (testing "move-resolved-spell returns db unchanged when spell is already in :exile"
    (let [db (th/create-test-db {:mana {:black 3}})
          [db obj-id] (th/add-card-to-zone db :dark-ritual :exile :player-1)
          obj (queries/get-object db obj-id)
          result-db (engine-resolution/move-resolved-spell db obj-id obj)]
      ;; db should be identical — no zone change
      (is (= db result-db)
          "move-resolved-spell should return db unchanged when spell is in :exile")
      ;; Object should still be in :exile
      (is (= :exile (:object/zone (queries/get-object result-db obj-id)))
          "Spell should remain in :exile"))))


(deftest test-move-resolved-spell-spell-in-graveyard-is-noop
  (testing "move-resolved-spell returns db unchanged when spell is already in :graveyard"
    (let [db (th/create-test-db {:mana {:black 3}})
          [db obj-id] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          obj (queries/get-object db obj-id)
          result-db (engine-resolution/move-resolved-spell db obj-id obj)]
      ;; db should be identical — no zone change
      (is (= db result-db)
          "move-resolved-spell should return db unchanged when spell is in :graveyard")
      ;; Object should still be in :graveyard
      (is (= :graveyard (:object/zone (queries/get-object result-db obj-id)))
          "Spell should remain in :graveyard"))))


(deftest test-move-resolved-spell-copy-in-exile-is-noop
  (testing "move-resolved-spell returns db unchanged when a copy is already in :exile"
    (let [db (th/create-test-db {:mana {:black 3}})
          [db src-id] (th/add-card-to-zone db :dark-ritual :exile :player-1)
          ;; Mark the object as a copy
          obj-eid (queries/get-object-eid db src-id)
          db (d/db-with db [[:db/add obj-eid :object/is-copy true]])
          obj (queries/get-object db src-id)
          result-db (engine-resolution/move-resolved-spell db src-id obj)]
      ;; Copy already off stack — should be a no-op (copy removal only happens from :stack)
      (is (= db result-db)
          "move-resolved-spell should return db unchanged for copy already in :exile")
      ;; Object should still exist in :exile
      (is (= :exile (:object/zone (queries/get-object result-db src-id)))
          "Copy should remain in :exile"))))


(deftest test-move-resolved-spell-unexpected-zone-emits-warn-and-is-noop
  (testing "move-resolved-spell returns db unchanged + console.warn for unexpected zone :hand"
    (let [db (th/create-test-db {:mana {:black 3}})
          [db obj-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          obj (queries/get-object db obj-id)
          warned? (atom false)
          _orig js/console.warn]
      ;; Capture console.warn
      (set! js/console.warn (fn [& _] (reset! warned? true)))
      (try
        (let [result-db (engine-resolution/move-resolved-spell db obj-id obj)]
          ;; db unchanged
          (is (= db result-db)
              "move-resolved-spell should return db unchanged for :hand zone")
          ;; Spell stays in hand
          (is (= :hand (:object/zone (queries/get-object result-db obj-id)))
              "Spell should remain in :hand")
          ;; console.warn should have been called
          (is @warned?
              "console.warn should be emitted for unexpected zone :hand"))
        (finally
          (set! js/console.warn _orig))))))


(deftest test-move-resolved-spell-uses-db-zone-not-stale-obj
  (testing "move-resolved-spell re-fetches zone from db, ignoring stale obj parameter"
    (let [db (th/create-test-db {:mana {:black 3}})
          [db obj-id] (th/add-card-to-zone db :dark-ritual :exile :player-1)
          ;; Create a STALE obj that claims zone :stack — but db has :exile
          fresh-obj (queries/get-object db obj-id)
          stale-obj (assoc fresh-obj :object/zone :stack)
          result-db (engine-resolution/move-resolved-spell db obj-id stale-obj)]
      ;; Should be a no-op because db says :exile, not :stack
      (is (= db result-db)
          "move-resolved-spell should ignore stale obj zone and use db-fetched zone")
      ;; Object should still be in :exile — not moved to graveyard
      (is (= :exile (:object/zone (queries/get-object result-db obj-id)))
          "Spell should remain in :exile despite stale obj claiming :stack"))))
