(ns fizzle.engine.spec-generators
  "Custom generators for all 4 multi-spec types.
   Registers generators via s/with-gen on each multi-spec so s/exercise works.

   ONLY require this namespace from test code — it redefines specs with custom
   generators, which must never pollute production spec registry.

   Generator rules:
   - Optional fields (s/keys :opt) must be ABSENT (not nil) unless spec uses s/nilable
   - :stack-item/targets is (s/nilable map?) — generates nil OR a valid map
   - UUIDs: (tgen/fmap (fn [_] (random-uuid)) tgen/nat) — no built-in in CLJS
   - Bot actions use UNNAMESPACED keys (:action, :player-id, :object-id)"
  (:require
    [cljs.spec.alpha :as s]
    [clojure.test.check.generators :as tgen]
    [fizzle.bots.action-spec :as action-spec]
    [fizzle.engine.card-spec :as card-spec]
    [fizzle.engine.mana-spec]
    [fizzle.engine.object-spec]
    [fizzle.engine.spec-common]
    [fizzle.engine.stack-spec :as stack-spec]
    [fizzle.events.selection.spec :as sel-spec]))


;; =====================================================
;; Primitive generators
;; =====================================================

(def gen-uuid
  (tgen/fmap (fn [_] (random-uuid)) tgen/nat))


(def gen-player-id
  (tgen/elements [:player-1 :player-2]))


(def gen-object-id
  (tgen/one-of [(tgen/return :test-object) gen-uuid]))


(def gen-mana-color
  (tgen/elements [:white :blue :black :red :green :colorless]))


(def gen-mana-map
  "Partial mana map — any subset of 6 colors."
  (tgen/fmap
    (fn [pairs] (into {} pairs))
    (tgen/vector
      (tgen/tuple gen-mana-color (tgen/choose 0 5))
      0 4)))


(def gen-target-ref-kw
  (tgen/elements [:target :player :opponent :self :welder-bf :welder-gy]))


(def gen-effect-amount
  (tgen/one-of [(tgen/choose 1 5)
                (tgen/elements [:storm :x])]))


(def gen-effect-count
  (tgen/one-of [(tgen/choose 1 5)
                (tgen/elements [:all :storm])]))


(def gen-selection-kw
  (tgen/elements [:player :opponent :self]))


(def gen-zone-kw
  (tgen/elements [:hand :graveyard :library :battlefield :exile]))


(def gen-permanent-type-kw
  (tgen/elements [:creature :land :artifact :enchantment]))


(def gen-restriction-type
  (tgen/elements #{:cannot-cast-spells :cannot-attack
                   :cannot-cast-instants-sorceries :cannot-activate-non-mana-abilities}))


(def gen-condition
  (tgen/hash-map :condition/type (tgen/elements [:threshold :no-counters :target-is-color :power-gte])))


(def gen-lifecycle
  (tgen/elements [:standard :finalized :chaining]))


(def gen-validation-kw
  (tgen/elements [:exact :at-most :at-least-one :exact-or-zero :always]))


(def gen-mode-map
  (tgen/return {:mode/id :primary :mode/mana-cost {:blue 1}}))


(def gen-target-requirement
  (tgen/return {:target/id :target :target/type :object}))


;; =====================================================
;; Effect generators (~40 types)
;; =====================================================

(defn- maybe-condition
  []
  (tgen/one-of [(tgen/return {})
                (tgen/hash-map :effect/condition gen-condition)]))


(defn gen-effect-mill
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :mill)
                     :effect/amount gen-effect-amount)
      (maybe-condition))))


(defn gen-effect-draw
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :draw)
                     :effect/amount gen-effect-amount)
      (maybe-condition))))


(defn gen-effect-deal-damage
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :deal-damage)
                     :effect/amount gen-effect-amount)
      (maybe-condition))))


(defn gen-effect-discard
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :discard)
                     :effect/count gen-effect-count
                     :effect/selection gen-selection-kw)
      (maybe-condition))))


(defn gen-effect-return-from-graveyard
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :return-from-graveyard)
                     :effect/count gen-effect-count
                     :effect/selection gen-selection-kw)
      (maybe-condition))))


(defn gen-effect-exile-self
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :exile-self))
      (maybe-condition))))


(defn gen-effect-bounce
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :bounce)
                     :effect/target-ref gen-target-ref-kw)
      (maybe-condition))))


