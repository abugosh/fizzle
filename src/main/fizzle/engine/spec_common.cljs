(ns fizzle.engine.spec-common
  "Shared spec predicates used across all spec modules (phases 1-4).

   These specs are domain-level predicates that appear in multiple spec files.
   Import this namespace to avoid duplicate predicate definitions.

   Specs defined here:
   - :game/player-id          — keyword? only (no int; ints are Datascript EIDs)
   - :game/object-id          — uuid? (game object identity)
   - :game/card-color         — #{:white :blue :black :red :green} (5 MTG colors, NO colorless)
   - :game/mana-color         — #{:white :blue :black :red :green :colorless} (6 pool colors)
   - :game/mana-map           — partial map of mana-color -> nat-int (any subset of 6 colors)
   - :game/mana-pool          — full 6-key mana map (all colors required, no extra keys)
   - :game/collection-flexible — set or vector (not list, not map)"
  (:require
    [cljs.spec.alpha :as s]))


;; =====================================================
;; Player and Object Identity
;; =====================================================

(s/def :game/player-id keyword?)

(s/def :game/object-id uuid?)


;; =====================================================
;; Color Predicates
;; Note: card-color (5 colors) != mana-color (6 colors) != mana-cost keys (8 keys)
;; =====================================================

(def card-colors
  "The 5 MTG card colors. Does NOT include :colorless — colorless is a mana pool color,
   not a card color. Matches card_spec/valid-colors."
  #{:white :blue :black :red :green})


(def mana-colors
  "The 6 mana pool colors. Includes :colorless but NOT :any or :x (those are mana-cost-only)."
  #{:white :blue :black :red :green :colorless})


(s/def :game/card-color card-colors)

(s/def :game/mana-color mana-colors)


;; =====================================================
;; Mana Map Specs
;; =====================================================

;; :game/mana-map — partial map, any subset of the 6 pool colors
;; Empty map {} is valid. Values must be non-negative integers.
(s/def :game/mana-map
  (s/map-of :game/mana-color nat-int?))


;; :game/mana-pool — full 6-key map, exactly the 6 pool color keys
;; All 6 colors must be present. No extra keys permitted.
(s/def :game/mana-pool
  (s/and :game/mana-map
         #(= 6 (count %))
         #(every? % [:white :blue :black :red :green :colorless])))


;; =====================================================
;; Collection Spec
;; =====================================================

;; :game/collection-flexible — used where a field accepts either a set or a vector.
;; Lists and maps are rejected. Mirrors the (s/or :set set? :vec vector?) pattern
;; duplicated across spec files.
(s/def :game/collection-flexible
  (s/or :set set? :vec vector?))
