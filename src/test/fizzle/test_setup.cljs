(ns fizzle.test-setup
  "Test-suite-wide setup. Required transitively by test_helpers.cljs.
   Binds *throw-on-spec-failure* to true so spec chokepoint violations
   fail tests instead of logging silently."
  (:require
    [fizzle.engine.spec-util :as spec-util]))


(set! spec-util/*throw-on-spec-failure* true)