(defn gen-effect-bounce-all
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :bounce-all)
                     :effect/criteria (tgen/return {:match/types #{:artifact}}))
      (maybe-condition))))


(defn gen-effect-destroy
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :destroy)
                     :effect/target-ref gen-target-ref-kw)
      (maybe-condition))))


(defn gen-effect-discard-hand
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :discard-hand))
      (maybe-condition))))


(defn gen-effect-sacrifice
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :sacrifice))
      (maybe-condition))))


(defn gen-effect-exile-zone
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :exile-zone)
                     :effect/target-ref gen-target-ref-kw
                     :effect/zone gen-zone-kw)
      (maybe-condition))))


(defn gen-effect-phase-out
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :phase-out)
                     :effect/target-ref gen-target-ref-kw)
      (maybe-condition))))


(defn gen-effect-chain-bounce
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :chain-bounce)
                     :effect/target-ref gen-target-ref-kw)
      (maybe-condition))))


(defn gen-effect-untap-lands
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :untap-lands)
                     :effect/count gen-effect-count)
      (maybe-condition))))


(defn gen-effect-tap-all
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :tap-all)
                     :effect/target gen-target-ref-kw
                     :effect/permanent-type gen-permanent-type-kw)
      (maybe-condition))))


(defn gen-effect-untap-all
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :untap-all)
                     :effect/target gen-target-ref-kw
                     :effect/permanent-type gen-permanent-type-kw)
      (maybe-condition))))


(defn gen-effect-welder-swap
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :welder-swap)
                     :effect/target-ref (tgen/return :welder-bf)
                     :effect/graveyard-ref (tgen/return :welder-gy))
      (maybe-condition))))


(defn gen-effect-add-mana
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :add-mana)
                     :effect/mana gen-mana-map)
      (maybe-condition))))


(defn gen-effect-tutor
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :tutor)
                     :effect/target-zone gen-zone-kw)
      (maybe-condition))))


(defn gen-effect-scry
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :scry)
                     :effect/amount gen-effect-amount)
      (maybe-condition))))


(defn gen-effect-peek-and-select
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :peek-and-select)
                     :effect/count gen-effect-count
                     :effect/selected-zone gen-zone-kw
                     :effect/remainder-zone gen-zone-kw
                     :effect/select-count (tgen/choose 1 4))
      (maybe-condition))))


(defn gen-effect-peek-and-reorder
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :peek-and-reorder)
                     :effect/count gen-effect-count)
      (maybe-condition))))


(defn gen-effect-peek-random-hand
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :peek-random-hand)
                     :effect/target-ref gen-target-ref-kw)
      (maybe-condition))))


(defn gen-effect-grant-flashback
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :grant-flashback)
                     :effect/target-ref gen-target-ref-kw)
      (maybe-condition))))


(defn gen-effect-grant-delayed-draw
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :grant-delayed-draw))
      (maybe-condition))))


(defn gen-effect-grant-mana-ability
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :grant-mana-ability)
                     :effect/target gen-target-ref-kw
                     :effect/ability (tgen/return {:ability/type :mana}))
      (maybe-condition))))


(defn gen-effect-add-restriction
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :add-restriction)
                     :restriction/type gen-restriction-type)
      (maybe-condition))))


(defn gen-effect-add-counters
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :add-counters)
                     :effect/target gen-target-ref-kw
                     :effect/counters (tgen/return {:+1/+1 1}))
      (maybe-condition))))


(defn gen-effect-counter-spell
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :counter-spell)
                     :effect/target-ref gen-target-ref-kw)
      (maybe-condition))))


(defn gen-effect-counter-ability
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :counter-ability)
                     :effect/target-ref gen-target-ref-kw)
      (maybe-condition))))


(defn gen-effect-apply-pt-modifier
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :apply-pt-modifier)
                     :effect/target-ref gen-target-ref-kw
                     :effect/power (tgen/choose -4 4)
                     :effect/toughness (tgen/choose -4 4))
      (maybe-condition))))


(defn gen-effect-lose-life-equal-to-toughness
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :lose-life-equal-to-toughness)
                     :effect/target-ref gen-target-ref-kw)
      (maybe-condition))))


(defn gen-effect-gain-life-equal-to-cmc
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :gain-life-equal-to-cmc)
                     :effect/target-ref gen-target-ref-kw)
      (maybe-condition))))


