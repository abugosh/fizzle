(ns fizzle.engine.mana-spec
  "Mana boundary validation specs for engine/mana.cljs.

   Validates mana maps at add-mana and pay-mana entry points.
   Uses goog.DEBUG guard via spec-util/validate-at-chokepoint! — dev-only,
   dead-code eliminated in production by Closure compiler.

   Specs defined here:
   - ::mana-add-arg  — partial mana map for add-mana argument (only valid pool colors)
   - ::mana-pay-arg  — mana cost for pay-mana argument (pool colors + :x for X costs)"
  (:require
    [cljs.spec.alpha :as s]
    [fizzle.engine.spec-common]
    [fizzle.engine.spec-util :as spec-util]))


;; add-mana receives partial mana maps: {:black 3} or {:white 1 :blue 2}
;; Only valid mana pool colors are allowed — :generic, :any are NOT valid.
(s/def ::mana-add-arg :game/mana-map)


;; pay-mana receives mana costs that may include :x (for X spells like Stroke of Genius).
;; resolve-x-cost strips :x before paying, but we validate the raw cost arg here.
;; :x values can be nat-int (count of X) or boolean (flag from card definitions).
(s/def ::mana-pay-arg
  (s/map-of (s/or :color :game/mana-color :x #{:x})
            (s/or :amount nat-int? :flag boolean?)))


(defn validate-mana-add-arg!
  "Validate a mana map argument at add-mana boundary.
   In dev (goog.DEBUG): logs console.error if invalid.
   In prod: no-op.
   Returns nil always."
  [mana-map label]
  (spec-util/validate-at-chokepoint! ::mana-add-arg mana-map label))


(defn validate-mana-pay-arg!
  "Validate a mana cost argument at pay-mana boundary.
   In dev (goog.DEBUG): logs console.error if invalid.
   In prod: no-op.
   Returns nil always."
  [cost label]
  (spec-util/validate-at-chokepoint! ::mana-pay-arg cost label))
