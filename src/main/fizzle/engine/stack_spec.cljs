(ns fizzle.engine.stack-spec
  "Stack item validation using cljs.spec.alpha.

   Validates stack-item data shapes at the create-stack-item chokepoint.
   All specs describe existing stack-item shapes — they do NOT prescribe
   new requirements. Every existing creation site must pass unchanged.

   Multi-spec dispatches on :stack-item/type.
   10 types: :spell, :storm-copy, :activated-ability, :permanent-entered,
             :storm, :declare-attackers, :declare-blockers, :combat-damage,
             :delayed-trigger, :state-check-trigger

   CRITICAL field distinction:
   - :stack-item/source   — Object ID (UUID). The game object that caused this item.
   - :stack-item/object-ref — Datascript EID (integer). Reference to the object entity.
   Spells have both; triggers may have only source; combat markers have neither."
  (:require
    [cljs.spec.alpha :as s]
    [fizzle.engine.spec-common]
    [fizzle.engine.spec-util :as spec-util]))


;; =====================================================
;; Base Field Specs
;; =====================================================

(s/def :stack-item/type keyword?)
(s/def :stack-item/controller :game/player-id)
(s/def :stack-item/source :game/object-id)
(s/def :stack-item/object-ref int?)
(s/def :stack-item/effects coll?)
(s/def :stack-item/targets (s/nilable map?))
(s/def :stack-item/description string?)
(s/def :stack-item/is-copy boolean?)


;; =====================================================
;; Stack Item Multi-Spec
;; =====================================================

(defmulti stack-item-type-spec :stack-item/type)


;; :spell — cast from hand or alternate zone.
;; Has both source (UUID, the object-id) and object-ref (EID, datascript reference).
(defmethod stack-item-type-spec :spell [_]
  (s/keys :req [:stack-item/type
                :stack-item/controller
                :stack-item/source
                :stack-item/object-ref]
          :opt [:stack-item/targets
                :stack-item/description
                :stack-item/is-copy]))


;; :storm-copy — copy of a spell created by the storm trigger.
;; Like spell: has source and object-ref for the copy.
(defmethod stack-item-type-spec :storm-copy [_]
  (s/keys :req [:stack-item/type
                :stack-item/controller
                :stack-item/source
                :stack-item/object-ref]
          :opt [:stack-item/targets
                :stack-item/description
                :stack-item/is-copy]))


