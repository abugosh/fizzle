(ns fizzle.events.selection.spell-cleanup-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.events.abilities :as abilities]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.events.selection.spell-cleanup :as spell-cleanup]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; cleanup-selection-source — existing tests (5 branches)
;; =====================================================

;; Legacy tests updated to reflect 3-way case refactor (fizzle-hve2):
;; nil source-type is now the default no-op branch — returns db unchanged with no warning.
;; Warnings for unexpected zones now live in move-resolved-spell (fizzle-9cos).

(deftest test-cleanup-nil-source-type-with-exiled-spell-returns-db-unchanged
  (testing "nil source-type + spell in exile returns game-db unchanged (default no-op branch)"
    (let [db (th/create-test-db)
          [db _obj-id] (th/add-card-to-zone db :dark-ritual :exile :player-1)
          selection {:selection/source-type nil}
          result (spell-cleanup/cleanup-selection-source db selection)]
      (is (= db result)
          "nil source-type should return db unchanged (3-way case default branch)"))))


(deftest test-cleanup-nil-source-type-is-silent-no-op
  (testing "nil source-type returns game-db unchanged without warnings (warnings moved to move-resolved-spell)"
    (let [db (th/create-test-db)
          [db _obj-id] (th/add-card-to-zone db :dark-ritual :battlefield :player-1)
          warnings (atom [])
          original-warn js/console.warn]
      ;; Capture console.warn calls
      (set! js/console.warn (fn [& args] (swap! warnings conj (apply str args))))
      (try
        (let [selection {:selection/source-type nil}
              result (spell-cleanup/cleanup-selection-source db selection)]
          (is (= db result)
              "nil source-type should return db unchanged")
          (is (= 0 (count @warnings))
              "nil source-type cleanup should produce no warnings (3-way default = silent no-op)"))
        (finally
          (set! js/console.warn original-warn))))))


(deftest test-cleanup-nil-spell-id-returns-db-unchanged
  (testing "nil spell-id (no source-type) returns game-db unchanged"
    (let [db (th/create-test-db)
          selection {:selection/spell-id nil}
          result (spell-cleanup/cleanup-selection-source db selection)]
      (is (= db result)
          "Should return db unchanged when no spell-id"))))


;; =====================================================
;; Test C — cleanup with :spell source-type invokes move + removes stack-item
;; =====================================================

(deftest test-cleanup-spell-source-type-moves-spell-and-removes-stack-item
  (testing ":spell source-type calls move-resolved-spell and removes stack-item"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent {:bot-archetype :goldfish}))
          [db obj-id] (th/add-card-to-zone db :dark-ritual :stack :player-1)
          obj-eid (q/get-object-eid db obj-id)
          ;; Create a stack-item referencing the spell object via production path
          db (stack/create-stack-item db {:stack-item/type :spell
                                          :stack-item/controller :player-1
                                          :stack-item/object-ref obj-eid
                                          :stack-item/effects []})
          si (stack/get-stack-item-by-object-ref db obj-eid)
          si-eid (:db/id si)
          ;; Selection with explicit :spell source-type
          selection {:selection/source-type :spell
                     :selection/spell-id obj-id
                     :selection/stack-item-eid si-eid}
          result (spell-cleanup/cleanup-selection-source db selection)]
      ;; Spell should have moved off stack (to graveyard for non-permanent sorcery)
      (is (not= :stack (th/get-object-zone result obj-id))
          "Spell should have moved off stack after :spell cleanup")
      ;; Stack-item should be gone (no longer findable by object-ref)
      (is (nil? (stack/get-stack-item-by-object-ref result obj-eid))
          "Stack-item should be removed after :spell cleanup"))))


;; =====================================================
;; Test D — cleanup with :ability source-type removes stack-item only
;; =====================================================

(deftest test-cleanup-ability-source-type-removes-stack-item-only
  (testing ":ability source-type removes stack-item but does NOT move any spell"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent {:bot-archetype :goldfish}))
          ;; Put a spell on the stack (to verify it's NOT moved)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :stack :player-1)
          ;; Create a stack-item for the ability (not referencing the spell object)
          ;; :activated-ability requires :stack-item/source and :stack-item/effects
          db (stack/create-stack-item db {:stack-item/type :activated-ability
                                          :stack-item/controller :player-1
                                          :stack-item/source (random-uuid)
                                          :stack-item/effects []})
          ;; Get the ability stack-item (has no object-ref, type = activated-ability)
          all-sis (q/get-all-stack-items db)
          ability-si (first (filter #(= :activated-ability (:stack-item/type %)) all-sis))
          si-eid (:db/id ability-si)
          ;; Selection with :ability source-type and the ability stack-item EID
          selection {:selection/source-type :ability
                     :selection/stack-item-eid si-eid}
          result (spell-cleanup/cleanup-selection-source db selection)]
      ;; Spell on stack should remain unchanged (ability cleanup doesn't move spells)
      (is (= :stack (th/get-object-zone result obj-id))
          "Spell on stack should remain after :ability cleanup")
      ;; The ability stack-item should be removed (no longer findable)
      (is (nil? (d/q '[:find ?e .
                       :in $ ?eid
                       :where [?e :db/id ?eid] [?e :stack-item/type _]]
                     result si-eid))
          "Ability stack-item should be removed after :ability cleanup"))))