(defn gen-effect-create-token
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :create-token)
                     :effect/token (tgen/return {:token/name "Beast" :token/power 4 :token/toughness 4}))
      (maybe-condition))))


(defn gen-effect-change-land-types
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :change-land-types))
      (maybe-condition))))


(defn gen-effect-discard-from-revealed-hand
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :discard-from-revealed-hand)
                     :effect/target gen-target-ref-kw
                     :effect/criteria (tgen/return {:match/not-types #{:creature :land}}))
      (maybe-condition))))


(defn gen-effect-storm-copies
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :storm-copies))
      (maybe-condition))))


(defn gen-effect-lose-life
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :lose-life)
                     :effect/amount gen-effect-amount)
      (maybe-condition))))


(defn gen-effect-gain-life
  []
  (tgen/fmap
    (fn [[base cond]] (merge base cond))
    (tgen/tuple
      (tgen/hash-map :effect/type (tgen/return :gain-life)
                     :effect/amount gen-effect-amount)
      (maybe-condition))))


;; Register generator on ::effect multi-spec
(s/def :fizzle.engine.card-spec/effect
  (s/with-gen
    (s/multi-spec card-spec/effect-type-spec :effect/type)
    #(tgen/one-of [(gen-effect-mill)
                   (gen-effect-draw)
                   (gen-effect-deal-damage)
                   (gen-effect-discard)
                   (gen-effect-return-from-graveyard)
                   (gen-effect-exile-self)
                   (gen-effect-bounce)
                   (gen-effect-bounce-all)
                   (gen-effect-destroy)
                   (gen-effect-discard-hand)
                   (gen-effect-sacrifice)
                   (gen-effect-exile-zone)
                   (gen-effect-phase-out)
                   (gen-effect-chain-bounce)
                   (gen-effect-untap-lands)
                   (gen-effect-tap-all)
                   (gen-effect-untap-all)
                   (gen-effect-welder-swap)
                   (gen-effect-add-mana)
                   (gen-effect-tutor)
                   (gen-effect-scry)
                   (gen-effect-peek-and-select)
                   (gen-effect-peek-and-reorder)
                   (gen-effect-peek-random-hand)
                   (gen-effect-grant-flashback)
                   (gen-effect-grant-delayed-draw)
                   (gen-effect-grant-mana-ability)
                   (gen-effect-add-restriction)
                   (gen-effect-add-counters)
                   (gen-effect-counter-spell)
                   (gen-effect-counter-ability)
                   (gen-effect-apply-pt-modifier)
                   (gen-effect-lose-life-equal-to-toughness)
                   (gen-effect-gain-life-equal-to-cmc)
                   (gen-effect-create-token)
                   (gen-effect-change-land-types)
                   (gen-effect-discard-from-revealed-hand)
                   (gen-effect-storm-copies)
                   (gen-effect-lose-life)
                   (gen-effect-gain-life)])))


;; =====================================================
;; Stack-item generators (~17 types)
;; =====================================================

(def gen-effects-vec
  "Generate a small vector of valid effects."
  (tgen/vector
    (tgen/one-of [(gen-effect-draw)
                  (gen-effect-add-mana)
                  (gen-effect-mill)])
    1 3))


(def gen-targets-nilable
  "s/nilable map? — generate nil OR an empty map."
  (tgen/one-of [(tgen/return nil)
                (tgen/return {})
                (tgen/hash-map :target (tgen/return :opponent))]))


(defn gen-stack-item-spell
  []
  (tgen/hash-map :stack-item/type (tgen/return :spell)
                 :stack-item/controller gen-player-id
                 :stack-item/source gen-uuid
                 :stack-item/object-ref (tgen/choose 1 9999)))


(defn gen-stack-item-storm-copy
  []
  (tgen/hash-map :stack-item/type (tgen/return :storm-copy)
                 :stack-item/controller gen-player-id
                 :stack-item/source gen-uuid
                 :stack-item/object-ref (tgen/choose 1 9999)))


(defn gen-stack-item-activated-ability
  []
  (tgen/hash-map :stack-item/type (tgen/return :activated-ability)
                 :stack-item/controller gen-player-id
                 :stack-item/source gen-uuid
                 :stack-item/effects gen-effects-vec))


(defn gen-stack-item-permanent-entered
  []
  (tgen/hash-map :stack-item/type (tgen/return :permanent-entered)
                 :stack-item/controller gen-player-id
                 :stack-item/effects gen-effects-vec))


