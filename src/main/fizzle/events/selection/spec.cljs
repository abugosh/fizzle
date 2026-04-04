(ns fizzle.events.selection.spec
  "Selection map validation using cljs.spec.alpha.

   Validates selection maps at the :game/pending-selection chokepoint.
   set-pending-selection is the sole writer — all callers use it.

   Multi-spec dispatches on :selection/type. One defmethod per concrete
   type (no hierarchy reuse — per Phase 1 pattern in engine/card-spec.cljs).

   Validation is dev-only via goog.DEBUG (dead-code eliminated in release).
   set-pending-selection uses validate-at-chokepoint! — throws when
   *throw-on-spec-failure* is bound true (tests), console.error otherwise."
  (:require
    [cljs.spec.alpha :as s]
    [fizzle.engine.spec-common]
    [fizzle.engine.spec-util :as spec-util]))


;; =====================================================
;; Base Field Specs
;; =====================================================

(s/def :selection/type keyword?)
(s/def :selection/lifecycle #{:standard :finalized :chaining})
(s/def :selection/validation #{:exact :at-most :at-least-one :exact-or-zero :always})
(s/def :selection/player-id :game/player-id)


;; :selection/selected is type-dependent (set, vec, map, integer, nil).
;; Constrained globally only to non-function (no per-type restriction here).
(s/def :selection/selected
  (s/nilable (s/or :set set?
                   :vec vector?
                   :map map?
                   :int int?
                   :keyword keyword?)))


;; Optional base keys (present on many but not all types)
(s/def :selection/auto-confirm? boolean?)
(s/def :selection/select-count int?)
(s/def :selection/spell-id (s/or :kw keyword? :uuid uuid?))
(s/def :selection/remaining-effects vector?)
(s/def :selection/valid-targets (s/or :set set? :vec vector?))
(s/def :selection/candidate-ids (s/or :set set? :vec vector?))
(s/def :selection/candidates (s/or :set set? :vec vector?))
(s/def :selection/zone keyword?)
(s/def :selection/card-source keyword?)
(s/def :selection/target-zone keyword?)
(s/def :selection/lifecycle-declared? boolean?)
(s/def :selection/min-count int?)
(s/def :selection/exact? boolean?)
(s/def :selection/object-id (s/or :kw keyword? :uuid uuid?))
(s/def :selection/stack-item-eid int?)
(s/def :selection/source-type keyword?)


;; =====================================================
;; Selection Multi-Spec
;; =====================================================

(defmulti selection-type-spec :selection/type)


;; =====================================================
;; Group 1: Zone-Pick Types
;; Built by generic zone-pick builder in core.cljs via hierarchy,
;; or by custom builders. Common keys: type, lifecycle, player-id, selected,
;; validation, auto-confirm?. Zone-pick types with :card-source :zone
;; always have :selection/candidate-ids.
;; =====================================================

;; :discard — built by generic zone-pick builder (:discard derives :zone-pick)
;; card-source is :hand (default). No candidate-ids (hand-based, not pre-enumerated).
(defmethod selection-type-spec :discard [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/selected
                :selection/validation
                :selection/auto-confirm?]
          :opt [:selection/zone
                :selection/card-source
                :selection/target-zone
                :selection/select-count
                :selection/spell-id
                :selection/remaining-effects
                :selection/pattern
                :selection/cleanup?]))


;; :graveyard-return — built by zone-pick builder with :return-from-graveyard config.
;; card-source :zone → always has :selection/candidate-ids.
(defmethod selection-type-spec :graveyard-return [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/selected
                :selection/validation
                :selection/auto-confirm?
                :selection/candidate-ids]
          :opt [:selection/zone
                :selection/card-source
                :selection/target-zone
                :selection/select-count
                :selection/spell-id
                :selection/remaining-effects
                :selection/pattern
                :selection/min-count]))


