(ns fizzle.bots.definitions-test
  (:require
    [cljs.test :refer-macros [deftest is]]
    [fizzle.bots.definitions :as defs]))


;; === Burn spec structure ===

(deftest burn-has-one-priority-rule
  (let [rules (:bot/priority-rules (defs/get-spec :burn))]
    (is (= 1 (count rules)))
    (is (= :auto (:rule/mode (first rules))))))


(deftest burn-priority-rule-has-three-conditions
  (let [rule (first (:bot/priority-rules (defs/get-spec :burn)))
        conditions (:rule/conditions rule)]
    (is (= 3 (count conditions)))
    (is (= :zone-contains (:check (first conditions))))
    (is (= :has-untapped-source (:check (second conditions))))
    (is (= :stack-empty (:check (nth conditions 2))))))


(deftest burn-priority-rule-action-casts-bolt-at-opponent
  (let [rule (first (:bot/priority-rules (defs/get-spec :burn)))
        action (:rule/action rule)]
    (is (= :cast-spell (:action action)))
    (is (= :lightning-bolt (:card-id action)))
    (is (= :opponent (:target action)))))


(deftest get-deck-returns-nil-for-unknown
  (is (nil? (defs/get-deck :unknown))))
