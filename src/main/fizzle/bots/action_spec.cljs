(ns fizzle.bots.action-spec
  "Bot action validation using cljs.spec.alpha.

   Validates bot action maps at the bot-decide-action chokepoint.
   All specs describe existing action shapes — they do NOT prescribe
   new requirements. Every existing return site must pass unchanged.

   Multi-spec dispatches on :action.
   3 types: :pass, :cast-spell, :play-land

   Bot actions use UNNAMESPACED keys — use s/keys :req-un/:opt-un.

   Tap entry shape: {:object-id uuid :mana-color keyword}
   :object-id is the game object's UUID (object/id), NOT a Datascript EID."
  (:require
    [cljs.spec.alpha :as s]
    [fizzle.engine.spec-common]
    [fizzle.engine.spec-util :as spec-util]))


;; =====================================================
;; Base Field Specs (unnamespaced — bot actions use plain keys)
;; =====================================================

(s/def ::action keyword?)
(s/def ::object-id :game/object-id)
(s/def ::player-id :game/player-id)
(s/def ::target keyword?)
(s/def ::mana-color keyword?)


;; =====================================================
;; Tap Entry + Tap Sequence
;; =====================================================

(s/def ::tap-entry
  (s/keys :req-un [::object-id
                   ::mana-color]))


(s/def ::tap-sequence
  (s/coll-of ::tap-entry :kind vector?))


;; =====================================================
;; Bot Action Multi-Spec
;; =====================================================

(defmulti action-type-spec :action)


;; :pass — bot yields priority, no additional fields required.
(defmethod action-type-spec :pass [_]
  (s/keys :req-un [::action]))


;; :cast-spell — bot casts a spell.
;; Requires :object-id (UUID of the card to cast), :player-id (casting player),
;; and :tap-sequence (vector of tap entries to pay cost). :target is optional.
(defmethod action-type-spec :cast-spell [_]
  (s/keys :req-un [::action
                   ::object-id
                   ::player-id
                   ::tap-sequence]
          :opt-un [::target]))


;; :play-land — bot plays a land from hand.
;; Only :action required; no additional fields needed.
(defmethod action-type-spec :play-land [_]
  (s/keys :req-un [::action]))


(s/def ::bot-action (s/multi-spec action-type-spec :action))


;; =====================================================
;; minimal-valid-actions
;; One entry per type — used by tests and validate-at-chokepoint!.
;; All entries must pass (s/valid? ::bot-action).
;; =====================================================

(def minimal-valid-actions
  {:pass
   {:action :pass}

   :cast-spell
   {:action :cast-spell
    :object-id (random-uuid)
    :player-id :player-2
    :tap-sequence [{:object-id (random-uuid) :mana-color :red}]}

   :play-land
   {:action :play-land}})


(defn minimal-valid-action
  "Return a minimal valid action map for the given :action keyword.
   Used by tests to verify every type has a working defmethod."
  [action-type]
  (get minimal-valid-actions action-type))


;; =====================================================
;; Validation Helper
;; =====================================================

(defn validate-bot-action!
  "Validate a bot action map at the bot-decide-action chokepoint.
   Delegates to spec-util/validate-at-chokepoint!.
   Returns nil always (side effects only in dev mode)."
  [action]
  (spec-util/validate-at-chokepoint! ::bot-action action "bot-decide-action"))
