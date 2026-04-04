(ns fizzle.engine.spec-generative-test
  "Property-based tests: roundtrip generators through specs for all 4 multi-specs.
   Verifies every generated sample passes s/valid? — catches generator/spec divergence."
  (:require
    [cljs.spec.alpha :as s]
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.bots.action-spec]
    [fizzle.engine.card-spec]
    [fizzle.engine.spec-generators]
    [fizzle.engine.stack-spec]
    [fizzle.events.selection.spec]))


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
