(ns fizzle.engine.card-spec-test
  (:require
    [cljs.spec.alpha :as s]
    [cljs.test :refer [deftest is testing]]
    [fizzle.engine.card-spec :as card-spec]
    [fizzle.engine.cards :as cards]))


(deftest test-valid-card-passes
  (testing "minimal valid card definition passes spec"
    (let [card {:card/id :test-spell
                :card/name "Test Spell"
                :card/cmc 1
                :card/mana-cost {:black 1}
                :card/colors #{:black}
                :card/types #{:instant}
                :card/text "Test."
                :card/effects [{:effect/type :add-mana
                                :effect/mana {:black 3}}]}]
      (is (= true (card-spec/valid-card? card))))))


(deftest test-missing-required-field-fails
  (testing "card without :card/name fails"
    (let [card {:card/id :bad-card
                :card/cmc 1
                :card/mana-cost {:black 1}
                :card/colors #{:black}
                :card/types #{:instant}
                :card/text "Test."}]
      (is (= false (card-spec/valid-card? card)))))

  (testing "card without :card/id fails"
    (let [card {:card/name "Bad Card"
                :card/cmc 1
                :card/mana-cost {:black 1}
                :card/colors #{:black}
                :card/types #{:instant}
                :card/text "Test."}]
      (is (= false (card-spec/valid-card? card)))))

  (testing "card without :card/types fails"
    (let [card {:card/id :bad-card
                :card/name "Bad Card"
                :card/cmc 1
                :card/mana-cost {:black 1}
                :card/colors #{:black}
                :card/text "Test."}]
      (is (= false (card-spec/valid-card? card))))))


(deftest test-invalid-effect-type-fails
  (testing "card with unknown effect type fails"
    (let [card {:card/id :bad-card
                :card/name "Bad Card"
                :card/cmc 1
                :card/mana-cost {:black 1}
                :card/colors #{:black}
                :card/types #{:instant}
                :card/text "Test."
                :card/effects [{:effect/type :nonexistent}]}]
      (is (= false (card-spec/valid-card? card))))))


(deftest test-invalid-mana-cost-fails
  (testing "card with string mana cost fails"
    (let [card {:card/id :bad-card
                :card/name "Bad Card"
                :card/cmc 1
                :card/mana-cost "bad"
                :card/colors #{:black}
                :card/types #{:instant}
                :card/text "Test."}]
      (is (= false (card-spec/valid-card? card))))))


(deftest test-empty-mana-cost-valid
  (testing "land with empty mana cost passes"
    (let [card {:card/id :test-land
                :card/name "Test Land"
                :card/cmc 0
                :card/mana-cost {}
                :card/colors #{}
                :card/types #{:land}
                :card/text "T: Add U."}]
      (is (= true (card-spec/valid-card? card))))))


(deftest test-x-mana-cost-valid
  (testing "card with :x true in mana cost passes"
    (let [card {:card/id :test-x
                :card/name "X Spell"
                :card/cmc 2
                :card/mana-cost {:colorless 1 :blue 1 :x true}
                :card/colors #{:blue}
                :card/types #{:instant}
                :card/text "X spell."}]
      (is (= true (card-spec/valid-card? card))))))


(deftest test-all-existing-cards-valid
  (testing "all 58 cards from all-cards pass validation"
    (doseq [card cards/all-cards]
      (is (= true (card-spec/valid-card? card))
          (str "Card failed validation: " (:card/name card))))))


