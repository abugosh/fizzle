(ns fizzle.views.battlefield-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.views.battlefield :as battlefield]))


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
