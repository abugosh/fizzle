(ns fizzle.views.binary-choice-test
  "Tests for binary-choice-view component and :binary-choice modal dispatch.

   binary-choice-view is a domain-agnostic inline component that renders
   one button per entry in :selection/choices. It handles both:
     - :replacement-choice domain: toggle dispatches full choice map
     - :unless-pay domain: toggle dispatches keyword (:pay or :decline)

   The dispatch shape is determined by :selection/valid-targets:
     - vector of keywords → toggle with (:choice/action choice) keyword
     - absent or non-keyword-vec → toggle with full choice map

   Tests verify:
     A. Component renders one button per :selection/choices entry
     B. Button labels show :choice/label text from each choice
     C. :binary-choice defmethod in render-selection returns [:inline ...]
     D. Correct toggle shape for :replacement-choice (full map dispatched on click)
     E. Correct toggle shape for :unless-pay (keyword dispatched on click)
     F. Builder: build-unless-pay-selection includes :selection/choices"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.events.selection :as selection-events]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.events.selection.zone-ops]
    [fizzle.test-helpers :as th]
    [fizzle.views.modals :as modals]
    [fizzle.views.selection.binary-choice :as binary-choice-view]
    [re-frame.core :as rf]))


;; ---------------------------------------------------------------------------
;; A. Component renders one button per :selection/choices entry