(defn gen-stack-item-storm
  []
  (tgen/hash-map :stack-item/type (tgen/return :storm)
                 :stack-item/controller gen-player-id
                 :stack-item/source gen-uuid
                 :stack-item/effects gen-effects-vec
                 :stack-item/description (tgen/return "Storm copies")))


(defn gen-stack-item-declare-attackers
  []
  (tgen/hash-map :stack-item/type (tgen/return :declare-attackers)
                 :stack-item/controller gen-player-id))


(defn gen-stack-item-declare-blockers
  []
  (tgen/hash-map :stack-item/type (tgen/return :declare-blockers)
                 :stack-item/controller gen-player-id))


(defn gen-stack-item-combat-damage
  []
  (tgen/hash-map :stack-item/type (tgen/return :combat-damage)
                 :stack-item/controller gen-player-id))


(defn gen-stack-item-delayed-trigger
  []
  (tgen/hash-map :stack-item/type (tgen/return :delayed-trigger)
                 :stack-item/controller gen-player-id
                 :stack-item/effects gen-effects-vec))


(defn gen-stack-item-state-check-trigger
  []
  (tgen/hash-map :stack-item/type (tgen/return :state-check-trigger)
                 :stack-item/controller gen-player-id
                 :stack-item/source gen-uuid
                 :stack-item/effects gen-effects-vec))


;; Card trigger types — type + controller required; everything else optional (absent)
(defn gen-stack-item-permanent-tapped
  []
  (tgen/hash-map :stack-item/type (tgen/return :permanent-tapped)
                 :stack-item/controller gen-player-id))


(defn gen-stack-item-creature-attacked
  []
  (tgen/hash-map :stack-item/type (tgen/return :creature-attacked)
                 :stack-item/controller gen-player-id))


(defn gen-stack-item-land-entered
  []
  (tgen/hash-map :stack-item/type (tgen/return :land-entered)
                 :stack-item/controller gen-player-id))


(defn gen-stack-item-etb
  []
  (tgen/hash-map :stack-item/type (tgen/return :etb)
                 :stack-item/controller gen-player-id))


(defn gen-stack-item-triggered-ability
  []
  (tgen/hash-map :stack-item/type (tgen/return :triggered-ability)
                 :stack-item/controller gen-player-id))


(defn gen-stack-item-phase-entered
  []
  (tgen/hash-map :stack-item/type (tgen/return :phase-entered)
                 :stack-item/controller gen-player-id))


(defn gen-stack-item-test
  []
  (tgen/hash-map :stack-item/type (tgen/return :test)
                 :stack-item/controller gen-player-id))


;; Register generator on ::stack-item multi-spec
(s/def :fizzle.engine.stack-spec/stack-item
  (s/with-gen
    (s/multi-spec stack-spec/stack-item-type-spec :stack-item/type)
    #(tgen/one-of [(gen-stack-item-spell)
                   (gen-stack-item-storm-copy)
                   (gen-stack-item-activated-ability)
                   (gen-stack-item-permanent-entered)
                   (gen-stack-item-storm)
                   (gen-stack-item-declare-attackers)
                   (gen-stack-item-declare-blockers)
                   (gen-stack-item-combat-damage)
                   (gen-stack-item-delayed-trigger)
                   (gen-stack-item-state-check-trigger)
                   (gen-stack-item-permanent-tapped)
                   (gen-stack-item-creature-attacked)
                   (gen-stack-item-land-entered)
                   (gen-stack-item-etb)
                   (gen-stack-item-triggered-ability)
                   (gen-stack-item-phase-entered)
                   (gen-stack-item-test)])))


;; =====================================================
;; Selection generators (~31 types)
;; =====================================================

(def gen-uuid-set
  "Set of 0–3 UUIDs."
  (tgen/fmap set (tgen/vector gen-uuid 0 3)))


(def gen-uuid-or-kw-set
  "Set of 0–3 uuid-or-keyword values."
  (tgen/fmap set (tgen/vector gen-object-id 0 3)))


(def gen-object-vec
  (tgen/vector gen-object-id 0 3))


(def gen-candidates-set
  "Candidates set — UUIDs or keyword object IDs."
  gen-uuid-or-kw-set)


;; --- Group 1: :pick-from-zone ---

