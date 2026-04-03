(ns fizzle.engine.spec-util
  "Shared spec validation utility for Phase 3 spec adoption.

   Provides validate-at-chokepoint! with goog.DEBUG guard:
   - In dev (goog.DEBUG): logs console.error on invalid data.
   - When *throw-on-spec-failure* is true: throws instead (for tests).
   - In prod (release): dead-code eliminated by Closure compiler."
  (:require
    [cljs.spec.alpha :as s]))


(def ^:dynamic *throw-on-spec-failure*
  "When true, validate-at-chokepoint! throws on invalid data instead of console.error.
   Bind true in tests to make spec failures assertable.
   Default: false (dev mode only logs to console)."
  false)


(defn validate-at-chokepoint!
  "Validate data against spec-key at a creation chokepoint.
   In dev (goog.DEBUG): logs console.error if invalid, throws if *throw-on-spec-failure*.
   In prod: no-op (dead-code eliminated).
   Returns nil always."
  [spec-key data label]
  (when ^boolean goog.DEBUG
    (when-not (s/valid? spec-key data)
      (let [msg (str label ": invalid data: " (s/explain-str spec-key data))]
        (if *throw-on-spec-failure*
          (throw (js/Error. msg))
          (js/console.error msg))))))
