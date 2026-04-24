(ns fizzle.engine.spec-generative-test
  "Property-based tests: roundtrip generators through specs for all 4 multi-specs.
   Verifies every generated sample passes s/valid? — catches generator/spec divergence.

   Also includes:
   - Chokepoint acceptance tests: generated valid data passes through production chokepoints
   - Rejection tests: generated data with required keys removed fails s/valid?"
  (:require
    [cljs.spec.alpha :as s]
    [cljs.test :refer-macros [deftest testing is]]
    [clojure.test.check.generators :as tgen]
    [fizzle.bots.action-spec :as action-spec]
    [fizzle.db.init :refer [init-game-state]]
    [fizzle.engine.card-spec]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.mana-spec :as mana-spec]
    [fizzle.engine.object-spec :as object-spec]
    [fizzle.engine.spec-generators :as sgen]
    [fizzle.engine.spec-util :as spec-util]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.stack-spec]
    [fizzle.events.selection.spec :as sel-spec]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; Roundtrip tests: s/exercise produces valid data
;; =====================================================

(deftest test-effect-exercise-roundtrip
  (testing "s/exercise produces valid effects for all types"
    (doseq [[val _] (s/exercise :fizzle.engine.card-spec/effect 10)]
      (is (s/valid? :fizzle.engine.card-spec/effect val)
          (str "Generated effect failed validation: " (pr-str val))))))


(deftest test-stack-item-exercise-roundtrip
  (testing "s/exercise produces valid stack-items for all types"
    (doseq [[val _] (s/exercise :fizzle.engine.stack-spec/stack-item 10)]
      (is (s/valid? :fizzle.engine.stack-spec/stack-item val)
          (str "Generated stack-item failed validation: " (pr-str val))))))


(deftest test-selection-exercise-roundtrip
  (testing "s/exercise produces valid selections for all types"
    (doseq [[val _] (s/exercise :fizzle.events.selection.spec/selection 10)]
      (is (s/valid? :fizzle.events.selection.spec/selection val)
          (str "Generated selection failed validation: " (pr-str val))))))


(deftest test-bot-action-exercise-roundtrip
  (testing "s/exercise produces valid bot-actions for all types"
    (doseq [[val _] (s/exercise :fizzle.bots.action-spec/bot-action 10)]
      (is (s/valid? :fizzle.bots.action-spec/bot-action val)
          (str "Generated bot-action failed validation: " (pr-str val))))))


;; =====================================================
;; Chokepoint acceptance tests
;; Generated valid data passes through production validation functions
;; without throwing — catches bugs where generator and spec agree but
;; the chokepoint function has additional logic beyond the spec.
;; =====================================================

(deftest test-stack-item-chokepoint-accepts-generated
  (testing "generated stack-item maps pass through validate-stack-item! without throwing"
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [db (th/create-test-db)]
        (doseq [[generated _] (s/exercise :fizzle.engine.stack-spec/stack-item 5)]
          (is (some? (stack/create-stack-item db generated))
              (str "Generated stack-item caused chokepoint error: " (pr-str generated))))))))


(deftest test-mana-add-chokepoint-accepts-generated
  (testing "generated mana-add-arg maps pass through validate-mana-add-arg! without throwing"
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [db (init-game-state)]
        (doseq [_ (range 5)]
          (let [mana-map (first (tgen/sample (sgen/gen-mana-add-arg) 1))]
            (is (some? (mana/add-mana db :player-1 mana-map))
                (str "Generated mana-add-arg caused chokepoint error: " (pr-str mana-map)))))))))


