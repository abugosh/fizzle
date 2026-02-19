(ns fizzle.bots.definitions-test
  (:require
    [cljs.test :refer-macros [deftest is]]
    [fizzle.bots.definitions :as defs]))


;; === get-spec ===

(deftest get-spec-returns-goldfish-spec
  (let [spec (defs/get-spec :goldfish)]
    (is (= "Goldfish" (:bot/name spec)))))


(deftest get-spec-returns-burn-spec
  (let [spec (defs/get-spec :burn)]
    (is (= "Burn" (:bot/name spec)))))


(deftest get-spec-returns-nil-for-unknown
  (is (nil? (defs/get-spec :unknown-archetype))))


;; === Goldfish spec structure ===

(deftest goldfish-deck-has-60-cards
  (let [deck (:bot/deck (defs/get-spec :goldfish))
        total (reduce + 0 (map :count deck))]
    (is (= 60 total))))


(deftest goldfish-deck-is-basic-lands
  (let [deck (:bot/deck (defs/get-spec :goldfish))
        card-ids (set (map :card/id deck))]
    (is (= #{:plains :island :swamp :mountain :forest} card-ids))))


(deftest goldfish-has-empty-priority-rules
  (is (empty? (:bot/priority-rules (defs/get-spec :goldfish)))))


(deftest goldfish-phase-actions-play-land-main1
  (is (= :play-land (get-in (defs/get-spec :goldfish) [:bot/phase-actions :main1]))))


;; === Burn spec structure ===

(deftest burn-deck-has-60-cards
  (let [deck (:bot/deck (defs/get-spec :burn))
        total (reduce + 0 (map :count deck))]
    (is (= 60 total))))


(deftest burn-deck-is-mountains-and-bolts
  (let [deck (:bot/deck (defs/get-spec :burn))
        by-id (into {} (map (fn [e] [(:card/id e) (:count e)])) deck)]
    (is (= 20 (:mountain by-id)))
    (is (= 40 (:lightning-bolt by-id)))))


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


;; === get-deck ===

(deftest get-deck-returns-goldfish-deck
  (let [deck (defs/get-deck :goldfish)]
    (is (= 5 (count deck)))
    (is (every? :card/id deck))))


(deftest get-deck-returns-burn-deck
  (let [deck (defs/get-deck :burn)]
    (is (= 2 (count deck)))))


(deftest get-deck-returns-nil-for-unknown
  (is (nil? (defs/get-deck :unknown))))


;; === list-archetypes ===

(deftest list-archetypes-includes-goldfish-and-burn
  (let [archetypes (set (defs/list-archetypes))]
    (is (contains? archetypes :goldfish))
    (is (contains? archetypes :burn))))
