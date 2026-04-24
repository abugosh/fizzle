(ns fizzle.engine.card-spec
  "Card definition validation using cljs.spec.alpha.

   Validates card data shapes at load time to catch typos and missing
   fields before they cause silent runtime failures.

   All specs describe existing card shapes — they do NOT prescribe
   new requirements. Every existing card definition must pass unchanged."
  (:require
    [cljs.spec.alpha :as s]
    [fizzle.engine.spec-common]))


;; === Enums ===

(def valid-effect-types
  #{:add-mana :mill :lose-life :gain-life :deal-damage :add-counters
    :draw :exile-self :discard-hand :return-from-graveyard :sacrifice
    :destroy :discard :tutor :scry :peek-and-select :peek-and-reorder
    :grant-flashback :grant-delayed-draw :add-restriction :storm-copies
    :exile-zone :gain-life-equal-to-cmc :discard-from-revealed-hand
    :bounce :bounce-all :chain-bounce :counter-spell :counter-ability
    :peek-random-hand :grant-mana-ability :create-token :apply-pt-modifier
    :welder-swap :untap-lands :tap-all :untap-all
    :lose-life-equal-to-toughness :phase-out :change-land-types
    :shuffle-from-graveyard-to-library})


(def valid-cost-types
  #{:pay-life :pay-x-life :exile-cards :return-land :discard-specific :sacrifice-permanent})


(def valid-ability-cost-keys
  #{:tap :sacrifice-self :remove-counter :discard-hand :mana})


(def valid-condition-types
  #{:threshold :no-counters :target-is-color :power-gte})


(def valid-colors
  #{:white :blue :black :red :green})


(def valid-card-types
  #{:land :instant :sorcery :enchantment :artifact :creature})


(def valid-ability-types
  #{:mana :activated :triggered})


(def valid-trigger-types
  #{:becomes-tapped :land-entered :creature-attacks :enters-battlefield :zone-change})


(def valid-restriction-types
  #{:cannot-cast-spells :cannot-attack
    :cannot-cast-instants-sorceries :cannot-activate-non-mana-abilities})


;; === Mana Cost Spec ===

(s/def :mana/color-amount nat-int?)
(s/def :mana/x boolean?)


(s/def ::mana-cost
  (s/and map?
         (s/every-kv
           (s/or :color valid-colors
                 :colorless #{:colorless}
                 :any #{:any}
                 :x #{:x})
           (s/or :amount nat-int?
                 :x-flag boolean?))))


;; === Effect Field Specs ===

(s/def :effect/type valid-effect-types)
(s/def :effect/mana ::mana-cost)
(s/def :effect/amount (s/or :int int? :keyword keyword? :dynamic map?))
(s/def :effect/count (s/or :int int? :keyword keyword? :dynamic map?))
(s/def :effect/target keyword?)
(s/def :effect/target-ref keyword?)
(s/def :effect/graveyard-ref keyword?)
(s/def :effect/selection keyword?)
(s/def :effect/criteria map?)
(s/def :effect/select-count int?)
(s/def :effect/selected-zone keyword?)
(s/def :effect/remainder-zone keyword?)
(s/def :effect/order-remainder? boolean?)
(s/def :effect/zone keyword?)
(s/def :effect/ability map?)
(s/def :effect/power int?)
(s/def :effect/toughness int?)
(s/def :restriction/type valid-restriction-types)


;; 9 previously-missing field specs
(s/def :effect/token map?)
(s/def :effect/counters map?)
(s/def :effect/permanent-type keyword?)
(s/def :effect/target-zone keyword?)
(s/def :effect/source-zone keyword?)
(s/def :effect/shuffle? boolean?)
(s/def :effect/pile-choice map?)
(s/def :effect/may-shuffle? boolean?)
(s/def :effect/shuffle-remainder? boolean?)


(s/def :effect/condition
  (s/keys :req [:condition/type]
          :opt [:condition/target :condition/counter-type]))


(s/def :effect/unless-pay ::mana-cost)


;; === Effect Multi-Spec ===
;; Dispatches on :effect/type. Each defmethod returns an s/keys spec
;; listing required and optional keys for that type.
;; :effect/condition is optional on every type (orthogonal to effect type).

(defmulti effect-type-spec :effect/type)


;; --- Group 1: amount+target ---

(defmethod effect-type-spec :mill [_]
  (s/keys :req [:effect/type :effect/amount]
          :opt [:effect/target :effect/target-ref :effect/condition]))


(defmethod effect-type-spec :draw [_]
  (s/keys :req [:effect/type :effect/amount]
          :opt [:effect/target :effect/condition]))


(defmethod effect-type-spec :deal-damage [_]
  (s/keys :req [:effect/type :effect/amount]
          :opt [:effect/target :effect/target-ref :effect/condition]))


(defmethod effect-type-spec :lose-life [_]
  (s/keys :req [:effect/type :effect/amount]
          :opt [:effect/target :effect/condition]))


(defmethod effect-type-spec :discard [_]
  (s/keys :req [:effect/type :effect/count :effect/selection]
          :opt [:effect/condition]))


(defmethod effect-type-spec :return-from-graveyard [_]
  (s/keys :req [:effect/type :effect/count :effect/selection]
          :opt [:effect/target :effect/condition]))


;; --- Group 2: zone-op ---

(defmethod effect-type-spec :exile-self [_]
  (s/keys :req [:effect/type]
          :opt [:effect/condition]))


(defmethod effect-type-spec :bounce [_]
  (s/keys :req [:effect/type :effect/target-ref]
          :opt [:effect/condition]))


(defmethod effect-type-spec :bounce-all [_]
  (s/keys :req [:effect/type :effect/criteria]
          :opt [:effect/target-ref :effect/condition]))


(defmethod effect-type-spec :destroy [_]
  (s/keys :req [:effect/type :effect/target-ref]
          :opt [:effect/condition]))


(defmethod effect-type-spec :discard-hand [_]
  (s/keys :req [:effect/type]
          :opt [:effect/target :effect/condition]))


(defmethod effect-type-spec :sacrifice [_]
  (s/keys :req [:effect/type]
          :opt [:effect/target :effect/condition]))


(defmethod effect-type-spec :exile-zone [_]
  (s/keys :req [:effect/type :effect/target-ref :effect/zone]
          :opt [:effect/condition]))


(defmethod effect-type-spec :phase-out [_]
  (s/keys :req [:effect/type :effect/target-ref]
          :opt [:effect/condition]))


(defmethod effect-type-spec :chain-bounce [_]
  (s/keys :req [:effect/type :effect/target-ref]
          :opt [:effect/condition]))


(defmethod effect-type-spec :untap-lands [_]
  (s/keys :req [:effect/type :effect/count]
          :opt [:effect/condition]))


(defmethod effect-type-spec :tap-all [_]
  (s/keys :req [:effect/type :effect/target :effect/permanent-type]
          :opt [:effect/condition]))


(defmethod effect-type-spec :untap-all [_]
  (s/keys :req [:effect/type :effect/target :effect/permanent-type]
          :opt [:effect/condition]))


(defmethod effect-type-spec :welder-swap [_]
  (s/keys :req [:effect/type :effect/target-ref :effect/graveyard-ref]
          :opt [:effect/condition]))


;; --- Group 3: mana ---

(defmethod effect-type-spec :add-mana [_]
  (s/keys :req [:effect/type :effect/mana]
          :opt [:effect/condition]))


;; --- Group 4: library-ops ---

(defmethod effect-type-spec :tutor [_]
  (s/keys :req [:effect/type :effect/target-zone]
          :opt [:effect/criteria :effect/shuffle? :effect/source-zone
                :effect/select-count :effect/pile-choice :effect/condition]))


(defmethod effect-type-spec :scry [_]
  (s/keys :req [:effect/type :effect/amount]
          :opt [:effect/condition]))


(defmethod effect-type-spec :peek-and-select [_]
  (s/keys :req [:effect/type :effect/count :effect/selected-zone
                :effect/remainder-zone :effect/select-count]
          :opt [:effect/order-remainder? :effect/shuffle-remainder? :effect/condition]))


(defmethod effect-type-spec :peek-and-reorder [_]
  (s/keys :req [:effect/type :effect/count]
          :opt [:effect/target-ref :effect/may-shuffle? :effect/condition]))


(defmethod effect-type-spec :peek-random-hand [_]
  (s/keys :req [:effect/type :effect/target-ref]
          :opt [:effect/condition]))


;; --- Group 5: granting ---

(defmethod effect-type-spec :grant-flashback [_]
  (s/keys :req [:effect/type :effect/target-ref]
          :opt [:effect/condition]))


(defmethod effect-type-spec :grant-delayed-draw [_]
  (s/keys :req [:effect/type]
          :opt [:effect/target :effect/condition]))


(defmethod effect-type-spec :grant-mana-ability [_]
  (s/keys :req [:effect/type :effect/target :effect/ability]
          :opt [:effect/mana :effect/condition]))


(defmethod effect-type-spec :add-restriction [_]
  (s/keys :req [:effect/type :restriction/type]
          :opt [:effect/target :effect/condition]))


(defmethod effect-type-spec :add-counters [_]
  (s/keys :req [:effect/type :effect/target :effect/counters]
          :opt [:effect/condition]))


;; --- Group 6: special ---

(defmethod effect-type-spec :counter-spell [_]
  (s/keys :req [:effect/type :effect/target-ref]
          :opt [:effect/condition :effect/unless-pay]))


(defmethod effect-type-spec :counter-ability [_]
  (s/keys :req [:effect/type :effect/target-ref]
          :opt [:effect/condition]))


(defmethod effect-type-spec :apply-pt-modifier [_]
  (s/keys :req [:effect/type :effect/target-ref :effect/power :effect/toughness]
          :opt [:effect/condition]))


(defmethod effect-type-spec :lose-life-equal-to-toughness [_]
  (s/keys :req [:effect/type :effect/target-ref]
          :opt [:effect/condition]))


(defmethod effect-type-spec :gain-life-equal-to-cmc [_]
  (s/keys :req [:effect/type :effect/target-ref]
          :opt [:effect/condition]))


(defmethod effect-type-spec :create-token [_]
  (s/keys :req [:effect/type :effect/token]
          :opt [:effect/condition]))


(defmethod effect-type-spec :change-land-types [_]
  (s/keys :req [:effect/type]
          :opt [:effect/target-ref :effect/amount :effect/condition]))


(defmethod effect-type-spec :discard-from-revealed-hand [_]
  (s/keys :req [:effect/type :effect/target :effect/criteria]
          :opt [:effect/condition]))


(defmethod effect-type-spec :shuffle-from-graveyard-to-library [_]
  (s/keys :req [:effect/type :effect/count :effect/selection]
          :opt [:effect/target :effect/condition]))


;; Runtime-only types: never appear in card definitions but must have defmethods
;; for s/exercise and completeness

(defmethod effect-type-spec :storm-copies [_]
  (s/keys :req [:effect/type]
          :opt [:effect/condition]))


(defmethod effect-type-spec :gain-life [_]
  (s/keys :req [:effect/type :effect/amount]
          :opt [:effect/target :effect/condition]))


;; === minimal-valid-effect helper (used by tests and ::effect generator) ===
;; Returns the minimal valid effect map for a given :effect/type keyword.
;; Defined before ::effect so the generator can reference it.

(def ^:private minimal-effects
  {:add-mana              {:effect/type :add-mana :effect/mana {:black 1}}
   :mill                  {:effect/type :mill :effect/amount 1}
   :draw                  {:effect/type :draw :effect/amount 1}
   :deal-damage           {:effect/type :deal-damage :effect/amount 1 :effect/target :opponent}
   :discard               {:effect/type :discard :effect/count 1 :effect/selection :player}
   :return-from-graveyard {:effect/type :return-from-graveyard :effect/count 1 :effect/selection :player}
   :exile-self            {:effect/type :exile-self}
   :bounce                {:effect/type :bounce :effect/target-ref :target}
   :bounce-all            {:effect/type :bounce-all :effect/criteria {:match/types #{:artifact}}}
   :destroy               {:effect/type :destroy :effect/target-ref :target}
   :discard-hand          {:effect/type :discard-hand}
   :sacrifice             {:effect/type :sacrifice}
   :exile-zone            {:effect/type :exile-zone :effect/target-ref :player :effect/zone :graveyard}
   :phase-out             {:effect/type :phase-out :effect/target-ref :target}
   :chain-bounce          {:effect/type :chain-bounce :effect/target-ref :target}
   :untap-lands           {:effect/type :untap-lands :effect/count 2}
   :tap-all               {:effect/type :tap-all :effect/target :opponent :effect/permanent-type :creature}
   :untap-all             {:effect/type :untap-all :effect/target :self :effect/permanent-type :land}
   :welder-swap           {:effect/type :welder-swap :effect/target-ref :welder-bf :effect/graveyard-ref :welder-gy}
   :tutor                 {:effect/type :tutor :effect/target-zone :hand}
   :scry                  {:effect/type :scry :effect/amount 1}
   :peek-and-select       {:effect/type :peek-and-select :effect/count 4
                           :effect/select-count 1
                           :effect/selected-zone :hand
                           :effect/remainder-zone :bottom-of-library}
   :peek-and-reorder      {:effect/type :peek-and-reorder :effect/count 3}
   :peek-random-hand      {:effect/type :peek-random-hand :effect/target-ref :player}
   :grant-flashback       {:effect/type :grant-flashback :effect/target-ref :target}
   :grant-delayed-draw    {:effect/type :grant-delayed-draw}
   :grant-mana-ability    {:effect/type :grant-mana-ability :effect/target :controlled-lands
                           :effect/ability {:ability/type :mana} :effect/mana {:black 1}}
   :add-restriction       {:effect/type :add-restriction :restriction/type :cannot-cast-spells}
   :add-counters          {:effect/type :add-counters :effect/target :self :effect/counters {:+1/+1 1}}
   :counter-spell         {:effect/type :counter-spell :effect/target-ref :target-spell}
   :counter-ability       {:effect/type :counter-ability :effect/target-ref :ability}
   :apply-pt-modifier     {:effect/type :apply-pt-modifier :effect/target-ref :target
                           :effect/power -2 :effect/toughness -2}
   :lose-life-equal-to-toughness {:effect/type :lose-life-equal-to-toughness :effect/target-ref :target}
   :gain-life-equal-to-cmc {:effect/type :gain-life-equal-to-cmc :effect/target-ref :target}
   :create-token          {:effect/type :create-token
                           :effect/token {:token/name "Beast" :token/power 4 :token/toughness 4}}
   :change-land-types     {:effect/type :change-land-types}
   :discard-from-revealed-hand {:effect/type :discard-from-revealed-hand :effect/target :opponent
                                :effect/criteria {:match/not-types #{:creature :land}}}
   :storm-copies          {:effect/type :storm-copies}
   :lose-life             {:effect/type :lose-life :effect/amount 3}
   :gain-life             {:effect/type :gain-life :effect/amount 3}
   :shuffle-from-graveyard-to-library
   {:effect/type :shuffle-from-graveyard-to-library :effect/count 3 :effect/selection :player}})


(defn minimal-valid-effect
  "Return a minimal valid effect map for the given effect type keyword.
   Used by tests to verify every type has a working defmethod."
  [effect-type]
  (get minimal-effects effect-type))


(s/def ::effect (s/multi-spec effect-type-spec :effect/type))


(s/def ::effects (s/coll-of ::effect :kind vector?))


;; === Condition Spec ===

(s/def :condition/type valid-condition-types)


;; === Targeting Spec ===

(s/def :target/id keyword?)
(s/def :target/type keyword?)
(s/def :target/zone keyword?)
(s/def :target/controller keyword?)
(s/def :target/criteria map?)
(s/def :target/options (s/coll-of keyword?))
(s/def :target/required boolean?)
(s/def :target/same-controller-as keyword?)


(s/def ::targeting
  (s/keys :req [:target/id :target/type]
          :opt [:target/zone :target/controller
                :target/criteria :target/options
                :target/required :target/same-controller-as]))


(s/def ::targeting-vec (s/coll-of ::targeting :kind vector?))


;; === Ability Cost Spec ===

(s/def ::ability-cost map?)


;; === Ability Spec ===

(s/def :ability/type valid-ability-types)
(s/def :ability/cost ::ability-cost)
(s/def :ability/effects ::effects)
(s/def :ability/targeting ::targeting-vec)
(s/def :ability/condition (s/keys :req [:condition/type]))
(s/def :ability/produces ::mana-cost)
(s/def :ability/name string?)
(s/def :ability/description string?)


(s/def ::ability
  (s/keys :req [:ability/type]
          :opt [:ability/cost :ability/effects
                :ability/targeting :ability/condition
                :ability/produces :ability/name
                :ability/description]))


;; === Trigger Spec ===

(s/def :trigger/type valid-trigger-types)
(s/def :trigger/description string?)
(s/def :trigger/effects ::effects)
(s/def :trigger/filter map?)


(s/def :trigger/match-value
  (s/or :self #{:self}
        :value any?))


(s/def :trigger/match
  (s/nilable (s/map-of keyword? :trigger/match-value)))


(s/def ::trigger
  (s/keys :req [:trigger/type :trigger/effects]
          :opt [:trigger/description :trigger/filter :trigger/match]))


;; === Additional Cost Spec ===

(s/def :cost/type valid-cost-types)
(s/def :cost/amount int?)
(s/def :cost/zone keyword?)
(s/def :cost/criteria map?)
(s/def :cost/count (s/or :int int? :keyword keyword?))
(s/def :cost/groups vector?)
(s/def :cost/total int?)


(s/def ::additional-cost
  (s/keys :req [:cost/type]
          :opt [:cost/amount :cost/zone :cost/criteria :cost/count
                :cost/groups :cost/total]))


;; === Alternate Cost Spec (Flashback, Kicker, etc.) ===

(s/def :alternate/id keyword?)
(s/def :alternate/zone keyword?)
(s/def :alternate/mana-cost ::mana-cost)
(s/def :alternate/additional-costs (s/coll-of ::additional-cost :kind vector?))
(s/def :alternate/on-resolve keyword?)
(s/def :alternate/kind keyword?)
(s/def :alternate/effects ::effects)
(s/def :alternate/targeting ::targeting-vec)


(s/def ::alternate-cost
  (s/keys :req [:alternate/id :alternate/zone :alternate/mana-cost]
          :opt [:alternate/additional-costs :alternate/on-resolve
                :alternate/kind :alternate/effects :alternate/targeting]))


;; === Conditional Effects Spec ===

(s/def ::conditional-effects
  (s/map-of keyword? ::effects))


;; === Static Ability Spec ===

(def valid-static-types
  #{:cost-modifier :pt-modifier :untap-restriction})


(def valid-modifier-directions
  #{:increase :decrease})


(def valid-applies-to
  #{:controller :all :self})


(s/def :static/type valid-static-types)
(s/def :modifier/amount pos-int?)
(s/def :modifier/direction valid-modifier-directions)
(s/def :modifier/condition map?)
(s/def :modifier/criteria map?)
(s/def :modifier/applies-to valid-applies-to)
(s/def :modifier/power int?)
(s/def :modifier/toughness int?)


(defn- valid-static-ability?
  "Cross-field validation: cost-modifier needs amount+direction, pt-modifier needs power+toughness."
  [sa]
  (case (:static/type sa)
    :cost-modifier (and (contains? sa :modifier/amount)
                        (contains? sa :modifier/direction))
    :pt-modifier (and (contains? sa :modifier/power)
                      (contains? sa :modifier/toughness))
    :untap-restriction (contains? sa :modifier/criteria)
    true))


(s/def ::static-ability
  (s/and (s/keys :req [:static/type]
                 :opt [:modifier/amount :modifier/direction
                       :modifier/condition :modifier/criteria :modifier/applies-to
                       :modifier/power :modifier/toughness])
         valid-static-ability?))


(s/def :card/static-abilities (s/coll-of ::static-ability :kind vector?))


;; === Top-level Card Spec ===

(s/def :card/id keyword?)
(s/def :card/name string?)
(s/def :card/cmc nat-int?)
(s/def :card/mana-cost ::mana-cost)
(s/def :card/colors (s/coll-of valid-colors :kind set?))
(s/def :card/types (s/coll-of valid-card-types :kind set?))
(s/def :card/text string?)
(s/def :card/subtypes (s/coll-of keyword? :kind set?))
(s/def :card/supertypes (s/coll-of keyword? :kind set?))
(s/def :card/keywords (s/coll-of keyword? :kind set?))
(s/def :card/effects ::effects)
(s/def :card/abilities (s/coll-of ::ability :kind vector?))
(s/def :card/etb-effects ::effects)
(s/def :card/triggers (s/coll-of ::trigger :kind vector?))
(s/def :card/targeting ::targeting-vec)
(s/def :card/alternate-costs (s/coll-of ::alternate-cost :kind vector?))
(s/def :card/conditional-effects ::conditional-effects)
(s/def :card/additional-costs (s/coll-of ::additional-cost :kind vector?))
(s/def :card/cast-restriction map?)
(s/def :card/kicker ::mana-cost)
(s/def :card/kicked-effects ::effects)
(s/def :card/cycling ::mana-cost)
(s/def :card/power int?)
(s/def :card/toughness pos-int?)


;; === State Trigger Spec ===
;; State triggers fire when a condition is true (e.g., power >= 7).
;; They go on the stack (respondable), unlike immediate SBAs.

(s/def :state/condition map?)
(s/def :state/effects ::effects)
(s/def :state/description string?)


(s/def ::state-trigger
  (s/keys :req [:state/condition :state/effects]
          :opt [:state/description]))


(s/def :card/state-triggers (s/coll-of ::state-trigger :kind vector?))


(defn- creature-has-pt?
  "Cross-field validation: creature cards require power and toughness."
  [card]
  (if (contains? (set (:card/types card)) :creature)
    (and (contains? card :card/power)
         (contains? card :card/toughness))
    true))


(s/def ::card
  (s/and (s/keys :req [:card/id :card/name :card/cmc :card/mana-cost
                       :card/colors :card/types :card/text]
                 :opt [:card/effects :card/abilities :card/etb-effects
                       :card/triggers :card/targeting :card/alternate-costs
                       :card/conditional-effects :card/kicker :card/kicked-effects
                       :card/subtypes :card/supertypes :card/keywords
                       :card/additional-costs :card/cast-restriction
                       :card/static-abilities :card/power :card/toughness
                       :card/state-triggers :card/cycling])
         creature-has-pt?))


;; === Validation Functions ===

(defn valid-card?
  "Check if a card definition is valid according to the spec.
   Returns true if valid, false otherwise."
  [card]
  (s/valid? ::card card))


(defn explain-card
  "Return explanation data for why a card fails validation.
   Returns nil if card is valid."
  [card]
  (when-not (s/valid? ::card card)
    (s/explain-data ::card card)))


(defn validate-cards!
  "Validate all card definitions. Throws ex-info on first invalid card
   with card name and spec explanation in the error data.
   Returns nil on success."
  [cards]
  (doseq [card cards]
    (when-not (s/valid? ::card card)
      (throw (ex-info (str "Invalid card definition for '"
                           (:card/name card "unknown")
                           "': " (s/explain-str ::card card))
                      {:card/id (:card/id card)
                       :card/name (:card/name card)
                       :explain-data (s/explain-data ::card card)})))))