(def ^:private replacement-choice-selection
  {:selection/mechanism :binary-choice
   :selection/domain    :replacement-choice
   :selection/choices   [{:choice/action :proceed   :choice/label "Discard a land"}
                         {:choice/action :redirect  :choice/label "Sacrifice (go to graveyard)"}]
   :selection/selected  #{}})


(def ^:private unless-pay-selection
  {:selection/mechanism :binary-choice
   :selection/domain    :unless-pay
   :selection/choices   [{:choice/action :pay     :choice/label "Pay {3}"}
                         {:choice/action :decline :choice/label "Decline"}]
   :selection/valid-targets [:pay :decline]
   :selection/selected  #{}})


(deftest binary-choice-view-renders-all-choices-test
  (testing "binary-choice-view renders one element per :selection/choices entry"
    (let [result (str (binary-choice-view/binary-choice-view replacement-choice-selection))]
      (is (re-find #"Discard a land" result)
          "Should render first choice label")
      (is (re-find #"Sacrifice" result)
          "Should render second choice label"))))


(deftest binary-choice-view-renders-three-choices-test
  (testing "binary-choice-view renders all choices in a 3-choice selection"
    (let [sel {:selection/mechanism :binary-choice
               :selection/domain    :replacement-choice
               :selection/choices   [{:choice/action :a :choice/label "Option A"}
                                     {:choice/action :b :choice/label "Option B"}
                                     {:choice/action :c :choice/label "Option C"}]
               :selection/selected  #{}}
          result (str (binary-choice-view/binary-choice-view sel))]
      (is (re-find #"Option A" result) "Should render Option A")
      (is (re-find #"Option B" result) "Should render Option B")
      (is (re-find #"Option C" result) "Should render Option C"))))


;; ---------------------------------------------------------------------------
;; B. Button labels show :choice/label text

(deftest binary-choice-view-labels-from-choice-label-test
  (testing "binary-choice-view uses :choice/label as button text"
    (let [result (str (binary-choice-view/binary-choice-view unless-pay-selection))]
      (is (re-find #"Pay \{3\}" result)
          "Pay button label should come from :choice/label")
      (is (re-find #"Decline" result)
          "Decline button label should come from :choice/label"))))


;; ---------------------------------------------------------------------------
;; C. :binary-choice defmethod in render-selection returns [:inline ...] for all domains

(deftest binary-choice-render-selection-replacement-choice-returns-inline-test
  (testing ":binary-choice render-selection returns [:inline ...] for :replacement-choice domain"
    (let [result (modals/render-selection replacement-choice-selection nil)]
      (is (vector? result)
          "render-selection should return a vector")
      (is (= :inline (first result))
          "First element should be :inline tag for :binary-choice mechanism")
      (is (vector? (second result))
          "Second element should be the hiccup component vector"))))


(deftest binary-choice-render-selection-unless-pay-returns-inline-test
  (testing ":binary-choice render-selection returns [:inline ...] for :unless-pay domain"
    (let [result (modals/render-selection unless-pay-selection nil)]
      (is (vector? result)
          "render-selection should return a vector")
      (is (= :inline (first result))
          "First element should be :inline tag for :binary-choice mechanism")
      (is (vector? (second result))
          "Second element should be the hiccup component vector"))))


;; ---------------------------------------------------------------------------
;; D. Toggle shape for :replacement-choice: full choice map dispatched on click

(defn- find-first-button
  "Walk a hiccup tree and return the first [:button ...] element found,
   handling lazy sequences produced by (for ...) inside :div children."
  [hiccup]
  (cond
    (not (vector? hiccup))
    nil

    (= :button (first hiccup))
    hiccup

    :else
    (let [children (if (map? (second hiccup)) (drop 2 hiccup) (drop 1 hiccup))]
      (some (fn [child]
              (cond
                (vector? child) (find-first-button child)
                (seq? child)    (some find-first-button child)
                :else           nil))
            children))))


(deftest binary-choice-view-replacement-choice-dispatches-full-map-test
  (testing "binary-choice-view passes full choice map to toggle-selection for :replacement-choice"
    (let [dispatched (atom [])
          _          (with-redefs [rf/dispatch (fn [event] (swap! dispatched conj event))]
                       (let [result    (binary-choice-view/binary-choice-view replacement-choice-selection)
                             first-btn (find-first-button result)
                             on-click  (:on-click (second first-btn))]
                         (is (some? on-click) "First button must have :on-click")
                         (on-click)))]
      ;; Should dispatch toggle-selection with the full choice map (not just keyword)
      (let [toggle-event (first (filter #(= ::selection-events/toggle-selection (first %)) @dispatched))]
        (is (some? toggle-event)
            "toggle-selection should be dispatched on button click")
        (is (map? (second toggle-event))
            "For :replacement-choice, toggle value should be the full choice map")
        (is (= :proceed (:choice/action (second toggle-event)))
            "Choice map should have :choice/action :proceed for first button")))))


(deftest binary-choice-view-replacement-choice-dispatches-confirm-on-click-test
  (testing "binary-choice-view dispatches confirm-selection alongside toggle for :replacement-choice"
    (let [dispatched (atom [])
          _          (with-redefs [rf/dispatch (fn [event] (swap! dispatched conj event))]
                       (let [result    (binary-choice-view/binary-choice-view replacement-choice-selection)
                             first-btn (find-first-button result)
                             on-click  (:on-click (second first-btn))]
                         (is (some? on-click) "First button must have :on-click")
                         (on-click)))]
      (let [confirm-event (first (filter #(= ::selection-events/confirm-selection (first %)) @dispatched))]
        (is (some? confirm-event)
            "confirm-selection should be dispatched on button click (single-click confirm)")))))


;; ---------------------------------------------------------------------------
;; E. Toggle shape for :unless-pay: keyword (:choice/action) dispatched on click

(deftest binary-choice-view-unless-pay-dispatches-keyword-test
  (testing "binary-choice-view passes :choice/action keyword to toggle-selection for :unless-pay"
    (let [dispatched (atom [])
          _          (with-redefs [rf/dispatch (fn [event] (swap! dispatched conj event))]
                       (let [result    (binary-choice-view/binary-choice-view unless-pay-selection)
                             first-btn (find-first-button result)
                             on-click  (:on-click (second first-btn))]
                         (is (some? on-click) "First button must have :on-click")
                         (on-click)))]
      ;; Should dispatch toggle-selection with the keyword :pay, not the full choice map
      (let [toggle-event (first (filter #(= ::selection-events/toggle-selection (first %)) @dispatched))]
        (is (some? toggle-event)
            "toggle-selection should be dispatched on button click")
        (is (keyword? (second toggle-event))
            "For :unless-pay, toggle value should be a keyword (not a full map)")
        (is (= :pay (second toggle-event))
            "First button should dispatch keyword :pay for :unless-pay domain")))))


(deftest binary-choice-view-unless-pay-dispatches-confirm-on-click-test
  (testing "binary-choice-view dispatches confirm-selection alongside toggle for :unless-pay"
    (let [dispatched (atom [])
          _          (with-redefs [rf/dispatch (fn [event] (swap! dispatched conj event))]
                       (let [result    (binary-choice-view/binary-choice-view unless-pay-selection)
                             first-btn (find-first-button result)
                             on-click  (:on-click (second first-btn))]
                         (is (some? on-click) "First button must have :on-click")
                         (on-click)))]
      (let [confirm-event (first (filter #(= ::selection-events/confirm-selection (first %)) @dispatched))]
        (is (some? confirm-event)
            "confirm-selection should be dispatched on button click (single-click confirm)")))))


;; ---------------------------------------------------------------------------
;; F. Builder: build-unless-pay-selection includes :selection/choices

(deftest build-unless-pay-selection-includes-choices-test
  (testing "build-unless-pay-selection includes :selection/choices with labeled entries"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :stack :player-2)
          [db ml-id] (th/add-card-to-zone db :mana-leak :stack :player-1)
          effect {:effect/type         :counter-spell
                  :effect/target       ritual-id
                  :effect/unless-pay   {:colorless 3}
                  :unless-pay/controller :player-2}
          sel (sel-core/build-selection-for-effect db :player-1 ml-id effect [])]
      (is (some? (:selection/choices sel))
          ":selection/choices should be present on unless-pay selection")
      (is (= 2 (count (:selection/choices sel)))
          "Should have exactly 2 choices (pay and decline)")
      (let [actions (set (map :choice/action (:selection/choices sel)))]
        (is (contains? actions :pay)
            "Should have :pay choice")
        (is (contains? actions :decline)
            "Should have :decline choice"))
      (let [labels (set (map :choice/label (:selection/choices sel)))]
        (is (every? string? labels)
            "All :choice/label values should be strings")))))


(deftest build-unless-pay-selection-pay-label-includes-cost-test
  (testing "build-unless-pay-selection :pay choice label includes formatted cost"
    (let [db (th/create-test-db)
          db (th/add-opponent db)
          [db ritual-id] (th/add-card-to-zone db :dark-ritual :stack :player-2)
          [db ml-id] (th/add-card-to-zone db :mana-leak :stack :player-1)
          effect {:effect/type         :counter-spell
                  :effect/target       ritual-id
                  :effect/unless-pay   {:colorless 3}
                  :unless-pay/controller :player-2}
          sel (sel-core/build-selection-for-effect db :player-1 ml-id effect [])
          pay-choice (first (filter #(= :pay (:choice/action %)) (:selection/choices sel)))]
      (is (some? pay-choice)
          "Should find :pay choice")
      (is (re-find #"3" (:choice/label pay-choice))
          "Pay label should include the cost amount"))))
