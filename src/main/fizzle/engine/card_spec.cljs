(ns fizzle.engine.card-spec
  "Card definition validation using cljs.spec.alpha.

   Validates card data shapes at load time to catch typos and missing
   fields before they cause silent runtime failures.

   All specs describe existing card shapes — they do NOT prescribe
   new requirements. Every existing card definition must pass unchanged."
  (:require
    [cljs.spec.alpha :as s]))


;; === Enums ===

(def valid-effect-types
  #{:add-mana :mill :lose-life :gain-life :deal-damage :add-counters
    :draw :exile-self :discard-hand :return-from-graveyard :sacrifice
    :destroy :discard :tutor :scry :peek-and-select :peek-and-reorder
    :grant-flashback :grant-delayed-draw :add-restriction :storm-copies
    :exile-zone :gain-life-equal-to-cmc :discard-from-revealed-hand
    :bounce :chain-bounce :counter-spell :counter-ability
    :peek-random-hand :grant-mana-ability})


(def valid-cost-types
  #{:pay-life :pay-x-life :exile-cards :return-land :discard-specific})


(def valid-ability-cost-keys
  #{:tap :sacrifice-self :remove-counter :discard-hand :mana})


(def valid-condition-types
  #{:threshold :no-counters})


(def valid-colors
  #{:white :blue :black :red :green})


(def valid-card-types
  #{:land :instant :sorcery :enchantment :artifact :creature})


(def valid-ability-types
  #{:mana :activated :triggered})


(def valid-trigger-types
  #{:becomes-tapped :land-entered})


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


;; === Effect Spec ===

(s/def :effect/type valid-effect-types)
(s/def :effect/mana ::mana-cost)
(s/def :effect/amount (s/or :int int? :keyword keyword? :dynamic map?))
(s/def :effect/count (s/or :int int? :keyword keyword? :dynamic map?))
(s/def :effect/target keyword?)
(s/def :effect/target-ref keyword?)
(s/def :effect/selection keyword?)
(s/def :effect/criteria map?)
(s/def :effect/destination keyword?)
(s/def :effect/counter-type keyword?)
(s/def :effect/x keyword?)
(s/def :effect/select-count int?)
(s/def :effect/selected-zone keyword?)
(s/def :effect/remainder-zone keyword?)
(s/def :effect/order-remainder? boolean?)
(s/def :effect/zone keyword?)
(s/def :effect/ability map?)
(s/def :restriction/type valid-restriction-types)


(s/def :effect/condition
  (s/keys :req [:condition/type]
          :opt [:condition/target :condition/counter-type]))


(s/def :effect/unless-pay ::mana-cost)


(s/def ::effect
  (s/keys :req [:effect/type]
          :opt [:effect/mana :effect/amount :effect/count
                :effect/target :effect/target-ref
                :effect/selection :effect/criteria
                :effect/destination :effect/counter-type
                :effect/condition :effect/x
                :effect/select-count :effect/selected-zone
                :effect/remainder-zone :effect/order-remainder?
                :effect/zone :effect/unless-pay
                :effect/ability
                :restriction/type]))


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


(s/def ::targeting
  (s/keys :req [:target/id :target/type]
          :opt [:target/zone :target/controller
                :target/criteria :target/options
                :target/required]))


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


(s/def ::trigger
  (s/keys :req [:trigger/type :trigger/effects]
          :opt [:trigger/description :trigger/filter]))


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


;; === Alternate Cost Spec (Flashback) ===

(s/def :alternate/id keyword?)
(s/def :alternate/zone keyword?)
(s/def :alternate/mana-cost ::mana-cost)
(s/def :alternate/additional-costs (s/coll-of ::additional-cost :kind vector?))
(s/def :alternate/on-resolve keyword?)


(s/def ::alternate-cost
  (s/keys :req [:alternate/id :alternate/zone :alternate/mana-cost]
          :opt [:alternate/additional-costs :alternate/on-resolve]))


;; === Conditional Effects Spec ===

(s/def ::conditional-effects
  (s/map-of keyword? ::effects))


;; === Static Ability Spec ===

(def valid-static-types
  #{:cost-modifier :pt-modifier})


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
(s/def :card/power int?)
(s/def :card/toughness pos-int?)


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
                       :card/static-abilities :card/power :card/toughness])
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