(defn gen-selection-discard
  []
  (tgen/hash-map :selection/mechanism (tgen/return :pick-from-zone)
                 :selection/domain (tgen/return :discard)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/validation gen-validation-kw
                 :selection/auto-confirm? (tgen/return false)))


(defn gen-selection-graveyard-return
  []
  (tgen/hash-map :selection/mechanism (tgen/return :pick-from-zone)
                 :selection/domain (tgen/return :graveyard-return)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/validation gen-validation-kw
                 :selection/auto-confirm? (tgen/return false)
                 :selection/candidate-ids gen-candidates-set))


(defn gen-selection-hand-reveal-discard
  []
  (tgen/hash-map :selection/mechanism (tgen/return :pick-from-zone)
                 :selection/domain (tgen/return :revealed-hand-discard)
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/select-count (tgen/choose 1 3)
                 :selection/validation gen-validation-kw
                 :selection/auto-confirm? (tgen/return false)
                 :selection/valid-targets gen-object-vec))


(defn gen-selection-chain-bounce
  []
  (tgen/hash-map :selection/mechanism (tgen/return :pick-from-zone)
                 :selection/domain (tgen/return :chain-bounce)
                 :selection/lifecycle (tgen/return :chaining)
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/validation gen-validation-kw
                 :selection/auto-confirm? (tgen/return false)
                 :selection/valid-targets gen-object-vec))


(defn gen-selection-chain-bounce-target
  []
  (tgen/hash-map :selection/mechanism (tgen/return :pick-from-zone)
                 :selection/domain (tgen/return :chain-bounce-target)
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/validation gen-validation-kw
                 :selection/auto-confirm? (tgen/return false)
                 :selection/valid-targets gen-object-vec))


(defn gen-selection-tutor
  []
  (tgen/hash-map :selection/mechanism (tgen/return :pick-from-zone)
                 :selection/domain (tgen/return :tutor)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/validation gen-validation-kw
                 :selection/auto-confirm? (tgen/return true)
                 :selection/target-zone gen-zone-kw
                 :selection/candidates gen-candidates-set))


(defn gen-selection-pile-choice
  []
  (tgen/hash-map :selection/mechanism (tgen/return :pick-from-zone)
                 :selection/domain (tgen/return :pile-choice)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/validation gen-validation-kw
                 :selection/auto-confirm? (tgen/return false)
                 :selection/candidates gen-candidates-set
                 :selection/hand-count (tgen/choose 0 7)))


(defn gen-selection-discard-specific-cost
  []
  (tgen/hash-map :selection/mechanism (tgen/return :pick-from-zone)
                 :selection/domain (tgen/return :discard-cost)
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/select-count (tgen/choose 1 3)
                 :selection/validation gen-validation-kw
                 :selection/auto-confirm? (tgen/return false)
                 :selection/mode gen-mode-map))


(defn gen-selection-return-land-cost
  []
  (tgen/hash-map :selection/mechanism (tgen/return :pick-from-zone)
                 :selection/domain (tgen/return :return-land-cost)
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/select-count (tgen/return 1)
                 :selection/valid-targets gen-object-vec
                 :selection/validation (tgen/return :exact)
                 :selection/auto-confirm? (tgen/return true)
                 :selection/mode gen-mode-map))


(defn gen-selection-sacrifice-permanent-cost
  []
  (tgen/hash-map :selection/mechanism (tgen/return :pick-from-zone)
                 :selection/domain (tgen/return :sacrifice-cost)
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/select-count (tgen/return 1)
                 :selection/valid-targets gen-object-vec
                 :selection/validation (tgen/return :exact)
                 :selection/auto-confirm? (tgen/return false)))


(defn gen-selection-exile-cards-cost
  []
  (tgen/hash-map :selection/mechanism (tgen/return :pick-from-zone)
                 :selection/domain (tgen/return :exile-cost)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/select-count (tgen/choose 1 5)
                 :selection/validation gen-validation-kw
                 :selection/auto-confirm? (tgen/return false)
                 :selection/candidates gen-candidates-set
                 :selection/mode gen-mode-map))


(defn gen-selection-untap-lands
  []
  (tgen/hash-map :selection/mechanism (tgen/return :pick-from-zone)
                 :selection/domain (tgen/return :untap-lands)
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/select-count (tgen/choose 1 5)
                 :selection/validation gen-validation-kw
                 :selection/auto-confirm? (tgen/return false)
                 :selection/candidates gen-candidates-set
                 :selection/candidate-ids gen-candidates-set))