;; :hand-reveal-discard — built by build-hand-reveal-discard-selection in zone_ops.cljs.
;; Shows opponent's hand. Always has :selection/valid-targets and :selection/target-player.
(defmethod selection-type-spec :hand-reveal-discard [_]
  (s/keys :req [:selection/type
                :selection/player-id
                :selection/selected
                :selection/select-count
                :selection/validation
                :selection/auto-confirm?
                :selection/valid-targets]
          :opt [:selection/card-source
                :selection/target-player
                :selection/spell-id
                :selection/remaining-effects
                :selection/lifecycle]))


;; :chain-bounce — built by build-chain-bounce-selection in zone_ops.cljs.
;; Always :chaining lifecycle. Has :selection/chain-controller and :selection/chain-target-id.
(defmethod selection-type-spec :chain-bounce [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/selected
                :selection/validation
                :selection/auto-confirm?
                :selection/valid-targets]
          :opt [:selection/zone
                :selection/card-source
                :selection/select-count
                :selection/spell-id
                :selection/remaining-effects
                :selection/chain-controller
                :selection/chain-target-id]))


;; :chain-bounce-target — built by build-chain-bounce-target-selection in zone_ops.cljs.
;; Has :selection/chain-copy-object-id and :selection/chain-copy-stack-item-eid.
(defmethod selection-type-spec :chain-bounce-target [_]
  (s/keys :req [:selection/type
                :selection/player-id
                :selection/selected
                :selection/validation
                :selection/auto-confirm?
                :selection/valid-targets]
          :opt [:selection/zone
                :selection/card-source
                :selection/select-count
                :selection/spell-id
                :selection/chain-copy-object-id
                :selection/chain-copy-stack-item-eid
                :selection/lifecycle]))


;; :unless-pay — built by build-unless-pay-selection in zone_ops.cljs.
;; Used for soft counters (Mana Leak, Daze). Has :selection/counter-target-id
;; and :selection/unless-pay-cost.
(defmethod selection-type-spec :unless-pay [_]
  (s/keys :req [:selection/type
                :selection/player-id
                :selection/selected
                :selection/select-count
                :selection/valid-targets
                :selection/validation
                :selection/auto-confirm?]
          :opt [:selection/spell-id
                :selection/remaining-effects
                :selection/counter-target-id
                :selection/unless-pay-cost
                :selection/lifecycle]))


;; =====================================================
;; Group 2: Accumulator Types
;; Non-card-picking selections using stepper controls.
;; =====================================================

;; :storm-split — built by build-storm-split-selection in storm.cljs.
;; Has :selection/copy-count, :selection/allocation, :selection/source-object-id.
(defmethod selection-type-spec :storm-split [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/selected
                :selection/validation
                :selection/auto-confirm?
                :selection/copy-count
                :selection/valid-targets
                :selection/allocation]
          :opt [:selection/pattern
                :selection/source-object-id
                :selection/controller-id
                :selection/stack-item-eid]))


;; :x-mana-cost — built by build-x-mana-selection in costs.cljs.
;; Has :selection/max-x, :selection/selected-x, :selection/mode.
(defmethod selection-type-spec :x-mana-cost [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/validation
                :selection/auto-confirm?
                :selection/max-x
                :selection/selected-x]
          :opt [:selection/zone
                :selection/spell-id
                :selection/mode
                :selection/pattern
                :selection/chain-builder
                :selection/selected]))


;; :mana-allocation — built by build-mana-allocation-selection in costs.cljs.
;; Has :selection/generic-remaining, :selection/generic-total, :selection/allocation.
(defmethod selection-type-spec :mana-allocation [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/validation
                :selection/auto-confirm?
                :selection/generic-remaining
                :selection/generic-total
                :selection/allocation]
          :opt [:selection/zone
                :selection/spell-id
                :selection/mode
                :selection/pattern
                :selection/remaining-pool
                :selection/original-remaining-pool
                :selection/colored-cost
                :selection/original-cost
                :selection/clear-selected-card?
                :selection/pending-targets
                :selection/source-type
                :selection/ability
                :selection/selected]))


