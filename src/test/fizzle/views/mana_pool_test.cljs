(ns fizzle.views.mana-pool-test
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.views.mana-pool :as mana-pool]))


;; === Mana color class mapping ===

(deftest mana-color-class-white
  (testing "white mana returns text-mana-white"
    (is (= "text-mana-white" (mana-pool/mana-color-class :white)))))


(deftest mana-color-class-blue
  (testing "blue mana returns text-mana-blue"
    (is (= "text-mana-blue" (mana-pool/mana-color-class :blue)))))


(deftest mana-color-class-black
  (testing "black mana returns text-mana-black"
    (is (= "text-mana-black" (mana-pool/mana-color-class :black)))))


(deftest mana-color-class-red
  (testing "red mana returns text-mana-red"
    (is (= "text-mana-red" (mana-pool/mana-color-class :red)))))


(deftest mana-color-class-green
  (testing "green mana returns text-mana-green"
    (is (= "text-mana-green" (mana-pool/mana-color-class :green)))))


(deftest mana-color-class-colorless
  (testing "colorless mana returns text-mana-colorless"
    (is (= "text-mana-colorless" (mana-pool/mana-color-class :colorless)))))


(deftest mana-color-class-unknown
  (testing "unknown mana type falls back to text-mana-colorless"
    (is (= "text-mana-colorless" (mana-pool/mana-color-class :phyrexian)))))