;; --- Group 2: :reorder ---

(defn gen-selection-scry
  []
  (tgen/hash-map :selection/mechanism (tgen/return :reorder)
                 :selection/domain (tgen/return :scry)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/validation (tgen/return :always)
                 :selection/auto-confirm? (tgen/return false)
                 :selection/cards gen-object-vec))


(defn gen-selection-peek-and-reorder
  []
  (tgen/hash-map :selection/mechanism (tgen/return :reorder)
                 :selection/domain (tgen/return :peek-and-reorder)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/validation (tgen/return :always)
                 :selection/auto-confirm? (tgen/return false)
                 :selection/candidates gen-candidates-set
                 :selection/ordered (tgen/return [])))


(defn gen-selection-order-bottom
  []
  (tgen/hash-map :selection/mechanism (tgen/return :reorder)
                 :selection/domain (tgen/return :order-bottom)
                 :selection/player-id gen-player-id
                 :selection/validation (tgen/return :always)
                 :selection/auto-confirm? (tgen/return false)
                 :selection/candidates gen-candidates-set
                 :selection/ordered (tgen/return [])))


(defn gen-selection-order-top
  []
  (tgen/hash-map :selection/mechanism (tgen/return :reorder)
                 :selection/domain (tgen/return :order-top)
                 :selection/player-id gen-player-id
                 :selection/validation (tgen/return :always)
                 :selection/auto-confirm? (tgen/return false)
                 :selection/candidates gen-candidates-set
                 :selection/ordered (tgen/return [])))


(defn gen-selection-peek-and-select
  []
  (tgen/hash-map :selection/mechanism (tgen/return :reorder)
                 :selection/domain (tgen/return :peek-and-select)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/validation gen-validation-kw
                 :selection/auto-confirm? (tgen/return false)
                 :selection/candidates gen-candidates-set
                 :selection/selected-zone gen-zone-kw
                 :selection/remainder-zone gen-zone-kw))


;; --- Group 3: :accumulate ---

(defn gen-selection-storm-split
  []
  (tgen/hash-map :selection/mechanism (tgen/return :accumulate)
                 :selection/domain (tgen/return :storm-split)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/validation (tgen/return :always)
                 :selection/auto-confirm? (tgen/return false)
                 :selection/copy-count (tgen/choose 1 5)
                 :selection/valid-targets (tgen/return [:player-1 :player-2])
                 :selection/allocation (tgen/return {:player-1 0 :player-2 0})))


(defn gen-selection-x-mana-cost
  []
  (tgen/hash-map :selection/mechanism (tgen/return :accumulate)
                 :selection/domain (tgen/return :x-mana-cost)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/validation (tgen/return :always)
                 :selection/auto-confirm? (tgen/return false)
                 :selection/max-x (tgen/choose 1 10)
                 :selection/selected-x (tgen/return 0)))


(defn gen-selection-pay-x-life
  []
  (tgen/hash-map :selection/mechanism (tgen/return :accumulate)
                 :selection/domain (tgen/return :pay-x-life)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/validation (tgen/return :always)
                 :selection/auto-confirm? (tgen/return false)
                 :selection/max-x (tgen/choose 1 20)
                 :selection/selected-x (tgen/return 0)))


;; --- Group 4: :allocate-resource ---

(defn gen-selection-mana-allocation
  []
  (tgen/hash-map :selection/mechanism (tgen/return :allocate-resource)
                 :selection/domain (tgen/return :mana-allocation)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/validation (tgen/return :always)
                 :selection/auto-confirm? (tgen/return true)
                 :selection/generic-remaining (tgen/choose 0 5)
                 :selection/generic-total (tgen/choose 0 5)
                 :selection/allocation (tgen/return {})))


;; --- Group 5: :n-slot-targeting ---