;; =====================================================
;; Test E — cleanup with nil source-type → no-op
;; =====================================================

(deftest test-cleanup-nil-source-type-is-no-op
  (testing "nil :selection/source-type returns db unchanged"
    (let [db (th/create-test-db)
          selection {:selection/source-type nil
                     :selection/spell-id nil}
          result (spell-cleanup/cleanup-selection-source db selection)]
      (is (= db result)
          "nil source-type should return db unchanged"))))


;; =====================================================
;; Test F — cleanup with :spell source-type but nil spell-id → no-op, no throw
;; =====================================================

(deftest test-cleanup-spell-source-type-nil-spell-id-is-safe
  (testing ":spell source-type with nil spell-id returns db unchanged (no exception)"
    (let [db (th/create-test-db)
          selection {:selection/source-type :spell
                     :selection/spell-id nil}
          result (spell-cleanup/cleanup-selection-source db selection)]
      (is (= db result)
          ":spell source-type with nil spell-id should return db unchanged"))))


;; =====================================================
;; Test A — build-selection-from-result sets :spell source-type
;; =====================================================

(deftest test-build-selection-from-result-sets-spell-source-type
  (testing "resolve-one-item on a spell with interactive effect sets :selection/source-type :spell"
    (let [;; Merchant Scroll costs 1U; create-test-db with mana to cast it
          db (th/create-test-db {:mana {:colorless 1 :blue 1}})
          db (th/add-opponent db {:bot-archetype :goldfish})
          ;; Add library card so tutor has something to find
          [db _] (th/add-cards-to-library db [:brain-freeze] :player-1)
          [db obj-id] (th/add-card-to-zone db :merchant-scroll :hand :player-1)
          ;; Cast the spell (rules/cast-spell pays mana from pool)
          db-cast (rules/cast-spell db :player-1 obj-id)
          ;; resolve-top returns {:db db :selection sel} for interactive effects
          {:keys [selection]} (th/resolve-top db-cast)]
      (is (some? selection)
          "Merchant Scroll should produce a pending selection (tutor effect is interactive)")
      (is (= :spell (:selection/source-type selection))
          "Selection from spell resolution should have :selection/source-type :spell"))))


;; =====================================================
;; Test B — ability activation sets :ability source-type on targeting path
;; =====================================================

(deftest test-ability-targeting-path-sets-ability-source-type
  (testing "activate-ability on targeting path sets :selection/source-type :ability"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent {:bot-archetype :goldfish}))
          ;; Urza's Bauble has targeting (any-player) + tap/sacrifice-self cost (not sacrifice-permanent)
          ;; This routes through the direct targeting path (line 229 in abilities.cljs),
          ;; which currently does NOT set :selection/source-type :ability. Test verifies the fix.
          [db obj-id] (th/add-card-to-zone db :urzas-bauble :battlefield :player-1)
          result (abilities/activate-ability db :player-1 obj-id 0)]
      ;; Urza's Bauble has targeting — should produce a pending selection
      (is (some? (:pending-selection result))
          "Urza's Bauble should produce a pending targeting selection")
      (when-let [sel (:pending-selection result)]
        (is (= :ability (:selection/source-type sel))
            "Ability targeting selection should have :selection/source-type :ability")))))


;; =====================================================
;; Test G — source-type propagates through standard-path chain
;; =====================================================

;; Test domain policy for test G: returns {:db game-db} (no side effects)
(defmethod sel-core/apply-domain-policy :test-spell-chain-g
  [game-db _selection]
  {:db game-db})


(deftest test-source-type-propagates-through-standard-path-chain
  (testing "source-type from parent selection propagates to chained interactive effect selection"
    (let [db (-> (th/create-test-db)
                 (th/add-opponent {:bot-archetype :goldfish}))
          ;; Put a spell on the stack (gives us a valid spell-id object)
          [db obj-id] (th/add-card-to-zone db :dark-ritual :stack :player-1)
          ;; Add a card to hand so discard effect has something to operate on
          [db _hand-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          ;; Build a selection with :spell source-type and a remaining discard effect
          ;; When confirmed, standard-path will process the remaining discard effect,
          ;; find it interactive, and create a chained selection — which should inherit source-type.
          selection {:selection/type :test-spell-chain-g
                     :selection/mechanism :pick-from-zone
                     :selection/domain :test-spell-chain-g
                     :selection/source-type :spell
                     :selection/spell-id obj-id
                     :selection/player-id :player-1
                     :selection/caster-id :player-1
                     :selection/selected #{}
                     :selection/validation :always
                     :selection/auto-confirm? false
                     :selection/remaining-effects [{:effect/type :discard
                                                    :effect/selection :player
                                                    :effect/count 1
                                                    :effect/target :self}]}
          app-db {:game/db db :game/pending-selection selection}
          result (sel-core/confirm-selection-impl app-db)
          chained-sel (:game/pending-selection result)]
      ;; The discard effect should have created a chained selection (player has a card)
      (is (some? chained-sel)
          "Should have a chained selection after remaining discard effect")
      ;; The chained selection should inherit the parent's source-type
      (is (= :spell (:selection/source-type chained-sel))
          "Chained selection should inherit :spell source-type from parent"))))
