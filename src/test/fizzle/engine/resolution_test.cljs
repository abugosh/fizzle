(ns fizzle.engine.resolution-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.resolution :as resolution]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.events.game :as game]
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
          result (resolution/resolve-stack-item db :player-1 stack-item)]
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
          result (resolution/resolve-stack-item db :player-1 stack-item)]
      ;; Should complete without selection
      (is (nil? (:needs-selection result))
          "Targeted Orim's Chant should not need selection when targets stored")
      ;; Player-2 should have restriction
      (let [p2-grants (grants/get-player-grants (:db result) :player-2)]
        (is (seq p2-grants)
            "Player-2 should have grants after resolution")
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
          result (resolution/resolve-stack-item db :player-1 stack-item)]
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
          result (resolution/resolve-stack-item db :player-1 stack-item)]
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
          result (resolution/resolve-stack-item db :player-1 stack-item)]
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
          result (resolution/resolve-stack-item db :player-1 stack-item)]
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
          result (resolution/resolve-stack-item db :player-1 stack-item)]
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
                          (let [result (game/resolve-one-item d :player-1)]
                            (recur (:db result) (inc iterations)))))]
      ;; Spell should be in graveyard
      (is (= :graveyard (:object/zone (queries/get-object result-loop obj-id)))
          "Dark Ritual should be in graveyard after resolution")
      ;; Stack should be empty
      (is (nil? (stack/get-top-stack-item result-loop))
          "Stack should be empty"))))


(deftest test-default-defmethod-returns-db-unchanged
  (testing ":default defmethod returns {:db db} for unknown types"
    (let [db (th/create-test-db)
          stack-item {:stack-item/type :unknown-type
                      :stack-item/controller :player-1}
          result (resolution/resolve-stack-item db :player-1 stack-item)]
      (is (= db (:db result))
          "Default defmethod should return db unchanged"))))
