(ns fizzle.storage-test
  (:require
    [cljs.test :refer-macros [deftest testing is use-fixtures]]
    [fizzle.storage :as storage]))


;; Mock localStorage for Node.js tests
(def ^:private mock-storage (atom {}))


(def ^:private create-mock-storage
  (fn []
    #js {:getItem (fn [key] (get @mock-storage key nil))
         :setItem (fn [key value] (swap! mock-storage assoc key value) nil)
         :removeItem (fn [key] (swap! mock-storage dissoc key) nil)
         :clear (fn [] (reset! mock-storage {}) nil)
         :length 0
         :key (fn [_] nil)}))


;; Always use our test mock storage for this test file
(set! js/localStorage (create-mock-storage))


;; Clear storage before each test
(use-fixtures :each
  {:before (fn [] (reset! mock-storage {}))
   :after (fn [] (reset! mock-storage {}))})


(deftest test-presets-round-trip
  (testing "save-presets! and load-presets round-trip correctly"
    (let [presets {"Test" {:main-deck [{:card/id :dark-ritual :count 4}]
                           :sideboard []
                           :clock-turns 4
                           :selected-deck :iggy-pop}}]
      (storage/save-presets! presets)
      (is (= presets (storage/load-presets))
          "Loaded presets should equal saved presets"))))


(deftest test-load-presets-empty-returns-default
  (testing "load-presets returns empty map when nothing stored"
    (is (= {} (storage/load-presets))
        "Should return {} when localStorage is empty")))


(deftest test-load-presets-corrupt-returns-default
  (testing "load-presets returns empty map on corrupt data"
    (.setItem js/localStorage "fizzle-presets" "{:unclosed")
    (is (= {} (storage/load-presets))
        "Should return {} when localStorage contains corrupt data")))


(deftest test-last-preset-round-trip
  (testing "save-last-preset! and load-last-preset round-trip correctly"
    (storage/save-last-preset! "My Preset")
    (is (= "My Preset" (storage/load-last-preset))
        "Loaded last-preset should equal saved value")))


(deftest test-load-last-preset-missing-returns-nil
  (testing "load-last-preset returns nil when nothing stored"
    (is (nil? (storage/load-last-preset))
        "Should return nil when no last-preset stored")))


;; === default-stops ===

(deftest default-stops-has-main-phases-for-player
  (is (= #{:main1 :main2} (:player (storage/default-stops)))))


(deftest default-stops-has-empty-set-for-opponent
  (is (= #{} (:opponent (storage/default-stops)))))


;; === toggle-stop ===

(deftest toggle-stop-adds-missing-phase
  (let [stops' (storage/toggle-stop (storage/default-stops) :player :combat)]
    (is (contains? (:player stops') :combat))
    (is (contains? (:player stops') :main1))
    (is (contains? (:player stops') :main2))))


(deftest toggle-stop-removes-existing-phase
  (let [stops' (storage/toggle-stop (storage/default-stops) :player :main1)]
    (is (not (contains? (:player stops') :main1)))
    (is (contains? (:player stops') :main2))))


(deftest toggle-stop-works-for-opponent-role
  (let [stops' (storage/toggle-stop (storage/default-stops) :opponent :end)]
    (is (contains? (:opponent stops') :end))))


(deftest toggle-stop-is-symmetric
  (let [stops (storage/default-stops)
        stops' (-> stops
                   (storage/toggle-stop :player :combat)
                   (storage/toggle-stop :player :combat))]
    (is (= (:player stops) (:player stops'))
        "Adding then removing same stop returns to original")))


;; === stops persistence ===

(deftest stops-round-trip
  (testing "save-stops! and load-stops round-trip correctly"
    (let [stops {:player #{:main1 :main2 :combat} :opponent #{:end}}]
      (storage/save-stops! stops)
      (is (= stops (storage/load-stops))))))


(deftest load-stops-returns-default-when-empty
  (is (= (storage/default-stops) (storage/load-stops))
      "Should return default stops when nothing stored"))
