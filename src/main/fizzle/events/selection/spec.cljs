(ns fizzle.events.selection.spec
  "Selection map validation using cljs.spec.alpha.

   Validates selection maps at the :game/pending-selection chokepoint.
   set-pending-selection is the sole writer — all callers use it.

   Multi-spec dispatches on :selection/mechanism (ADR-030, fizzle-8650 phase 4).
   7 defmethods — one per mechanism — replace the previous 33 type-keyed defmethods. has been fully retired (ADR-030 complete).

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

(s/def :selection/lifecycle #{:standard :finalized :chaining})


;; ADR-030 mechanism/domain split. Set directly by all builders (ADR-030 complete).
;; :selection/mechanism is a bounded alphabet identifying the UI/interaction pattern.
;; :selection/domain is a free-form keyword tag identifying post-confirm policy.
;; :selection/mechanism is :req in all defmethods (dispatch key for multi-spec).
;; has been fully retired.
(s/def :selection/mechanism
  #{:pick-from-zone :reorder :accumulate :allocate-resource
    :n-slot-targeting :pick-mode :binary-choice})


(s/def :selection/domain keyword?)
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
(s/def :selection/caster-id :game/player-id)


;; =====================================================
;; Replacement-choice spec fields (placed here before multi-spec defmethods
;; that reference them; previously co-located with :replacement-choice defmethod)
;; =====================================================

(s/def :selection/choices (s/coll-of map?))
(s/def :selection/replacement-event map?)
(s/def :selection/replacement-entity-id int?)


;; Multi-slot cast-time targeting (fizzle-4xcm.4)
(s/def :selection/target-requirements (s/coll-of map? :kind vector?))
(s/def :selection/enforce-distinctness boolean?)


;; =====================================================
;; Selection Multi-Spec — mechanism-keyed (ADR-030, fizzle-8650 phase 4)
;;
;; Dispatches on :selection/mechanism (7 mechanisms).
;; :selection/mechanism is :req in all methods (dispatch key).
;; has been fully retired (ADR-030 complete).
;;
;; Each method validates the minimum fields common to all domains under
;; that mechanism plus a comprehensive :opt list covering all domain-specific
;; fields. Domain-specific validation is handled by apply-domain-policy.
;; =====================================================

(defmulti selection-type-spec :selection/mechanism)


;; :pick-from-zone — select N cards/objects from a zone.
;; Covers: discard, graveyard-return, shuffle-from-graveyard-to-library,
;;   hand-reveal-discard, chain-bounce, chain-bounce-target, untap-lands,
;;   discard-specific-cost, return-land-cost, sacrifice-permanent-cost,
;;   exile-cards-cost, tutor, peek-and-select, pile-choice.
(defmethod selection-type-spec :pick-from-zone [_]
  (s/keys :req [:selection/mechanism
                :selection/domain
                :selection/player-id
                :selection/selected
                :selection/validation
                :selection/auto-confirm?]
          :opt [:selection/lifecycle
                :selection/select-count
                :selection/valid-targets
                :selection/candidate-ids
                :selection/candidates
                :selection/zone
                :selection/card-source
                :selection/target-zone
                :selection/spell-id
                :selection/remaining-effects
                :selection/min-count
                :selection/caster-id
                :selection/chain-controller
                :selection/chain-target-id
                :selection/chain-copy-object-id
                :selection/chain-copy-stack-item-eid
                :selection/target-player
                :selection/exact?
                :selection/allow-fail-to-find?
                :selection/shuffle?
                :selection/enters-tapped
                :selection/pile-choice
                :selection/chain-builder
                :selection/selected-zone
                :selection/remainder-zone
                :selection/shuffle-remainder?
                :selection/order-remainder?
                :selection/hand-count
                :selection/allow-random
                :selection/mode
                :selection/discard-cost
                :selection/discard-groups
                :selection/candidate-card-map
                :selection/return-cost
                :selection/sac-cost
                :selection/source-type
                :selection/ability
                :selection/ability-index
                :selection/exile-cost
                :selection/counter-target-id
                :selection/unless-pay-cost
                :selection/pending-targets
                :selection/cleanup?
                :selection/stack-item-eid]))


;; :reorder — sort/assign cards into ordered positions.
;; Covers: scry, peek-and-reorder, order-bottom, order-top.
(defmethod selection-type-spec :reorder [_]
  (s/keys :req [:selection/mechanism
                :selection/domain
                :selection/player-id
                :selection/validation
                :selection/auto-confirm?]
          :opt [:selection/lifecycle
                :selection/cards
                :selection/top-pile
                :selection/bottom-pile
                :selection/candidates
                :selection/ordered
                :selection/target-player
                :selection/spell-id
                :selection/remaining-effects
                :selection/may-shuffle?
                :selection/selected]))


;; :accumulate — distribute/increment a numeric value via stepper controls.
;; Covers: storm-split, x-mana-cost, pay-x-life.
(defmethod selection-type-spec :accumulate [_]
  (s/keys :req [:selection/mechanism
                :selection/domain
                :selection/lifecycle
                :selection/player-id
                :selection/validation
                :selection/auto-confirm?]
          :opt [:selection/selected
                :selection/max-x
                :selection/selected-x
                :selection/copy-count
                :selection/valid-targets
                :selection/allocation
                :selection/source-object-id
                :selection/stack-item-eid
                :selection/zone
                :selection/spell-id
                :selection/mode
                :selection/chain-builder]))


;; :allocate-resource — assign mana from pool to typed cost slots.
;; Covers: mana-allocation.
(defmethod selection-type-spec :allocate-resource [_]
  (s/keys :req [:selection/mechanism
                :selection/domain
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
                :selection/remaining-pool
                :selection/original-remaining-pool
                :selection/colored-cost
                :selection/original-cost
                :selection/pending-targets
                :selection/source-type
                :selection/ability
                :selection/selected]))


;; :n-slot-targeting — fill N target slots from a valid-targets set.
;; Covers: player-target, cast-time-targeting, ability-cast-targeting,
;;   ability-targeting, select-attackers, assign-blockers.
(defmethod selection-type-spec :n-slot-targeting [_]
  (s/keys :req [:selection/mechanism
                :selection/domain
                :selection/lifecycle
                :selection/player-id
                :selection/selected
                :selection/select-count
                :selection/valid-targets
                :selection/validation
                :selection/auto-confirm?]
          :opt [:selection/object-id
                :selection/mode
                :selection/target-requirement
                :selection/target-requirements
                :selection/enforce-distinctness
                :selection/ability-index
                :selection/chosen-targets
                :selection/remaining-target-reqs
                :selection/stack-item-eid
                :selection/current-attacker
                :selection/remaining-attackers
                :selection/card-source
                :selection/source-type
                :selection/spell-id
                :selection/target-effect
                :selection/remaining-effects
                :selection/pending-targets]))


;; :pick-mode — choose one named option from a finite non-card list.
;; Covers: spell-mode, land-type-source, land-type-target.
(defmethod selection-type-spec :pick-mode [_]
  (s/keys :req [:selection/mechanism
                :selection/domain
                :selection/player-id
                :selection/validation]
          :opt [:selection/lifecycle
                :selection/selected
                :selection/select-count
                :selection/auto-confirm?
                :selection/object-id
                :selection/candidates
                :selection/options
                :selection/exact?
                :selection/source-type
                :selection/spell-id
                :selection/remaining-effects
                :selection/on-complete]))


;; :binary-choice — choose one action from a small fixed action-keyword set.
;; Covers: unless-pay, replacement-choice.
(defmethod selection-type-spec :binary-choice [_]
  (s/keys :req [:selection/mechanism
                :selection/domain
                :selection/player-id
                :selection/selected
                :selection/validation
                :selection/auto-confirm?]
          :opt [:selection/lifecycle
                :selection/select-count
                :selection/valid-targets
                :selection/spell-id
                :selection/remaining-effects
                :selection/counter-target-id
                :selection/unless-pay-cost
                :selection/object-id
                :selection/replacement-entity-id
                :selection/replacement-event
                :selection/choices]))


;; =====================================================
;; ::selection Multi-Spec
;; =====================================================

(s/def ::selection (s/multi-spec selection-type-spec :selection/mechanism))


;; =====================================================
;; minimal-valid-selections
;; One entry per type — used by tests and exercise checks.
;; All entries must pass (s/valid? ::selection).
;; =====================================================

(def minimal-valid-selections
  {:discard
   {:selection/mechanism :pick-from-zone
    :selection/domain    :discard
    :selection/lifecycle :standard
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :exact
    :selection/auto-confirm? false}

   :graveyard-return
   {:selection/mechanism :pick-from-zone
    :selection/domain    :graveyard-return
    :selection/lifecycle :standard
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :at-most
    :selection/auto-confirm? false
    :selection/candidate-ids #{}}

   :hand-reveal-discard
   {:selection/mechanism :pick-from-zone
    :selection/domain    :revealed-hand-discard
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 1
    :selection/validation :exact-or-zero
    :selection/auto-confirm? false
    :selection/valid-targets []}

   :chain-bounce
   {:selection/mechanism :pick-from-zone
    :selection/domain    :chain-bounce
    :selection/lifecycle :chaining
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :exact-or-zero
    :selection/auto-confirm? false
    :selection/valid-targets []
    :selection/select-count 1}

   :chain-bounce-target
   {:selection/mechanism :pick-from-zone
    :selection/domain    :chain-bounce-target
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :exact-or-zero
    :selection/auto-confirm? false
    :selection/valid-targets []
    :selection/select-count 1}

   :unless-pay
   {:selection/mechanism :binary-choice
    :selection/domain    :unless-pay
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 1
    :selection/valid-targets [:pay :decline]
    :selection/validation :exact
    :selection/auto-confirm? true}

   :storm-split
   {:selection/mechanism :accumulate
    :selection/domain    :storm-split
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :always
    :selection/auto-confirm? false
    :selection/copy-count 3
    :selection/valid-targets [:player-2 :player-1]
    :selection/allocation {:player-2 3 :player-1 0}}

   :x-mana-cost
   {:selection/mechanism :accumulate
    :selection/domain    :x-mana-cost
    :selection/lifecycle :chaining
    :selection/player-id :player-1
    :selection/validation :always
    :selection/auto-confirm? false
    :selection/max-x 5
    :selection/selected-x 0}

   :mana-allocation
   {:selection/mechanism :allocate-resource
    :selection/domain    :mana-allocation
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/validation :always
    :selection/auto-confirm? true
    :selection/generic-remaining 2
    :selection/generic-total 2
    :selection/allocation {}}

   :pay-x-life
   {:selection/mechanism :accumulate
    :selection/domain    :pay-x-life
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/validation :always
    :selection/auto-confirm? false
    :selection/max-x 10
    :selection/selected-x 0}

   :scry
   {:selection/mechanism :reorder
    :selection/domain    :scry
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/validation :always
    :selection/auto-confirm? false
    :selection/cards [:obj-1 :obj-2]}

   :peek-and-reorder
   {:selection/mechanism :reorder
    :selection/domain    :peek-and-reorder
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/validation :always
    :selection/auto-confirm? false
    :selection/candidates #{:obj-1 :obj-2}
    :selection/ordered []}

   :order-bottom
   {:selection/mechanism :reorder
    :selection/domain    :order-bottom
    :selection/player-id :player-1
    :selection/validation :always
    :selection/auto-confirm? false
    :selection/candidates #{:obj-1 :obj-2}
    :selection/ordered []}

   :order-top
   {:selection/mechanism :reorder
    :selection/domain    :order-top
    :selection/player-id :player-1
    :selection/validation :always
    :selection/auto-confirm? false
    :selection/candidates #{:obj-1 :obj-2}
    :selection/ordered []}

   :tutor
   {:selection/mechanism :pick-from-zone
    :selection/domain    :tutor
    :selection/lifecycle :chaining
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :exact-or-zero
    :selection/auto-confirm? true
    :selection/target-zone :hand
    :selection/candidates #{}
    :selection/select-count 1}

   :peek-and-select
   {:selection/mechanism :pick-from-zone
    :selection/domain    :peek-and-select
    :selection/lifecycle :chaining
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :at-most
    :selection/auto-confirm? false
    :selection/candidates #{:obj-1 :obj-2}
    :selection/selected-zone :hand
    :selection/remainder-zone :bottom-of-library
    :selection/select-count 1}

   :pile-choice
   {:selection/mechanism :pick-from-zone
    :selection/domain    :pile-choice
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :exact
    :selection/auto-confirm? false
    :selection/candidates #{:obj-1 :obj-2 :obj-3}
    :selection/hand-count 1
    :selection/select-count 1}

   :player-target
   {:selection/mechanism :n-slot-targeting
    :selection/domain    :player-target
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 1
    :selection/valid-targets #{:player-1 :player-2}
    :selection/validation :exact
    :selection/auto-confirm? true}

   :cast-time-targeting
   {:selection/mechanism :n-slot-targeting
    :selection/domain    :cast-time-targeting
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
   {:selection/mechanism :n-slot-targeting
    :selection/domain    :ability-cast-targeting
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
   {:selection/mechanism :n-slot-targeting
    :selection/domain    :ability-targeting
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
   {:selection/mechanism :pick-from-zone
    :selection/domain    :discard-cost
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 2
    :selection/validation :exact
    :selection/auto-confirm? false
    :selection/mode {:mode/id :primary :mode/mana-cost {:blue 1}}}

   :return-land-cost
   {:selection/mechanism :pick-from-zone
    :selection/domain    :return-land-cost
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 1
    :selection/valid-targets [:land-id]
    :selection/validation :exact
    :selection/auto-confirm? true
    :selection/mode {:mode/id :primary :mode/mana-cost {:blue 1}}}

   :sacrifice-permanent-cost
   {:selection/mechanism :pick-from-zone
    :selection/domain    :sacrifice-cost
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 1
    :selection/valid-targets [:perm-id]
    :selection/validation :exact
    :selection/auto-confirm? false}

   :exile-cards-cost
   {:selection/mechanism :pick-from-zone
    :selection/domain    :exile-cost
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 3
    :selection/validation :at-least-one
    :selection/auto-confirm? false
    :selection/candidates #{:obj-1}
    :selection/mode {:mode/id :primary :mode/mana-cost {:colorless 1}}}

   :land-type-source
   {:selection/mechanism :pick-mode
    :selection/domain    :land-type-source
    :selection/lifecycle :chaining
    :selection/player-id :player-1
    :selection/selected nil
    :selection/select-count 1
    :selection/validation :exact}

   :land-type-target
   {:selection/mechanism :pick-mode
    :selection/domain    :land-type-target
    :selection/lifecycle :standard
    :selection/player-id :player-1
    :selection/selected nil
    :selection/select-count 1
    :selection/validation :exact}

   :select-attackers
   {:selection/mechanism :n-slot-targeting
    :selection/domain    :select-attackers
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :at-most
    :selection/auto-confirm? false
    :selection/stack-item-eid 42
    :selection/valid-targets [:creature-1]
    :selection/select-count 1}

   :assign-blockers
   {:selection/mechanism :n-slot-targeting
    :selection/domain    :assign-blockers
    :selection/lifecycle :chaining
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/validation :at-most
    :selection/auto-confirm? false
    :selection/stack-item-eid 42
    :selection/valid-targets []
    :selection/current-attacker :attacker-1
    :selection/remaining-attackers []
    :selection/select-count 1}

   :spell-mode
   {:selection/mechanism :pick-mode
    :selection/domain    :spell-mode
    :selection/lifecycle :finalized
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 1
    :selection/validation :exact
    :selection/auto-confirm? true
    :selection/object-id :spell-id
    :selection/candidates [{:mode/id :primary}]}

   :untap-lands
   {:selection/mechanism :pick-from-zone
    :selection/domain    :untap-lands
    :selection/player-id :player-1
    :selection/selected #{}
    :selection/select-count 2
    :selection/validation :at-most
    :selection/auto-confirm? false
    :selection/candidates #{:land-1}
    :selection/candidate-ids #{:land-1}}

   :replacement-choice
   {:selection/mechanism :binary-choice
    :selection/domain    :replacement-choice
    :selection/player-id :player-1
    :selection/object-id #uuid "00000000-0000-0000-0000-000000000001"
    :selection/replacement-entity-id 42
    :selection/replacement-event {:event/type :zone-change}
    :selection/choices [{:choice/label "Proceed" :choice/action :proceed}]
    :selection/validation :always
    :selection/auto-confirm? false
    :selection/select-count 1
    :selection/selected #{}}})


(defn minimal-valid-selection
  "Return a minimal valid selection map for the given keyword.
   Used by tests to verify every type has a working defmethod."
  [selection-type]
  (get minimal-valid-selections selection-type))


;; =====================================================
;; set-pending-selection Helper
;; =====================================================

(defn set-pending-selection
  "Validated setter for :game/pending-selection.
   All builders must set :selection/mechanism + :selection/domain directly (ADR-030).
   In dev (goog.DEBUG): validates selection via validate-at-chokepoint!.
   Throws when *throw-on-spec-failure* is true (tests), console.error otherwise.
   In prod: no validation (dead-code eliminated by Closure compiler).

   Pure function: (app-db, selection) -> app-db."
  [app-db selection]
  (spec-util/validate-at-chokepoint!
    ::selection selection "set-pending-selection")
  (assoc app-db :game/pending-selection selection))