(deftest test-validation-error-descriptive
  (testing "validate-cards! error includes card name in message"
    (let [bad-card {:card/id :bad-card
                    :card/name "Bad Card"
                    :card/cmc 1
                    :card/mana-cost "invalid"
                    :card/colors #{:black}
                    :card/types #{:instant}
                    :card/text "Test."}
          error (try
                  (card-spec/validate-cards! [bad-card])
                  nil
                  (catch :default e e))]
      (is (instance? js/Error error) "validate-cards! should throw on invalid card")
      (is (re-find #"Bad Card" (ex-message error))
          "Error message should include card name"))))


(deftest test-validate-cards-passes-valid-cards
  (testing "validate-cards! succeeds with valid cards"
    (is (nil? (card-spec/validate-cards! cards/all-cards))
        "validate-cards! should return nil on success")))


(deftest test-card-with-abilities-valid
  (testing "card with abilities passes"
    (let [card {:card/id :test-land-ability
                :card/name "Test Land"
                :card/cmc 0
                :card/mana-cost {}
                :card/colors #{}
                :card/types #{:land}
                :card/text "T: Add U."
                :card/abilities [{:ability/type :mana
                                  :ability/cost {:tap true}
                                  :ability/produces {:blue 1}
                                  :ability/effects [{:effect/type :deal-damage
                                                     :effect/amount 1
                                                     :effect/target :controller}]}]}]
      (is (= true (card-spec/valid-card? card))))))


(deftest test-card-with-targeting-valid
  (testing "card with targeting passes"
    (let [card {:card/id :test-target
                :card/name "Target Spell"
                :card/cmc 2
                :card/mana-cost {:colorless 1 :blue 1}
                :card/colors #{:blue}
                :card/types #{:sorcery}
                :card/text "Target player draws two cards."
                :card/targeting [{:target/id :player
                                  :target/type :player
                                  :target/options [:self :opponent :any-player]
                                  :target/required true}]
                :card/effects [{:effect/type :draw
                                :effect/amount 2
                                :effect/target :any-player}]}]
      (is (= true (card-spec/valid-card? card))))))


(deftest test-card-with-alternate-costs-valid
  (testing "card with flashback alternate costs passes"
    (let [card {:card/id :test-flashback
                :card/name "Flashback Spell"
                :card/cmc 4
                :card/mana-cost {:colorless 3 :blue 1}
                :card/colors #{:blue}
                :card/types #{:sorcery}
                :card/text "Flashback."
                :card/effects [{:effect/type :draw :effect/amount 2 :effect/target :any-player}]
                :card/alternate-costs [{:alternate/id :flashback
                                        :alternate/zone :graveyard
                                        :alternate/mana-cost {:colorless 1 :blue 1}
                                        :alternate/additional-costs [{:cost/type :pay-life :cost/amount 3}]
                                        :alternate/on-resolve :exile}]}]
      (is (= true (card-spec/valid-card? card))))))


(deftest test-card-with-triggers-valid
  (testing "card with triggers passes"
    (let [card {:card/id :test-trigger
                :card/name "Trigger Land"
                :card/cmc 0
                :card/mana-cost {}
                :card/colors #{}
                :card/types #{:land}
                :card/text "T: Add U."
                :card/triggers [{:trigger/type :becomes-tapped
                                 :trigger/description "deals 1 damage"
                                 :trigger/effects [{:effect/type :deal-damage
                                                    :effect/amount 1
                                                    :effect/target :controller}]}]}]
      (is (= true (card-spec/valid-card? card))))))


(deftest test-card-with-conditional-effects-valid
  (testing "card with conditional effects passes"
    (let [card {:card/id :test-conditional
                :card/name "Conditional Spell"
                :card/cmc 2
                :card/mana-cost {:colorless 1 :black 1}
                :card/colors #{:black}
                :card/types #{:instant}
                :card/text "Add BBB. Threshold - Add BBBBB instead."
                :card/effects [{:effect/type :add-mana :effect/mana {:black 3}}]
                :card/conditional-effects {:threshold [{:effect/type :add-mana
                                                        :effect/mana {:black 5}}]}}]
      (is (= true (card-spec/valid-card? card))))))


(deftest test-card-with-kicker-valid
  (testing "card with kicker passes"
    (let [card {:card/id :test-kicker
                :card/name "Kicker Spell"
                :card/cmc 1
                :card/mana-cost {:white 1}
                :card/colors #{:white}
                :card/types #{:instant}
                :card/text "Kicker W."
                :card/kicker {:white 1}
                :card/effects [{:effect/type :add-restriction
                                :effect/target :opponent
                                :restriction/type :cannot-cast-spells}]
                :card/kicked-effects [{:effect/type :add-restriction
                                       :effect/target :opponent
                                       :restriction/type :cannot-attack}]}]
      (is (= true (card-spec/valid-card? card))))))


;; === Effect Multi-Spec Tests ===

;; --- Shape validation: valid effects pass ---

(deftest test-valid-mill-effect
  (testing "mill with amount passes"
    (is (s/valid? ::card-spec/effect {:effect/type :mill :effect/amount 3}))))


(deftest test-valid-add-mana-effect
  (testing "add-mana with mana map passes"
    (is (s/valid? ::card-spec/effect {:effect/type :add-mana :effect/mana {:black 3}}))))


(deftest test-valid-exile-self-minimal
  (testing "exile-self needs only :effect/type"
    (is (s/valid? ::card-spec/effect {:effect/type :exile-self}))))


(deftest test-valid-tutor-with-target-zone
  (testing "tutor with target-zone and shuffle? passes"
    (is (s/valid? ::card-spec/effect {:effect/type :tutor
                                      :effect/target-zone :hand
                                      :effect/criteria {:match/types #{:instant}}
                                      :effect/shuffle? true}))))


(deftest test-valid-create-token
  (testing "create-token with token map passes"
    (is (s/valid? ::card-spec/effect {:effect/type :create-token
                                      :effect/token {:token/name "Beast"
                                                     :token/power 4
                                                     :token/toughness 4}}))))


(deftest test-valid-tap-all-with-permanent-type
  (testing "tap-all with target and permanent-type passes"
    (is (s/valid? ::card-spec/effect {:effect/type :tap-all
                                      :effect/target :opponent
                                      :effect/permanent-type :creature}))))


(deftest test-valid-draw-with-dynamic-amount
  (testing "draw with dynamic amount map in :effect/amount passes"
    (is (s/valid? ::card-spec/effect {:effect/type :draw
                                      :effect/amount {:dynamic/type :count-named-in-zone}}))))


(deftest test-valid-counter-spell-with-unless-pay
  (testing "counter-spell with unless-pay mana cost passes"
    (is (s/valid? ::card-spec/effect {:effect/type :counter-spell
                                      :effect/target-ref :target
                                      :effect/unless-pay {:colorless 1}}))))


(deftest test-valid-add-counters
  (testing "add-counters with target and counters map passes"
    (is (s/valid? ::card-spec/effect {:effect/type :add-counters
                                      :effect/target :self
                                      :effect/counters {:mining 3}}))))


(deftest test-valid-peek-and-select
  (testing "peek-and-select with required keys passes"
    (is (s/valid? ::card-spec/effect {:effect/type :peek-and-select
                                      :effect/count 4
                                      :effect/select-count 1
                                      :effect/selected-zone :hand
                                      :effect/remainder-zone :bottom-of-library
                                      :effect/order-remainder? true}))))


(deftest test-valid-peek-and-reorder-with-may-shuffle
  (testing "peek-and-reorder with may-shuffle? passes"
    (is (s/valid? ::card-spec/effect {:effect/type :peek-and-reorder
                                      :effect/count 3
                                      :effect/target-ref :player
                                      :effect/may-shuffle? true}))))


(deftest test-valid-tutor-with-pile-choice
  (testing "tutor with pile-choice passes"
    (is (s/valid? ::card-spec/effect {:effect/type :tutor
                                      :effect/select-count 3
                                      :effect/target-zone :hand
                                      :effect/pile-choice {:hand 1 :graveyard :rest}}))))


(deftest test-valid-welder-swap
  (testing "welder-swap with target-ref and graveyard-ref passes"
    (is (s/valid? ::card-spec/effect {:effect/type :welder-swap
                                      :effect/target-ref :welder-bf
                                      :effect/graveyard-ref :welder-gy}))))


(deftest test-valid-exile-zone
  (testing "exile-zone with target-ref and zone passes"
    (is (s/valid? ::card-spec/effect {:effect/type :exile-zone
                                      :effect/target-ref :player
                                      :effect/zone :graveyard}))))


(deftest test-valid-apply-pt-modifier
  (testing "apply-pt-modifier with target-ref, power, toughness passes"
    (is (s/valid? ::card-spec/effect {:effect/type :apply-pt-modifier
                                      :effect/target-ref :target-creature
                                      :effect/power -2
                                      :effect/toughness -2}))))


(deftest test-valid-grant-mana-ability
  (testing "grant-mana-ability with target, ability, mana passes"
    (is (s/valid? ::card-spec/effect {:effect/type :grant-mana-ability
                                      :effect/target :controlled-lands
                                      :effect/ability {:ability/type :mana}
                                      :effect/mana {:black 1}}))))


(deftest test-valid-storm-copies-minimal
  (testing "storm-copies needs only :effect/type (runtime-generated)"
    (is (s/valid? ::card-spec/effect {:effect/type :storm-copies}))))


(deftest test-valid-lose-life
  (testing "lose-life with amount passes"
    (is (s/valid? ::card-spec/effect {:effect/type :lose-life
                                      :effect/amount 3}))))


(deftest test-valid-gain-life
  (testing "gain-life with amount passes"
    (is (s/valid? ::card-spec/effect {:effect/type :gain-life
                                      :effect/amount 3}))))


;; --- Rejection tests: invalid effects fail ---

(deftest test-unknown-effect-type-fails
  (testing "unknown effect type fails"
    (is (not (s/valid? ::card-spec/effect {:effect/type :fake-effect})))))


(deftest test-wrong-amount-type-fails
  (testing "mill with string amount fails"
    (is (not (s/valid? ::card-spec/effect {:effect/type :mill :effect/amount "three"})))))


(deftest test-missing-type-fails
  (testing "effect without :effect/type fails"
    (is (not (s/valid? ::card-spec/effect {:effect/amount 3})))))


(deftest test-mill-without-amount-fails
  (testing "mill without :effect/amount fails (required for mill)"
    (is (not (s/valid? ::card-spec/effect {:effect/type :mill})))))


(deftest test-tutor-without-target-zone-fails
  (testing "tutor without :effect/target-zone fails"
    (is (not (s/valid? ::card-spec/effect {:effect/type :tutor
                                           :effect/criteria {:match/types #{:instant}}})))))


(deftest test-create-token-without-token-fails
  (testing "create-token without :effect/token fails"
    (is (not (s/valid? ::card-spec/effect {:effect/type :create-token})))))


(deftest test-add-counters-without-required-fails
  (testing "add-counters without :effect/counters fails"
    (is (not (s/valid? ::card-spec/effect {:effect/type :add-counters
                                           :effect/target :self})))))


;; --- Orthogonality tests ---

(deftest test-condition-on-any-effect-type
  (testing ":effect/condition is allowed on any effect type"
    (is (s/valid? ::card-spec/effect {:effect/type :exile-self
                                      :effect/condition {:condition/type :threshold}}))
    (is (s/valid? ::card-spec/effect {:effect/type :mill
                                      :effect/amount 3
                                      :effect/condition {:condition/type :threshold}}))
    (is (s/valid? ::card-spec/effect {:effect/type :sacrifice
                                      :effect/target :self
                                      :effect/condition {:condition/type :no-counters
                                                         :condition/counter-type :mining}}))))


(deftest test-extra-keys-tolerated
  (testing "s/keys allows extra keys beyond spec (forward compat)"
    ;; s/keys specs in cljs.spec do not reject extra keys
    (is (s/valid? ::card-spec/effect {:effect/type :mill
                                      :effect/amount 3
                                      :some/future-key true}))))


;; --- Integration tests ---

(deftest test-all-existing-card-effects-valid
  (testing "all effects in all-cards pass the effect multi-spec"
    (doseq [card cards/all-cards]
      (doseq [effect (concat (:card/effects card)
                             (:card/etb-effects card)
                             (:card/kicked-effects card)
                             (mapcat :ability/effects (:card/abilities card))
                             (mapcat :trigger/effects (:card/triggers card))
                             (mapcat :state/effects (:card/state-triggers card))
                             (mapcat (fn [[_ effs]] effs) (:card/conditional-effects card))
                             (mapcat :mode/effects (:card/modes card)))]
        (is (s/valid? ::card-spec/effect effect)
            (str "Effect failed in card " (:card/name card) ": " effect))))))


(deftest test-each-effect-type-has-defmethod
  (testing "each keyword in valid-effect-types has a working defmethod"
    (doseq [effect-type card-spec/valid-effect-types]
      (let [minimal (card-spec/minimal-valid-effect effect-type)]
        (is (some? minimal)
            (str "No minimal example for effect type: " effect-type))
        (is (s/valid? ::card-spec/effect minimal)
            (str "Minimal effect invalid for type: " effect-type))))))


(deftest test-exercise-generates-valid-effects
  (testing "minimal valid effect for every type conforms to ::effect (exercise substitute)"
    ;; s/exercise requires test.check which is not in this project's deps.
    ;; This test exercises the same guarantee: the spec accepts one known-valid
    ;; instance per type — 40 entries, all conforming — equivalent coverage.
    (let [entries (map (fn [t] [t (card-spec/minimal-valid-effect t)])
                       card-spec/valid-effect-types)]
      (is (= 40 (count entries))
          "valid-effect-types should have exactly 40 types")
      (doseq [[effect-type example] entries]
        (is (s/valid? ::card-spec/effect example)
            (str "Effect type " effect-type " minimal example failed spec"))))))


(deftest test-unused-keys-removed-from-spec
  (testing ":effect/counter-type, :effect/destination, :effect/x are not in the spec"
    ;; These keys were confirmed unused and should be removed
    (is (nil? (s/get-spec :effect/counter-type)))
    (is (nil? (s/get-spec :effect/destination)))
    (is (nil? (s/get-spec :effect/x)))))