;; :pay-x-life — built by build-pay-x-life-selection in costs.cljs.
;; Has :selection/max-x, :selection/selected-x.
(defmethod selection-type-spec :pay-x-life [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/validation
                :selection/auto-confirm?
                :selection/max-x
                :selection/selected-x]
          :opt [:selection/zone
                :selection/spell-id
                :selection/mode
                :selection/clear-selected-card?
                :selection/selected]))


;; =====================================================
;; Group 3: Reorder Types
;; Sorting/assigning cards into piles or ordered lists.
;; =====================================================

;; :scry — built by build-scry-selection in library.cljs.
;; Has :selection/cards, :selection/top-pile, :selection/bottom-pile.
(defmethod selection-type-spec :scry [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/validation
                :selection/auto-confirm?
                :selection/cards]
          :opt [:selection/pattern
                :selection/top-pile
                :selection/bottom-pile
                :selection/spell-id
                :selection/remaining-effects
                :selection/selected]))


;; :peek-and-reorder — built by build-peek-and-reorder-selection in library.cljs.
;; Has :selection/candidates, :selection/ordered, :selection/target-player.
(defmethod selection-type-spec :peek-and-reorder [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/validation
                :selection/auto-confirm?
                :selection/candidates
                :selection/ordered]
          :opt [:selection/pattern
                :selection/target-player
                :selection/spell-id
                :selection/remaining-effects
                :selection/may-shuffle?
                :selection/selected]))


;; :order-bottom — built by build-order-bottom-selection in library.cljs.
;; Has :selection/candidates, :selection/ordered.
(defmethod selection-type-spec :order-bottom [_]
  (s/keys :req [:selection/type
                :selection/player-id
                :selection/validation
                :selection/auto-confirm?
                :selection/candidates
                :selection/ordered]
          :opt [:selection/spell-id
                :selection/remaining-effects
                :selection/lifecycle
                :selection/selected]))


;; :order-top — built by build-order-top-selection in library.cljs.
;; Has :selection/candidates, :selection/ordered.
(defmethod selection-type-spec :order-top [_]
  (s/keys :req [:selection/type
                :selection/player-id
                :selection/validation
                :selection/auto-confirm?
                :selection/candidates
                :selection/ordered]
          :opt [:selection/pattern
                :selection/spell-id
                :selection/remaining-effects
                :selection/lifecycle
                :selection/selected]))


;; =====================================================
;; Group 4: Library Operation Types
;; =====================================================

;; :tutor — built by build-tutor-selection in library.cljs.
;; Has :selection/target-zone, :selection/candidates (set of candidate-ids).
(defmethod selection-type-spec :tutor [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/selected
                :selection/validation
                :selection/auto-confirm?
                :selection/target-zone
                :selection/candidates]
          :opt [:selection/zone
                :selection/card-source
                :selection/select-count
                :selection/exact?
                :selection/allow-fail-to-find?
                :selection/shuffle?
                :selection/enters-tapped
                :selection/pile-choice
                :selection/spell-id
                :selection/remaining-effects
                :selection/chain-builder]))


;; :peek-and-select — built by build-peek-selection in library.cljs.
;; Has :selection/candidates (set), :selection/selected-zone, :selection/remainder-zone.
(defmethod selection-type-spec :peek-and-select [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/selected
                :selection/validation
                :selection/auto-confirm?
                :selection/candidates
                :selection/selected-zone
                :selection/remainder-zone]
          :opt [:selection/zone
                :selection/card-source
                :selection/select-count
                :selection/exact?
                :selection/allow-fail-to-find?
                :selection/spell-id
                :selection/remaining-effects
                :selection/shuffle-remainder?
                :selection/order-remainder?
                :selection/chain-builder]))


;; :pile-choice — built by build-pile-choice-selection in library.cljs.
;; Has :selection/candidates, :selection/hand-count, :selection/allow-random.
(defmethod selection-type-spec :pile-choice [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/selected
                :selection/validation
                :selection/auto-confirm?
                :selection/candidates
                :selection/hand-count]
          :opt [:selection/card-source
                :selection/select-count
                :selection/allow-random
                :selection/spell-id
                :selection/remaining-effects]))


