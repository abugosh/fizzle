(ns fizzle.history.descriptions-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [fizzle.history.descriptions :as descriptions]))


(deftest test-game-events-have-descriptions
  (testing "Game state-changing events return non-nil description strings"
    (is (string? (descriptions/describe-event [:fizzle.events.game/cast-spell])))
    (is (string? (descriptions/describe-event [:fizzle.events.game/resolve-top])))
    (is (string? (descriptions/describe-event [:fizzle.events.game/advance-phase])))
    (is (string? (descriptions/describe-event [:fizzle.events.game/start-turn])))
    (is (string? (descriptions/describe-event [:fizzle.events.game/play-land :some-object-id])))))


(deftest test-ability-events-have-descriptions
  (testing "Ability events return non-nil description strings"
    (is (string? (descriptions/describe-event [:fizzle.events.abilities/activate-mana-ability :obj-1 :black])))
    (is (string? (descriptions/describe-event [:fizzle.events.abilities/activate-ability :obj-1 0])))))


(deftest test-selection-events-return-nil
  (testing "Selection events are mid-resolution choices, not priority actions — return nil"
    (is (nil? (descriptions/describe-event [:fizzle.events.selection/confirm-selection])))
    (is (nil? (descriptions/describe-event [:fizzle.events.selection/confirm-tutor-selection])))
    (is (nil? (descriptions/describe-event [:fizzle.events.selection/confirm-scry-selection])))
    (is (nil? (descriptions/describe-event [:fizzle.events.selection/confirm-cast-time-target])))))


(deftest test-unknown-event-returns-nil
  (testing "Unknown events return nil"
    (is (nil? (descriptions/describe-event [:some.unknown/event])))))


(deftest test-ui-only-events-return-nil
  (testing "UI-only events that don't change game state return nil"
    (is (nil? (descriptions/describe-event [:fizzle.events.game/select-card :obj-1])))
    (is (nil? (descriptions/describe-event [:fizzle.events.selection/toggle-selection :obj-1])))
    (is (nil? (descriptions/describe-event [:fizzle.events.selection/cancel-selection])))
    (is (nil? (descriptions/describe-event [:fizzle.events.game/cancel-mode-selection])))))


(deftest test-mid-action-events-return-nil
  (testing "Mid-action events (mode selection, ability targeting) return nil"
    (is (nil? (descriptions/describe-event [:fizzle.events.game/select-casting-mode {:mode :normal}])))
    (is (nil? (descriptions/describe-event [:fizzle.events.abilities/confirm-ability-target :target-1])))))


(deftest test-init-game-has-description
  (testing "init-game is a priority event and has a description"
    (is (= "Game started" (descriptions/describe-event [:fizzle.events.game/init-game])))))


(deftest test-descriptions-are-non-empty-strings
  (testing "All descriptions that are strings are non-empty"
    (let [events [[:fizzle.events.game/init-game]
                  [:fizzle.events.game/cast-spell]
                  [:fizzle.events.game/resolve-top]
                  [:fizzle.events.game/advance-phase]
                  [:fizzle.events.game/start-turn]
                  [:fizzle.events.game/play-land :obj-1]
                  [:fizzle.events.abilities/activate-mana-ability :obj-1 :black]
                  [:fizzle.events.abilities/activate-ability :obj-1 0]]]
      (doseq [event events]
        (let [desc (descriptions/describe-event event)]
          (is (pos? (count desc)) (str "Description for " (first event) " should be non-empty")))))))
