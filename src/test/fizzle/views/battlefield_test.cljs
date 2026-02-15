(ns fizzle.views.battlefield-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.views.battlefield :as battlefield]
    [fizzle.views.card-styles :as card-styles]))


;; === mana-bg-class ===

(deftest mana-bg-class-white
  (testing "white returns white mana bg + text classes"
    (is (= "bg-mana-bg-white text-mana-white" (battlefield/mana-bg-class :white)))))


(deftest mana-bg-class-blue
  (testing "blue returns blue mana bg + text classes"
    (is (= "bg-mana-bg-blue text-mana-blue" (battlefield/mana-bg-class :blue)))))


(deftest mana-bg-class-black
  (testing "black returns black mana bg + text classes"
    (is (= "bg-mana-bg-black text-mana-black" (battlefield/mana-bg-class :black)))))


(deftest mana-bg-class-red
  (testing "red returns red mana bg + text classes"
    (is (= "bg-mana-bg-red text-mana-red" (battlefield/mana-bg-class :red)))))


(deftest mana-bg-class-green
  (testing "green returns green mana bg + text classes"
    (is (= "bg-mana-bg-green text-mana-green" (battlefield/mana-bg-class :green)))))


(deftest mana-bg-class-unknown
  (testing "unknown color falls back to dim surface"
    (is (= "bg-surface-dim text-mana-colorless" (battlefield/mana-bg-class :phyrexian)))))


;; === get-producible-colors ===

(deftest get-producible-colors-single-produces
  (testing "permanent with :ability/produces {:black 1} returns [:black]"
    (let [obj {:object/card
               {:card/abilities [{:ability/type :mana
                                  :ability/produces {:black 1}}]}}]
      (is (= [:black] (battlefield/get-producible-colors obj))))))


(deftest get-producible-colors-any-mana
  (testing "permanent with :any produces all 5 colors"
    (let [obj {:object/card
               {:card/abilities [{:ability/type :mana
                                  :ability/produces {:any 1}}]}}]
      (is (= [:white :blue :black :red :green]
             (battlefield/get-producible-colors obj))))))


(deftest get-producible-colors-from-effect
  (testing "permanent with :add-mana effect extracts color"
    (let [obj {:object/card
               {:card/abilities [{:ability/type :mana
                                  :ability/effects [{:effect/type :add-mana
                                                     :effect/mana {:red 3}}]}]}}]
      (is (= [:red] (vec (battlefield/get-producible-colors obj)))))))


(deftest get-producible-colors-no-mana-abilities
  (testing "permanent with no mana abilities returns empty"
    (let [obj {:object/card
               {:card/abilities [{:ability/type :activated
                                  :ability/name "Sacrifice"}]}}]
      (is (= [] (vec (battlefield/get-producible-colors obj)))))))


(deftest get-producible-colors-no-abilities
  (testing "permanent with no abilities at all returns empty"
    (let [obj {:object/card {:card/abilities []}}]
      (is (= [] (vec (battlefield/get-producible-colors obj)))))))