;; =====================================================
;; Group 5: Targeting Types
;; =====================================================

;; :player-target — built by build-player-target-selection in targeting.cljs.
;; Has :selection/valid-targets (set of player-ids), :selection/target-effect.
(defmethod selection-type-spec :player-target [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/selected
                :selection/select-count
                :selection/valid-targets
                :selection/validation
                :selection/auto-confirm?]
          :opt [:selection/spell-id
                :selection/target-effect
                :selection/remaining-effects]))


;; :cast-time-targeting — built by build-cast-time-target-selection in targeting.cljs.
;; Has :selection/object-id, :selection/mode, :selection/target-requirement, :selection/valid-targets.
(defmethod selection-type-spec :cast-time-targeting [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/selected
                :selection/select-count
                :selection/valid-targets
                :selection/validation
                :selection/auto-confirm?
                :selection/object-id
                :selection/mode
                :selection/target-requirement]
          :opt [:selection/card-source
                :selection/clear-selected-card?
                :selection/pending-targets]))


;; :ability-cast-targeting — same shape as :cast-time-targeting (same builder path).
(defmethod selection-type-spec :ability-cast-targeting [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/selected
                :selection/select-count
                :selection/valid-targets
                :selection/validation
                :selection/auto-confirm?
                :selection/object-id
                :selection/mode
                :selection/target-requirement]
          :opt [:selection/card-source
                :selection/clear-selected-card?
                :selection/pending-targets]))


;; :ability-targeting — built by build-ability-target-selection in abilities.cljs.
;; Has :selection/object-id, :selection/ability-index, :selection/target-requirement,
;; :selection/chosen-targets, :selection/remaining-target-reqs.
(defmethod selection-type-spec :ability-targeting [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/selected
                :selection/select-count
                :selection/valid-targets
                :selection/validation
                :selection/auto-confirm?
                :selection/object-id
                :selection/ability-index
                :selection/target-requirement
                :selection/chosen-targets
                :selection/remaining-target-reqs]
          :opt [:selection/card-source]))


;; =====================================================
;; Group 6: Pre-Cast Cost Types
;; =====================================================

;; :discard-specific-cost — built by build-discard-specific-selection in costs.cljs.
;; Has :selection/mode, :selection/discard-cost, :selection/discard-groups.
(defmethod selection-type-spec :discard-specific-cost [_]
  (s/keys :req [:selection/type
                :selection/player-id
                :selection/selected
                :selection/select-count
                :selection/validation
                :selection/auto-confirm?
                :selection/mode]
          :opt [:selection/zone
                :selection/card-source
                :selection/spell-id
                :selection/discard-cost
                :selection/discard-groups
                :selection/valid-targets
                :selection/candidate-card-map
                :selection/lifecycle
                :selection/clear-selected-card?]))


;; :return-land-cost — built by build-return-land-selection in costs.cljs.
;; Has :selection/mode, :selection/return-cost.
(defmethod selection-type-spec :return-land-cost [_]
  (s/keys :req [:selection/type
                :selection/player-id
                :selection/selected
                :selection/select-count
                :selection/valid-targets
                :selection/validation
                :selection/auto-confirm?
                :selection/mode]
          :opt [:selection/zone
                :selection/card-source
                :selection/spell-id
                :selection/return-cost
                :selection/lifecycle
                :selection/clear-selected-card?]))


;; :sacrifice-permanent-cost — built by build-sacrifice-permanent-selection in costs.cljs.
;; Has :selection/mode (nil for ability path), :selection/sac-cost.
(defmethod selection-type-spec :sacrifice-permanent-cost [_]
  (s/keys :req [:selection/type
                :selection/player-id
                :selection/selected
                :selection/select-count
                :selection/valid-targets
                :selection/validation
                :selection/auto-confirm?]
          :opt [:selection/zone
                :selection/card-source
                :selection/spell-id
                :selection/mode
                :selection/sac-cost
                :selection/source-type
                :selection/ability
                :selection/ability-index
                :selection/lifecycle
                :selection/clear-selected-card?]))


