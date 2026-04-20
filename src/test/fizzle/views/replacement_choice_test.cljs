(ns fizzle.views.replacement-choice-test
  "Tests for :replacement-choice selection UI rendering.

   The replacement-choice modal renders discrete proceed/redirect buttons.
   Tests verify:
     A. The multimethod dispatch handles :replacement-choice (not falling to :default)
     B. The modal component returns choice buttons with :choice/label text (data-driven)

   These tests exercise pure functions only — no re-frame app-db needed."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.views.modals :as modals]
    [fizzle.views.selection.replacement :as replacement-view]))


;; ---------------------------------------------------------------------------
;; A. Multimethod dispatch

(deftest replacement-choice-dispatch-exists-test
  (testing ":replacement-choice has a dedicated render-selection-modal method"
    (is (some? (get-method modals/render-selection-modal :replacement-choice))
        "render-selection-modal should have a method for :replacement-choice (not just :default)")))


;; ---------------------------------------------------------------------------
;; B. Pure component: choice labels rendered from :selection/choices

(def ^:private sample-selection
  {:selection/type    :replacement-choice
   :selection/choices [{:choice/action :proceed   :choice/label "Discard a land"}
                       {:choice/action :redirect  :choice/label "Sacrifice (go to graveyard)"}]
   :selection/selected #{}})


(deftest replacement-choice-modal-returns-hiccup-test
  (testing "replacement-choice-modal returns hiccup vector"
    (let [result (replacement-view/replacement-choice-modal sample-selection)]
      (is (vector? result)
          "modal should return a hiccup vector"))))


(deftest replacement-choice-modal-contains-proceed-label-test
  (testing "replacement-choice-modal includes 'Discard a land' label"
    (let [result (str (replacement-view/replacement-choice-modal sample-selection))]
      (is (re-find #"Discard a land" result)
          "modal output should contain 'Discard a land' from :choice/label"))))


(deftest replacement-choice-modal-contains-redirect-label-test
  (testing "replacement-choice-modal includes 'Sacrifice (go to graveyard)' label"
    (let [result (str (replacement-view/replacement-choice-modal sample-selection))]
      (is (re-find #"Sacrifice" result)
          "modal output should contain 'Sacrifice' from :choice/label"))))


(deftest replacement-choice-modal-data-driven-test
  (testing "replacement-choice-modal renders all choices from :selection/choices"
    (let [custom-selection {:selection/type    :replacement-choice
                            :selection/choices [{:choice/action :proceed  :choice/label "Option A"}
                                                {:choice/action :redirect :choice/label "Option B"}
                                                {:choice/action :redirect :choice/label "Option C"}]
                            :selection/selected #{}}
          result (str (replacement-view/replacement-choice-modal custom-selection))]
      (is (re-find #"Option A" result) "should contain 'Option A'")
      (is (re-find #"Option B" result) "should contain 'Option B'")
      (is (re-find #"Option C" result) "should contain 'Option C'"))))