(defn gen-selection-player-target
  []
  (tgen/hash-map :selection/mechanism (tgen/return :n-slot-targeting)
                 :selection/domain (tgen/return :player-target)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/select-count (tgen/return 1)
                 :selection/valid-targets (tgen/return #{:player-1 :player-2})
                 :selection/validation (tgen/return :exact)
                 :selection/auto-confirm? (tgen/return true)))


(defn gen-selection-cast-time-targeting
  []
  (tgen/hash-map :selection/mechanism (tgen/return :n-slot-targeting)
                 :selection/domain (tgen/return :cast-time-targeting)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/select-count (tgen/return 1)
                 :selection/valid-targets gen-object-vec
                 :selection/validation (tgen/return :exact)
                 :selection/auto-confirm? (tgen/return true)
                 :selection/object-id gen-object-id
                 :selection/mode gen-mode-map
                 :selection/target-requirement gen-target-requirement))


(defn gen-selection-ability-cast-targeting
  []
  (tgen/hash-map :selection/mechanism (tgen/return :n-slot-targeting)
                 :selection/domain (tgen/return :ability-cast-targeting)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/select-count (tgen/return 1)
                 :selection/valid-targets gen-object-vec
                 :selection/validation (tgen/return :exact)
                 :selection/auto-confirm? (tgen/return true)
                 :selection/object-id gen-object-id
                 :selection/mode gen-mode-map
                 :selection/target-requirement gen-target-requirement))


(defn gen-selection-ability-targeting
  []
  (tgen/hash-map :selection/mechanism (tgen/return :n-slot-targeting)
                 :selection/domain (tgen/return :ability-targeting)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/select-count (tgen/return 1)
                 :selection/valid-targets gen-object-vec
                 :selection/validation (tgen/return :exact)
                 :selection/auto-confirm? (tgen/return true)
                 :selection/object-id gen-object-id
                 :selection/ability-index (tgen/choose 0 3)
                 :selection/target-requirement gen-target-requirement
                 :selection/chosen-targets (tgen/return {})
                 :selection/remaining-target-reqs (tgen/return [])))


(defn gen-selection-select-attackers
  []
  (tgen/hash-map :selection/mechanism (tgen/return :n-slot-targeting)
                 :selection/domain (tgen/return :select-attackers)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/validation gen-validation-kw
                 :selection/auto-confirm? (tgen/return false)
                 :selection/stack-item-eid (tgen/choose 1 9999)
                 :selection/valid-targets gen-object-vec
                 :selection/select-count (tgen/choose 0 3)))


(defn gen-selection-assign-blockers
  []
  (tgen/hash-map :selection/mechanism (tgen/return :n-slot-targeting)
                 :selection/domain (tgen/return :assign-blockers)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/validation gen-validation-kw
                 :selection/auto-confirm? (tgen/return false)
                 :selection/stack-item-eid (tgen/choose 1 9999)
                 :selection/valid-targets gen-object-vec
                 :selection/select-count (tgen/choose 0 3)
                 :selection/current-attacker gen-object-id
                 :selection/remaining-attackers (tgen/return [])))


;; --- Group 6: :pick-mode ---

(defn gen-selection-spell-mode
  []
  (tgen/hash-map :selection/mechanism (tgen/return :pick-mode)
                 :selection/domain (tgen/return :spell-mode)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/select-count (tgen/return 1)
                 :selection/validation (tgen/return :exact)
                 :selection/auto-confirm? (tgen/return true)
                 :selection/object-id gen-object-id
                 :selection/candidates (tgen/return [{:mode/id :primary}])))


(defn gen-selection-land-type-source
  []
  (tgen/hash-map :selection/mechanism (tgen/return :pick-mode)
                 :selection/domain (tgen/return :land-type-source)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return nil)
                 :selection/select-count (tgen/return 1)
                 :selection/validation (tgen/return :exact)))


(defn gen-selection-land-type-target
  []
  (tgen/hash-map :selection/mechanism (tgen/return :pick-mode)
                 :selection/domain (tgen/return :land-type-target)
                 :selection/lifecycle gen-lifecycle
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return nil)
                 :selection/select-count (tgen/return 1)
                 :selection/validation (tgen/return :exact)))


;; --- Group 7: :binary-choice ---