(deftest get-producible-colors-multiple-colors
  (testing "permanent producing multiple colors lists all distinct"
    (let [obj {:object/card
               {:card/abilities [{:ability/type :mana
                                  :ability/produces {:blue 1 :red 1}}]}}]
      (is (= #{:blue :red} (set (battlefield/get-producible-colors obj)))))))


;; === get-activated-abilities ===

(deftest get-activated-abilities-none
  (testing "card with no abilities returns empty"
    (let [obj {:object/card {:card/abilities []}}]
      (is (= [] (battlefield/get-activated-abilities obj))))))


(deftest get-activated-abilities-mana-only
  (testing "card with only mana abilities returns empty (filtered out)"
    (let [obj {:object/card
               {:card/abilities [{:ability/type :mana
                                  :ability/produces {:black 1}}]}}]
      (is (= [] (battlefield/get-activated-abilities obj))))))


(deftest get-activated-abilities-activated
  (testing "card with activated ability returns indexed tuple"
    (let [ability {:ability/type :activated :ability/name "Sacrifice"}
          obj {:object/card {:card/abilities [ability]}}]
      (is (= [[0 ability]] (battlefield/get-activated-abilities obj))))))


(deftest get-activated-abilities-mixed
  (testing "card with mixed ability types returns only activated with correct indices"
    (let [mana-ability {:ability/type :mana :ability/produces {:black 1}}
          activated {:ability/type :activated :ability/name "Draw"}
          obj {:object/card {:card/abilities [mana-ability activated]}}]
      (is (= [[1 activated]] (battlefield/get-activated-abilities obj))))))


;; === get-type-border-class ===

(deftest get-type-border-class-creature
  (testing "creature types return green border"
    (is (= "border-type-creature" (card-styles/get-type-border-class #{:creature} false)))
    (is (= "border-type-creature-tapped" (card-styles/get-type-border-class #{:creature} true)))))


(deftest get-type-border-class-land
  (testing "land types return brown border"
    (is (= "border-type-land" (card-styles/get-type-border-class #{:land} false)))
    (is (= "border-type-land-tapped" (card-styles/get-type-border-class #{:land} true)))))


(deftest get-type-border-class-artifact
  (testing "artifact types return gray border"
    (is (= "border-type-artifact" (card-styles/get-type-border-class #{:artifact} false)))
    (is (= "border-type-artifact-tapped" (card-styles/get-type-border-class #{:artifact} true)))))


(deftest get-type-border-class-enchantment
  (testing "enchantment types return purple border"
    (is (= "border-type-enchantment" (card-styles/get-type-border-class #{:enchantment} false)))
    (is (= "border-type-enchantment-tapped" (card-styles/get-type-border-class #{:enchantment} true)))))


(deftest get-type-border-class-artifact-creature
  (testing "artifact-creature uses creature priority (green border)"
    (is (= "border-type-creature" (card-styles/get-type-border-class #{:artifact :creature} false)))))


(deftest get-type-border-class-land-creature
  (testing "land-creature uses creature priority (green border)"
    (is (= "border-type-creature" (card-styles/get-type-border-class #{:land :creature} false)))))


(deftest get-type-border-class-artifact-land
  (testing "artifact-land uses land priority over artifact (brown border)"
    (is (= "border-type-land" (card-styles/get-type-border-class #{:artifact :land} false)))))


(deftest get-type-border-class-nil
  (testing "nil types default to artifact border (other)"
    (is (= "border-type-artifact" (card-styles/get-type-border-class nil false)))))


;; === get-color-identity-bg-class ===

(deftest get-color-identity-bg-class-white
  (testing "white color identity returns cream tint"
    (is (= "bg-color-identity-white" (card-styles/get-color-identity-bg-class #{:white})))))


(deftest get-color-identity-bg-class-blue
  (testing "blue color identity returns blue tint"
    (is (= "bg-color-identity-blue" (card-styles/get-color-identity-bg-class #{:blue})))))


(deftest get-color-identity-bg-class-black
  (testing "black color identity returns dark tint"
    (is (= "bg-color-identity-black" (card-styles/get-color-identity-bg-class #{:black})))))


(deftest get-color-identity-bg-class-red
  (testing "red color identity returns red tint"
    (is (= "bg-color-identity-red" (card-styles/get-color-identity-bg-class #{:red})))))


(deftest get-color-identity-bg-class-green
  (testing "green color identity returns green tint"
    (is (= "bg-color-identity-green" (card-styles/get-color-identity-bg-class #{:green})))))


(deftest get-color-identity-bg-class-multicolor
  (testing "multicolor (2+ colors) returns gold tint"
    (is (= "bg-color-identity-multicolor" (card-styles/get-color-identity-bg-class #{:blue :black})))
    (is (= "bg-color-identity-multicolor" (card-styles/get-color-identity-bg-class #{:white :blue :black})))))


(deftest get-color-identity-bg-class-colorless
  (testing "colorless (empty or nil) returns neutral/default"
    (is (= "bg-perm-bg" (card-styles/get-color-identity-bg-class #{})))
    (is (= "bg-perm-bg" (card-styles/get-color-identity-bg-class nil)))))