;; :exile-cards-cost — built by build-exile-cards-selection in costs.cljs.
;; Has :selection/candidates, :selection/mode, :selection/exile-cost.
(defmethod selection-type-spec :exile-cards-cost [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/selected
                :selection/select-count
                :selection/validation
                :selection/auto-confirm?
                :selection/candidates
                :selection/mode]
          :opt [:selection/zone
                :selection/card-source
                :selection/spell-id
                :selection/exact?
                :selection/allow-fail-to-find?
                :selection/exile-cost
                :selection/clear-selected-card?]))


;; =====================================================
;; Group 7: Land Type Types
;; =====================================================

;; :land-type-source — built by build-selection-for-effect :change-land-types in land_types.cljs.
;; Has :selection/options (the 5 basic land type keys).
(defmethod selection-type-spec :land-type-source [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/selected
                :selection/select-count
                :selection/validation]
          :opt [:selection/exact?
                :selection/options
                :selection/spell-id
                :selection/remaining-effects
                :selection/auto-confirm?]))


;; :land-type-target — built by build-chain-selection :land-type-source in land_types.cljs.
;; Has :selection/options (excluding source type), :selection/source-type.
(defmethod selection-type-spec :land-type-target [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/selected
                :selection/select-count
                :selection/validation]
          :opt [:selection/exact?
                :selection/options
                :selection/source-type
                :selection/spell-id
                :selection/remaining-effects
                :selection/auto-confirm?]))


;; =====================================================
;; Group 8: Combat Types
;; =====================================================

;; :select-attackers — built by build-attacker-selection in combat.cljs.
;; Has :selection/stack-item-eid (cleanup source), :selection/valid-targets.
(defmethod selection-type-spec :select-attackers [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/selected
                :selection/validation
                :selection/auto-confirm?
                :selection/stack-item-eid
                :selection/valid-targets]
          :opt [:selection/card-source
                :selection/select-count
                :selection/source-type]))


;; :assign-blockers — built by build-blocker-selection in combat.cljs.
;; Has :selection/stack-item-eid, :selection/current-attacker, :selection/remaining-attackers.
(defmethod selection-type-spec :assign-blockers [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/selected
                :selection/validation
                :selection/auto-confirm?
                :selection/stack-item-eid
                :selection/valid-targets
                :selection/current-attacker
                :selection/remaining-attackers]
          :opt [:selection/card-source
                :selection/select-count
                :selection/source-type]))


;; =====================================================
;; Group 9: Other Types
;; =====================================================

;; :spell-mode — built by build-spell-mode-selection in casting.cljs.
;; Has :selection/object-id, :selection/candidates (valid modes).
(defmethod selection-type-spec :spell-mode [_]
  (s/keys :req [:selection/type
                :selection/lifecycle
                :selection/player-id
                :selection/selected
                :selection/select-count
                :selection/validation
                :selection/auto-confirm?
                :selection/object-id
                :selection/candidates]
          :opt [:selection/on-complete]))


;; :untap-lands — built by build-untap-lands-selection in untap.cljs.
;; Has :selection/candidates, :selection/candidate-ids (tapped lands).
(defmethod selection-type-spec :untap-lands [_]
  (s/keys :req [:selection/type
                :selection/player-id
                :selection/selected
                :selection/select-count
                :selection/validation
                :selection/auto-confirm?
                :selection/candidates
                :selection/candidate-ids]
          :opt [:selection/zone
                :selection/card-source
                :selection/spell-id
                :selection/remaining-effects
                :selection/min-count
                :selection/lifecycle]))


;; =====================================================
;; ::selection Multi-Spec
;; =====================================================

(s/def ::selection (s/multi-spec selection-type-spec :selection/type))


;; =====================================================
;; minimal-valid-selections
;; One entry per type — used by tests and exercise checks.
;; All entries must pass (s/valid? ::selection).
;; =====================================================

