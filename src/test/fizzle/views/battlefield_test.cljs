(ns fizzle.views.battlefield-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.views.battlefield :as battlefield]
    [fizzle.views.card-styles :as card-styles]))


;; === mana-bg-class ===

(deftest mana-bg-class-all-colors
  (testing "each mana color returns correct bg + text classes"
    (doseq [[color expected] [[:white "bg-mana-bg-white text-mana-white"]
                              [:blue  "bg-mana-bg-blue text-mana-blue"]
                              [:black "bg-mana-bg-black text-mana-black"]
                              [:red   "bg-mana-bg-red text-mana-red"]
                              [:green "bg-mana-bg-green text-mana-green"]]]
      (is (= expected (battlefield/mana-bg-class color))
          (str "Failed for " color)))))


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
    (is (= "border-type-border-creature" (card-styles/get-type-border-class #{:creature} false)))
    (is (= "border-type-border-creature-tapped" (card-styles/get-type-border-class #{:creature} true)))))


(deftest get-type-border-class-land
  (testing "land types return brown border"
    (is (= "border-type-border-land" (card-styles/get-type-border-class #{:land} false)))
    (is (= "border-type-border-land-tapped" (card-styles/get-type-border-class #{:land} true)))))


(deftest get-type-border-class-artifact
  (testing "artifact types return gray border"
    (is (= "border-type-border-artifact" (card-styles/get-type-border-class #{:artifact} false)))
    (is (= "border-type-border-artifact-tapped" (card-styles/get-type-border-class #{:artifact} true)))))


(deftest get-type-border-class-enchantment
  (testing "enchantment types return purple border"
    (is (= "border-type-border-enchantment" (card-styles/get-type-border-class #{:enchantment} false)))
    (is (= "border-type-border-enchantment-tapped" (card-styles/get-type-border-class #{:enchantment} true)))))


(deftest get-type-border-class-artifact-creature
  (testing "artifact-creature uses creature priority (green border)"
    (is (= "border-type-border-creature" (card-styles/get-type-border-class #{:artifact :creature} false)))))


(deftest get-type-border-class-land-creature
  (testing "land-creature uses creature priority (green border)"
    (is (= "border-type-border-creature" (card-styles/get-type-border-class #{:land :creature} false)))))


(deftest get-type-border-class-artifact-land
  (testing "artifact-land uses land priority over artifact (brown border)"
    (is (= "border-type-border-land" (card-styles/get-type-border-class #{:artifact :land} false)))))


(deftest get-type-border-class-nil
  (testing "nil types default to artifact border (other)"
    (is (= "border-type-border-artifact" (card-styles/get-type-border-class nil false)))))


;; === get-color-identity-bg-class ===

(deftest get-color-identity-bg-class-mono-colors
  (testing "each single color identity returns correct tint"
    (doseq [[color expected] [[:white "bg-identity-white"]
                              [:blue  "bg-identity-blue"]
                              [:black "bg-identity-black"]
                              [:red   "bg-identity-red"]
                              [:green "bg-identity-green"]]]
      (is (= expected (card-styles/get-color-identity-bg-class #{color} nil))
          (str "Failed for " color)))))


(deftest get-color-identity-bg-class-multicolor
  (testing "multicolor (2+ colors) returns gold tint"
    (is (= "bg-identity-multicolor" (card-styles/get-color-identity-bg-class #{:blue :black} nil)))
    (is (= "bg-identity-multicolor" (card-styles/get-color-identity-bg-class #{:white :blue :black} nil)))))


(deftest get-color-identity-bg-class-colorless-land
  (testing "colorless land returns brown tint"
    (is (= "bg-identity-land" (card-styles/get-color-identity-bg-class #{} #{:land})))
    (is (= "bg-identity-land" (card-styles/get-color-identity-bg-class nil #{:land})))))


(deftest get-color-identity-bg-class-colorless-artifact
  (testing "colorless non-land returns silver tint"
    (is (= "bg-identity-colorless" (card-styles/get-color-identity-bg-class #{} #{:artifact})))
    (is (= "bg-identity-colorless" (card-styles/get-color-identity-bg-class #{} nil)))
    (is (= "bg-identity-colorless" (card-styles/get-color-identity-bg-class nil nil)))))


;; === pt-text-class ===

(deftest pt-text-class-buffed
  (testing ":buffed modification returns green text class"
    (is (= "text-green-400" (battlefield/pt-text-class :buffed)))))


(deftest pt-text-class-debuffed
  (testing ":debuffed modification returns red text class"
    (is (= "text-red-400" (battlefield/pt-text-class :debuffed)))))


(deftest pt-text-class-nil-returns-empty
  (testing "nil modification (unmodified) returns empty string"
    (is (= "" (battlefield/pt-text-class nil)))))
