(ns fizzle.cards.blue.portent-test
  "Tests for Portent card."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.blue.portent :as portent]))


;; === A. Card Definition Tests ===

(deftest portent-card-definition-test
  (testing "Portent has correct card attributes"
    (let [card portent/card]
      (is (= :portent (:card/id card)))
      (is (= "Portent" (:card/name card)))
      (is (= 1 (:card/cmc card)))
      (is (= {:blue 1} (:card/mana-cost card)))
      (is (= #{:blue} (:card/colors card)))
      (is (= #{:sorcery} (:card/types card)))
      (is (string? (:card/text card))))))


(deftest portent-effects-structure-test
  (testing "Portent has peek-and-reorder + delayed-draw effects"
    (let [effects (:card/effects portent/card)]
      (is (= 2 (count effects)))
      (is (= :peek-and-reorder (:effect/type (first effects))))
      (is (= 3 (:effect/count (first effects))))
      (is (= :player (:effect/target-ref (first effects))))
      (is (= :grant-delayed-draw (:effect/type (second effects))))
      (is (= :controller (:effect/target (second effects)))))))


(deftest portent-targeting-test
  (testing "Portent has player targeting"
    (let [targeting (:card/targeting portent/card)]
      (is (= 1 (count targeting)))
      (is (= :player (:target/id (first targeting))))
      (is (= :player (:target/type (first targeting))))
      (is (true? (:target/required (first targeting)))))))