(def minimal-valid-selections
  {:discard
   {:selection/type :discard
    :selection/lifecycle :standard
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :exact
    :selection/auto-confirm? false}

   :graveyard-return
   {:selection/type :graveyard-return
    :selection/lifecycle :standard
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :at-most
    :selection/auto-confirm? false
    :selection/candidate-ids #{}}

   :hand-reveal-discard
   {:selection/type :hand-reveal-discard
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 1
    :selection/validation :exact-or-zero
    :selection/auto-confirm? false
    :selection/valid-targets []}

   :chain-bounce
   {:selection/type :chain-bounce
    :selection/lifecycle :chaining
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :exact-or-zero
    :selection/auto-confirm? false
    :selection/valid-targets []}

   :chain-bounce-target
   {:selection/type :chain-bounce-target
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :exact-or-zero
    :selection/auto-confirm? false
    :selection/valid-targets []}

   :unless-pay
   {:selection/type :unless-pay
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 1
    :selection/valid-targets [:pay :decline]
    :selection/validation :exact
    :selection/auto-confirm? true}

   :storm-split
   {:selection/type :storm-split
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :always
    :selection/auto-confirm? false
    :selection/copy-count 3
    :selection/valid-targets [:player-2 :player-1]
    :selection/allocation {:player-2 3 :player-1 0}}

   :x-mana-cost
   {:selection/type :x-mana-cost
    :selection/lifecycle :chaining
    :selection/player-id :player-1
    :selection/validation :always
    :selection/auto-confirm? false
    :selection/max-x 5
    :selection/selected-x 0}

   :mana-allocation
   {:selection/type :mana-allocation
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/validation :always
    :selection/auto-confirm? true
    :selection/generic-remaining 2
    :selection/generic-total 2
    :selection/allocation {}}

   :pay-x-life
   {:selection/type :pay-x-life
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/validation :always
    :selection/auto-confirm? false
    :selection/max-x 10
    :selection/selected-x 0}

   :scry
   {:selection/type :scry
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/validation :always
    :selection/auto-confirm? false
    :selection/cards [:obj-1 :obj-2]}

   :peek-and-reorder
   {:selection/type :peek-and-reorder
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/validation :always
    :selection/auto-confirm? false
    :selection/candidates #{:obj-1 :obj-2}
    :selection/ordered []}

   :order-bottom
   {:selection/type :order-bottom
    :selection/player-id :player-1
    :selection/validation :always
    :selection/auto-confirm? false
    :selection/candidates #{:obj-1 :obj-2}
    :selection/ordered []}

   :order-top
   {:selection/type :order-top
    :selection/player-id :player-1
    :selection/validation :always
    :selection/auto-confirm? false
    :selection/candidates #{:obj-1 :obj-2}
    :selection/ordered []}

   :tutor
   {:selection/type :tutor
    :selection/lifecycle :chaining
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :exact-or-zero
    :selection/auto-confirm? true
    :selection/target-zone :hand
    :selection/candidates #{}}

   :peek-and-select
   {:selection/type :peek-and-select
    :selection/lifecycle :chaining
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :at-most
    :selection/auto-confirm? false
    :selection/candidates #{:obj-1 :obj-2}
    :selection/selected-zone :hand
    :selection/remainder-zone :bottom-of-library}

   :pile-choice
   {:selection/type :pile-choice
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :exact
    :selection/auto-confirm? false
    :selection/candidates #{:obj-1 :obj-2 :obj-3}
    :selection/hand-count 1}

   :player-target
   {:selection/type :player-target
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 1
    :selection/valid-targets #{:player-1 :player-2}
    :selection/validation :exact
    :selection/auto-confirm? true}

   :cast-time-targeting
   {:selection/type :cast-time-targeting
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 1
    :selection/valid-targets [:obj-1]
    :selection/validation :exact
    :selection/auto-confirm? true
    :selection/object-id :spell-id
    :selection/mode {:mode/id :primary :mode/mana-cost {:blue 1}}
    :selection/target-requirement {:target/id :target :target/type :object}}

   :ability-cast-targeting
   {:selection/type :ability-cast-targeting
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 1
    :selection/valid-targets [:obj-1]
    :selection/validation :exact
    :selection/auto-confirm? true
    :selection/object-id :spell-id
    :selection/mode {:mode/id :primary :mode/mana-cost {:blue 1}}
    :selection/target-requirement {:target/id :target :target/type :object}}

   :ability-targeting
   {:selection/type :ability-targeting
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 1
    :selection/valid-targets [:obj-1]
    :selection/validation :exact
    :selection/auto-confirm? true
    :selection/object-id :perm-id
    :selection/ability-index 0
    :selection/target-requirement {:target/id :target :target/type :object}
    :selection/chosen-targets {}
    :selection/remaining-target-reqs []}

   :discard-specific-cost
   {:selection/type :discard-specific-cost
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 2
    :selection/validation :exact
    :selection/auto-confirm? false
    :selection/mode {:mode/id :primary :mode/mana-cost {:blue 1}}}

   :return-land-cost
   {:selection/type :return-land-cost
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 1
    :selection/valid-targets [:land-id]
    :selection/validation :exact
    :selection/auto-confirm? true
    :selection/mode {:mode/id :primary :mode/mana-cost {:blue 1}}}

   :sacrifice-permanent-cost
   {:selection/type :sacrifice-permanent-cost
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 1
    :selection/valid-targets [:perm-id]
    :selection/validation :exact
    :selection/auto-confirm? false}

   :exile-cards-cost
   {:selection/type :exile-cards-cost
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 3
    :selection/validation :at-least-one
    :selection/auto-confirm? false
    :selection/candidates #{:obj-1}
    :selection/mode {:mode/id :primary :mode/mana-cost {:colorless 1}}}

   :land-type-source
   {:selection/type :land-type-source
    :selection/lifecycle :chaining
    :selection/player-id :player-1
    :selection/selected nil
    :selection/select-count 1
    :selection/validation :exact}

   :land-type-target
   {:selection/type :land-type-target
    :selection/lifecycle :standard
    :selection/player-id :player-1
    :selection/selected nil
    :selection/select-count 1
    :selection/validation :exact}

   :select-attackers
   {:selection/type :select-attackers
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :at-most
    :selection/auto-confirm? false
    :selection/stack-item-eid 42
    :selection/valid-targets [:creature-1]}

   :assign-blockers
   {:selection/type :assign-blockers
    :selection/lifecycle :chaining
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :at-most
    :selection/auto-confirm? false
    :selection/stack-item-eid 42
    :selection/valid-targets []
    :selection/current-attacker :attacker-1
    :selection/remaining-attackers []}

   :spell-mode
   {:selection/type :spell-mode
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 1
    :selection/validation :exact
    :selection/auto-confirm? true
    :selection/object-id :spell-id
    :selection/candidates [{:mode/id :primary}]}

   :untap-lands
   {:selection/type :untap-lands
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 2
    :selection/validation :at-most
    :selection/auto-confirm? false
    :selection/candidates #{:land-1}
    :selection/candidate-ids #{:land-1}}})


(defn minimal-valid-selection
  "Return a minimal valid selection map for the given :selection/type keyword.
   Used by tests to verify every type has a working defmethod."
  [selection-type]
  (get minimal-valid-selections selection-type))


;; =====================================================
;; set-pending-selection Helper
;; =====================================================

(defn set-pending-selection
  "Validated setter for :game/pending-selection.
   In dev (goog.DEBUG): validates selection via validate-at-chokepoint!.
   Throws when *throw-on-spec-failure* is true (tests), console.error otherwise.
   In prod: no validation (dead-code eliminated by Closure compiler).

   Pure function: (app-db, selection) -> app-db."
  [app-db selection]
  (spec-util/validate-at-chokepoint!
    ::selection selection "set-pending-selection")
  (assoc app-db :game/pending-selection selection))