(defn gen-selection-unless-pay
  []
  (tgen/hash-map :selection/mechanism (tgen/return :binary-choice)
                 :selection/domain (tgen/return :unless-pay)
                 :selection/player-id gen-player-id
                 :selection/selected (tgen/return #{})
                 :selection/select-count (tgen/return 1)
                 :selection/valid-targets (tgen/return [:pay :decline])
                 :selection/validation (tgen/return :exact)
                 :selection/auto-confirm? (tgen/return true)))


;; Register generator on ::selection multi-spec
(s/def :fizzle.events.selection.spec/selection
  (s/with-gen
    (s/multi-spec sel-spec/selection-type-spec :selection/mechanism)
    #(tgen/one-of [(gen-selection-discard)
                   (gen-selection-graveyard-return)
                   (gen-selection-hand-reveal-discard)
                   (gen-selection-chain-bounce)
                   (gen-selection-chain-bounce-target)
                   (gen-selection-unless-pay)
                   (gen-selection-storm-split)
                   (gen-selection-x-mana-cost)
                   (gen-selection-mana-allocation)
                   (gen-selection-pay-x-life)
                   (gen-selection-scry)
                   (gen-selection-peek-and-reorder)
                   (gen-selection-order-bottom)
                   (gen-selection-order-top)
                   (gen-selection-tutor)
                   (gen-selection-peek-and-select)
                   (gen-selection-pile-choice)
                   (gen-selection-player-target)
                   (gen-selection-cast-time-targeting)
                   (gen-selection-ability-cast-targeting)
                   (gen-selection-ability-targeting)
                   (gen-selection-discard-specific-cost)
                   (gen-selection-return-land-cost)
                   (gen-selection-sacrifice-permanent-cost)
                   (gen-selection-exile-cards-cost)
                   (gen-selection-land-type-source)
                   (gen-selection-land-type-target)
                   (gen-selection-select-attackers)
                   (gen-selection-assign-blockers)
                   (gen-selection-spell-mode)
                   (gen-selection-untap-lands)])))


;; =====================================================
;; Bot action generators (3 types)
;; UNNAMESPACED keys — :req-un/:opt-un in spec
;; =====================================================

(def gen-tap-entry
  (tgen/hash-map :object-id gen-uuid
                 :mana-color gen-mana-color))


(def gen-tap-sequence
  (tgen/vector gen-tap-entry 0 4))


(defn gen-bot-action-pass
  []
  (tgen/return {:action :pass}))


(defn gen-bot-action-cast-spell
  []
  (tgen/hash-map :action (tgen/return :cast-spell)
                 :object-id gen-uuid
                 :player-id gen-player-id
                 :tap-sequence gen-tap-sequence))


(defn gen-bot-action-play-land
  []
  (tgen/return {:action :play-land}))


;; Register generator on ::bot-action multi-spec
(s/def :fizzle.bots.action-spec/bot-action
  (s/with-gen
    (s/multi-spec action-spec/action-type-spec :action)
    #(tgen/one-of [(gen-bot-action-pass)
                   (gen-bot-action-cast-spell)
                   (gen-bot-action-play-land)])))


;; =====================================================
;; Boundary spec generators (mana + object creation)
;; =====================================================

(defn gen-mana-add-arg
  "Generator for add-mana argument: partial mana map with pool color keys only."
  []
  gen-mana-map)


(defn gen-mana-pay-arg
  "Generator for pay-mana cost argument: partial mana map, may include :x."
  []
  (tgen/fmap
    (fn [pairs] (into {} pairs))
    (tgen/vector
      (tgen/tuple
        (tgen/one-of [gen-mana-color (tgen/return :x)])
        (tgen/choose 0 5))
      0 4)))


(defn gen-object-tx
  "Generator for object transaction maps produced by restorer/object-tx-for-zone."
  []
  (tgen/fmap
    (fn [[base maybe-creature]]
      (merge base maybe-creature))
    (tgen/tuple
      (tgen/hash-map
        :object/id         gen-uuid
        :object/card       (tgen/choose 1 1000)
        :object/zone       (tgen/elements [:hand :library :graveyard :exile :battlefield])
        :object/owner      (tgen/choose 1 100)
        :object/controller (tgen/choose 1 100)
        :object/tapped     tgen/boolean
        :object/position   (tgen/choose 0 100))
      (tgen/one-of
        [(tgen/return {})
         (tgen/hash-map
           :object/power          (tgen/choose 0 20)
           :object/toughness      (tgen/choose 0 20)
           :object/summoning-sick tgen/boolean
           :object/damage-marked  (tgen/choose 0 10))]))))


;; Note: boundary specs (mana-add-arg, mana-pay-arg, object-tx) are plain s/keys specs,
;; not multi-specs. Use gen-mana-add-arg, gen-mana-pay-arg, gen-object-tx directly
;; in property tests rather than registering s/with-gen on them.