(deftest test-mana-pay-chokepoint-accepts-generated
  (testing "generated mana-pay-arg maps pass through validate-mana-pay-arg! without throwing"
    (binding [spec-util/*throw-on-spec-failure* true]
      (doseq [_ (range 5)]
        (let [cost (first (tgen/sample (sgen/gen-mana-pay-arg) 1))]
          ;; validate-mana-pay-arg! is called from pay-mana -- call directly to test the chokepoint
          (is (nil? (mana-spec/validate-mana-pay-arg! cost "test"))
              (str "Generated mana-pay-arg caused chokepoint error: " (pr-str cost))))))))


(deftest test-object-tx-chokepoint-accepts-generated
  (testing "generated object-tx maps pass through validate-object-tx! without throwing"
    (binding [spec-util/*throw-on-spec-failure* true]
      (doseq [_ (range 5)]
        (let [obj-tx (first (tgen/sample (sgen/gen-object-tx) 1))]
          (is (nil? (object-spec/validate-object-tx! obj-tx))
              (str "Generated object-tx caused chokepoint error: " (pr-str obj-tx))))))))


(deftest test-bot-action-chokepoint-accepts-generated
  (testing "generated bot-action maps pass through validate-bot-action! without throwing"
    (binding [spec-util/*throw-on-spec-failure* true]
      (doseq [[generated _] (s/exercise :fizzle.bots.action-spec/bot-action 5)]
        (is (nil? (action-spec/validate-bot-action! generated))
            (str "Generated bot-action caused chokepoint error: " (pr-str generated)))))))


(deftest test-selection-chokepoint-accepts-generated
  (testing "generated selection maps are spec-valid and stored by set-pending-selection"
    ;; set-pending-selection calls validate-at-chokepoint! which runs in dev mode.
    ;; Binding *throw-on-spec-failure* true here would cause this test to throw on any
    ;; invalid generator output — but we verify validity separately with s/valid? first.
    ;; The set-pending-selection call verifies the chokepoint stores valid data without error.
    ;; ADR-030: set-pending-selection enriches selections with :selection/mechanism and
    ;; :selection/domain, so stored map is not identical to input — compare enriched form.
    (doseq [[generated _] (s/exercise :fizzle.events.selection.spec/selection 5)]
      (is (s/valid? :fizzle.events.selection.spec/selection generated)
          (str "Generator produced invalid selection (generator/spec divergence): " (pr-str generated)))
      (let [app-db {:game/db (th/create-test-db)}
            result (sel-spec/set-pending-selection app-db generated)
            expected (sel-spec/inject-mechanism-domain generated)]
        (is (= expected (:game/pending-selection result))
            (str "set-pending-selection failed to store valid selection: " (pr-str generated)))))))


(deftest test-selection-chokepoint-rejects-invalid-when-throw-binding
  (testing "set-pending-selection throws on invalid selection when *throw-on-spec-failure* true"
    ;; Bug caught: if validate-at-chokepoint! were no-op'd or removed from
    ;; set-pending-selection, this test would not throw and would fail.
    ;; Companion to test-selection-chokepoint-accepts-generated — proves the throw
    ;; path is shape-specific (valid data passes, invalid data throws).
    (binding [spec-util/*throw-on-spec-failure* true]
      (let [app-db {:game/db (th/create-test-db)}
            ;; Intentionally invalid: :selection/type alone is not a valid :discard
            ;; — missing required :lifecycle, :player-id, :selected, etc.
            invalid-sel {:selection/type :discard}]
        (is (thrown? js/Error
              (sel-spec/set-pending-selection app-db invalid-sel))
            "set-pending-selection must throw on invalid selection when binding is true")))))


;; =====================================================
;; Rejection tests for multi-specs
;; Generated data with required keys removed fails s/valid?
;; Catches vacuously true specs that accept everything.
;; =====================================================

(deftest test-effect-rejects-missing-type
  (testing "effect spec rejects map missing required :effect/type"
    (let [[valid _] (first (s/exercise :fizzle.engine.card-spec/effect 1))
          broken (dissoc valid :effect/type)]
      (is (not (s/valid? :fizzle.engine.card-spec/effect broken))
          "Effect without :effect/type should fail spec"))))


(deftest test-effect-rejects-invalid-type-value
  (testing "effect spec rejects map with unregistered :effect/type dispatch value"
    (let [[valid _] (first (s/exercise :fizzle.engine.card-spec/effect 1))
          broken (assoc valid :effect/type :this-type-does-not-exist)]
      (is (not (s/valid? :fizzle.engine.card-spec/effect broken))
          "Effect with unknown :effect/type should fail spec"))))


(deftest test-stack-item-rejects-missing-type
  (testing "stack-item spec rejects map missing required :stack-item/type"
    (let [[valid _] (first (s/exercise :fizzle.engine.stack-spec/stack-item 1))
          broken (dissoc valid :stack-item/type)]
      (is (not (s/valid? :fizzle.engine.stack-spec/stack-item broken))
          "Stack-item without :stack-item/type should fail spec"))))


(deftest test-stack-item-rejects-missing-controller
  (testing "stack-item spec rejects map missing required :stack-item/controller"
    (let [[valid _] (first (s/exercise :fizzle.engine.stack-spec/stack-item 1))
          broken (dissoc valid :stack-item/controller)]
      (is (not (s/valid? :fizzle.engine.stack-spec/stack-item broken))
          "Stack-item without :stack-item/controller should fail spec"))))


(deftest test-stack-item-rejects-int-controller
  (testing "stack-item spec rejects int :stack-item/controller (must be keyword)"
    (let [[valid _] (first (s/exercise :fizzle.engine.stack-spec/stack-item 1))
          broken (assoc valid :stack-item/controller 42)]
      (is (not (s/valid? :fizzle.engine.stack-spec/stack-item broken))
          "Stack-item with int controller should fail spec — player-id must be keyword"))))


(deftest test-stack-item-rejects-invalid-type-value
  (testing "stack-item spec rejects map with unregistered :stack-item/type dispatch value"
    (let [[valid _] (first (s/exercise :fizzle.engine.stack-spec/stack-item 1))
          broken (assoc valid :stack-item/type :this-type-does-not-exist)]
      (is (not (s/valid? :fizzle.engine.stack-spec/stack-item broken))
          "Stack-item with unknown :stack-item/type should fail spec"))))


(deftest test-selection-rejects-missing-type
  (testing "selection spec rejects map missing required :selection/type"
    (let [[valid _] (first (s/exercise :fizzle.events.selection.spec/selection 1))
          broken (dissoc valid :selection/type)]
      (is (not (s/valid? :fizzle.events.selection.spec/selection broken))
          "Selection without :selection/type should fail spec"))))


(deftest test-selection-rejects-invalid-type-value
  (testing "selection spec rejects map with unregistered :selection/type dispatch value"
    (let [[valid _] (first (s/exercise :fizzle.events.selection.spec/selection 1))
          broken (assoc valid :selection/type :this-type-does-not-exist)]
      (is (not (s/valid? :fizzle.events.selection.spec/selection broken))
          "Selection with unknown :selection/type should fail spec"))))


(deftest test-bot-action-rejects-missing-action
  (testing "bot-action spec rejects map missing required :action"
    (let [[valid _] (first (s/exercise :fizzle.bots.action-spec/bot-action 1))
          broken (dissoc valid :action)]
      (is (not (s/valid? :fizzle.bots.action-spec/bot-action broken))
          "Bot-action without :action should fail spec"))))


(deftest test-bot-action-rejects-invalid-action-value
  (testing "bot-action spec rejects map with unregistered :action dispatch value"
    (let [[valid _] (first (s/exercise :fizzle.bots.action-spec/bot-action 1))
          broken (assoc valid :action :this-type-does-not-exist)]
      (is (not (s/valid? :fizzle.bots.action-spec/bot-action broken))
          "Bot-action with unknown :action should fail spec"))))


;; =====================================================
;; Rejection tests for boundary specs (mana + object-tx)
;; =====================================================

(deftest test-mana-add-arg-rejects-invalid-key
  (testing "mana-add-arg rejects invalid mana color keys"
    (is (not (s/valid? ::mana-spec/mana-add-arg {:generic 1}))
        "mana-add-arg with :generic key should fail — not a pool color")
    (is (not (s/valid? ::mana-spec/mana-add-arg {:any 1}))
        "mana-add-arg with :any key should fail — not a pool color")
    (is (not (s/valid? ::mana-spec/mana-add-arg {:x 1}))
        "mana-add-arg with :x key should fail — :x is not a pool color")))


(deftest test-mana-pay-arg-rejects-invalid-key
  (testing "mana-pay-arg rejects unknown mana keys"
    (is (not (s/valid? ::mana-spec/mana-pay-arg {:generic 1}))
        "mana-pay-arg with :generic key should fail")
    (is (not (s/valid? ::mana-spec/mana-pay-arg {:any 1}))
        "mana-pay-arg with :any key should fail")))


(deftest test-object-tx-rejects-missing-required-field
  (testing "object-tx rejects map missing any required field"
    (let [valid {:object/id         (random-uuid)
                 :object/card       42
                 :object/zone       :hand
                 :object/owner      1
                 :object/controller 1
                 :object/tapped     false
                 :object/position   0}]
      (doseq [required-key [:object/id :object/card :object/zone
                            :object/owner :object/controller
                            :object/tapped :object/position]]
        (is (not (s/valid? ::object-spec/object-tx (dissoc valid required-key)))
            (str "object-tx missing " required-key " should fail spec"))))))


(deftest test-object-tx-rejects-wrong-field-type
  (testing "object-tx rejects wrong types for required fields"
    (let [valid {:object/id         (random-uuid)
                 :object/card       42
                 :object/zone       :hand
                 :object/owner      1
                 :object/controller 1
                 :object/tapped     false
                 :object/position   0}]
      (is (not (s/valid? ::object-spec/object-tx (assoc valid :object/id :not-a-uuid)))
          "Non-UUID :object/id should fail")
      (is (not (s/valid? ::object-spec/object-tx (assoc valid :object/card :not-an-int)))
          "Non-int :object/card should fail — must be Datascript EID")
      (is (not (s/valid? ::object-spec/object-tx (assoc valid :object/zone :invalid-zone)))
          "Invalid zone keyword should fail")
      (is (not (s/valid? ::object-spec/object-tx (assoc valid :object/tapped "yes")))
          "String :object/tapped should fail — must be boolean"))))