;; :activated-ability — player-activated ability of a permanent.
;; Has source (originating object UUID) and effects (the ability's effects).
(defmethod stack-item-type-spec :activated-ability [_]
  (s/keys :req [:stack-item/type
                :stack-item/controller
                :stack-item/source
                :stack-item/effects]
          :opt [:stack-item/targets
                :stack-item/description]))


;; :permanent-entered — ETB trigger for a permanent entering the battlefield.
;; Source is optional (depends on the trigger definition). Has effects.
(defmethod stack-item-type-spec :permanent-entered [_]
  (s/keys :req [:stack-item/type
                :stack-item/controller
                :stack-item/effects]
          :opt [:stack-item/source
                :stack-item/description]))


;; :storm — storm trigger stack item. Has source (the storm spell's object-id),
;; effects (storm-copies effect with count), and description.
(defmethod stack-item-type-spec :storm [_]
  (s/keys :req [:stack-item/type
                :stack-item/controller
                :stack-item/source
                :stack-item/effects
                :stack-item/description]
          :opt [:stack-item/targets]))


;; :declare-attackers — marker item placed on stack at start of declare-attackers step.
;; Only type and controller required; description is optional.
(defmethod stack-item-type-spec :declare-attackers [_]
  (s/keys :req [:stack-item/type
                :stack-item/controller]
          :opt [:stack-item/description]))


;; :declare-blockers — marker item placed on stack for declaring blockers.
;; Only type and controller required; description is optional.
(defmethod stack-item-type-spec :declare-blockers [_]
  (s/keys :req [:stack-item/type
                :stack-item/controller]
          :opt [:stack-item/description]))


;; :combat-damage — marker item for combat damage assignment.
;; Only type and controller required; description is optional.
(defmethod stack-item-type-spec :combat-damage [_]
  (s/keys :req [:stack-item/type
                :stack-item/controller]
          :opt [:stack-item/description]))


;; :delayed-trigger — delayed trigger from a grant (e.g., Promise of Power delay).
;; Has effects, optional description. No source (grant is removed when trigger fires).
(defmethod stack-item-type-spec :delayed-trigger [_]
  (s/keys :req [:stack-item/type
                :stack-item/controller
                :stack-item/effects]
          :opt [:stack-item/description]))


;; :state-check-trigger — trigger from a state-based action (e.g., high-toughness creature).
;; Has source (the triggering object's UUID) and effects. Description is optional.
(defmethod stack-item-type-spec :state-check-trigger [_]
  (s/keys :req [:stack-item/type
                :stack-item/controller
                :stack-item/source
                :stack-item/effects]
          :opt [:stack-item/description]))


;; =====================================================
;; Card Trigger Types (dispatched via trigger_dispatch)
;; These are event-type keywords produced by trigger-type->event-type mapping.
;; Shape: type + controller required; source + effects + description optional.
;; =====================================================

;; :permanent-tapped — trigger from :becomes-tapped card trigger (e.g., City of Brass)
(defmethod stack-item-type-spec :permanent-tapped [_]
  (s/keys :req [:stack-item/type
                :stack-item/controller]
          :opt [:stack-item/source
                :stack-item/effects
                :stack-item/description]))


;; :creature-attacked — trigger from :creature-attacks card trigger
(defmethod stack-item-type-spec :creature-attacked [_]
  (s/keys :req [:stack-item/type
                :stack-item/controller]
          :opt [:stack-item/source
                :stack-item/effects
                :stack-item/description]))


;; :land-entered — trigger from :land-entered card trigger
(defmethod stack-item-type-spec :land-entered [_]
  (s/keys :req [:stack-item/type
                :stack-item/controller]
          :opt [:stack-item/source
                :stack-item/effects
                :stack-item/description]))


;; :etb — ETB trigger type used in tests and views for historical/display purposes
(defmethod stack-item-type-spec :etb [_]
  (s/keys :req [:stack-item/type
                :stack-item/controller]
          :opt [:stack-item/source
                :stack-item/effects
                :stack-item/description]))


;; :triggered-ability — generic triggered ability type (used in targeting tests)
(defmethod stack-item-type-spec :triggered-ability [_]
  (s/keys :req [:stack-item/type
                :stack-item/controller]
          :opt [:stack-item/source
                :stack-item/effects
                :stack-item/description]))


;; :phase-entered — turn-based action trigger fired when a new phase is entered
;; (e.g., draw-step and untap-step triggers fire when :draw/:untap phase is entered)
(defmethod stack-item-type-spec :phase-entered [_]
  (s/keys :req [:stack-item/type
                :stack-item/controller]
          :opt [:stack-item/source
                :stack-item/effects
                :stack-item/description]))


;; :zone-change — trigger from :zone-change card triggers (e.g., Gaea's Blessing)
;; Fires when an object moves from one zone to another, filtered by :trigger/match.
(defmethod stack-item-type-spec :zone-change [_]
  (s/keys :req [:stack-item/type
                :stack-item/controller]
          :opt [:stack-item/source
                :stack-item/effects
                :stack-item/description]))


;; :test — test-only sentinel type (used in turn_test to verify stack non-empty)
(defmethod stack-item-type-spec :test [_]
  (s/keys :req [:stack-item/type
                :stack-item/controller]
          :opt [:stack-item/source
                :stack-item/effects
                :stack-item/description]))


(s/def ::stack-item (s/multi-spec stack-item-type-spec :stack-item/type))


;; =====================================================
;; minimal-valid-stack-items
;; One entry per type — used by tests and validate-at-chokepoint!.
;; All entries must pass (s/valid? ::stack-item).
;; =====================================================

(def minimal-valid-stack-items
  {:spell
   {:stack-item/type :spell
    :stack-item/controller :player-1
    :stack-item/source (random-uuid)
    :stack-item/object-ref 42}

   :storm-copy
   {:stack-item/type :storm-copy
    :stack-item/controller :player-1
    :stack-item/source (random-uuid)
    :stack-item/object-ref 43}

   :activated-ability
   {:stack-item/type :activated-ability
    :stack-item/controller :player-1
    :stack-item/source (random-uuid)
    :stack-item/effects [{:effect/type :add-mana :effect/mana {:black 1}}]}

   :permanent-entered
   {:stack-item/type :permanent-entered
    :stack-item/controller :player-1
    :stack-item/effects [{:effect/type :draw :effect/amount 1}]}

   :storm
   {:stack-item/type :storm
    :stack-item/controller :player-1
    :stack-item/source (random-uuid)
    :stack-item/effects [{:effect/type :storm-copies :effect/count 2}]
    :stack-item/description "Storm - create 2 copies"}

   :declare-attackers
   {:stack-item/type :declare-attackers
    :stack-item/controller :player-1}

   :declare-blockers
   {:stack-item/type :declare-blockers
    :stack-item/controller :player-1}

   :combat-damage
   {:stack-item/type :combat-damage
    :stack-item/controller :player-1}

   :delayed-trigger
   {:stack-item/type :delayed-trigger
    :stack-item/controller :player-1
    :stack-item/effects [{:effect/type :draw :effect/amount 2}]}

   :state-check-trigger
   {:stack-item/type :state-check-trigger
    :stack-item/controller :player-1
    :stack-item/source (random-uuid)
    :stack-item/effects [{:effect/type :deal-damage :effect/amount 1}]}})


(defn minimal-valid-stack-item
  "Return a minimal valid stack-item map for the given :stack-item/type keyword.
   Used by tests to verify every type has a working defmethod."
  [item-type]
  (get minimal-valid-stack-items item-type))


;; =====================================================
;; Validation Helper
;; =====================================================

(defn validate-stack-item!
  "Validate a stack-item attrs map at create-stack-item chokepoint.
   Delegates to spec-util/validate-at-chokepoint!.
   Returns nil always (side effects only in dev mode)."
  [attrs]
  (spec-util/validate-at-chokepoint! ::stack-item attrs "create-stack-item"))
