(ns fizzle.views.keyboard-test
  "Tests for views/keyboard.cljs keyboard shortcut infrastructure.

   All tested functions are pure (no DOM, no re-frame side effects):
     - keymap lookup
     - derive-context
     - hint-for-action
     - action-dispatch (guards only)
     - normalize-key (via js mock event objects)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.events.casting :as casting-events]
    [fizzle.events.cycling :as cycling-events]
    [fizzle.events.lands :as lands-events]
    [fizzle.events.priority-flow :as priority-flow-events]
    [fizzle.events.selection :as selection-events]
    [fizzle.events.selection.costs :as cost-events]
    [fizzle.history.events :as history-events]
    [fizzle.views.keyboard :as kb]))


;; ---------------------------------------------------------------------------
;; Helpers

(defn- mock-event
  "Build a minimal JS-like event map for normalize-key testing."
  ([key] (mock-event key false))
  ([key shift?]
   #js {:key key :shiftKey shift?}))


;; ---------------------------------------------------------------------------
;; A. Keymap lookup

(deftest keymap-normal-e-cast-test
  (testing "[:normal \"e\"] → :cast"
    (is (= :cast (get kb/keymap [:normal "e"])))))


(deftest keymap-normal-space-yield-test
  (testing "[:normal \"Space\"] → :yield"
    (is (= :yield (get kb/keymap [:normal "Space"])))))


(deftest keymap-normal-shift-space-yield-all-test
  (testing "[:normal \"Shift+Space\"] → :yield-all"
    (is (= :yield-all (get kb/keymap [:normal "Shift+Space"])))))


(deftest keymap-normal-q-undo-test
  (testing "[:normal \"q\"] → :undo"
    (is (= :undo (get kb/keymap [:normal "q"])))))


(deftest keymap-normal-r-cycle-test
  (testing "[:normal \"r\"] → :cycle"
    (is (= :cycle (get kb/keymap [:normal "r"])))))


(deftest keymap-binary-choice-1-choose-1-test
  (testing "[:binary-choice \"1\"] → :choose-1"
    (is (= :choose-1 (get kb/keymap [:binary-choice "1"])))))


(deftest keymap-binary-choice-2-choose-2-test
  (testing "[:binary-choice \"2\"] → :choose-2"
    (is (= :choose-2 (get kb/keymap [:binary-choice "2"])))))


(deftest keymap-pick-mode-1-2-3-test
  (testing "pick-mode maps 1/2/3 to choose-1/2/3"
    (is (= :choose-1 (get kb/keymap [:pick-mode "1"])))
    (is (= :choose-2 (get kb/keymap [:pick-mode "2"])))
    (is (= :choose-3 (get kb/keymap [:pick-mode "3"])))))


(deftest keymap-accumulate-w-increment-test
  (testing "[:accumulate \"w\"] → :increment"
    (is (= :increment (get kb/keymap [:accumulate "w"])))))


(deftest keymap-accumulate-s-decrement-test
  (testing "[:accumulate \"s\"] → :decrement"
    (is (= :decrement (get kb/keymap [:accumulate "s"])))))


(deftest keymap-accumulate-space-confirm-test
  (testing "[:accumulate \"Space\"] → :confirm"
    (is (= :confirm (get kb/keymap [:accumulate "Space"])))))


(deftest keymap-allocate-resource-1-5-test
  (testing "allocate-resource maps 1-5 to allocate-1 through allocate-5"
    (is (= :allocate-1 (get kb/keymap [:allocate-resource "1"])))
    (is (= :allocate-2 (get kb/keymap [:allocate-resource "2"])))
    (is (= :allocate-3 (get kb/keymap [:allocate-resource "3"])))
    (is (= :allocate-4 (get kb/keymap [:allocate-resource "4"])))
    (is (= :allocate-5 (get kb/keymap [:allocate-resource "5"])))))


(deftest keymap-allocate-resource-space-confirm-test
  (testing "[:allocate-resource \"Space\"] → :confirm"
    (is (= :confirm (get kb/keymap [:allocate-resource "Space"])))))


;; ---------------------------------------------------------------------------
;; B. Context derivation

(deftest derive-context-nil-is-normal-test
  (testing "nil pending-selection → :normal"
    (is (= :normal (kb/derive-context nil)))))


(deftest derive-context-binary-choice-test
  (testing ":binary-choice mechanism → :binary-choice context"
    (is (= :binary-choice (kb/derive-context {:selection/mechanism :binary-choice})))))


(deftest derive-context-pick-mode-test
  (testing ":pick-mode mechanism → :pick-mode context"
    (is (= :pick-mode (kb/derive-context {:selection/mechanism :pick-mode})))))


(deftest derive-context-accumulate-test
  (testing ":accumulate mechanism → :accumulate context"
    (is (= :accumulate (kb/derive-context {:selection/mechanism :accumulate})))))


(deftest derive-context-allocate-resource-test
  (testing ":allocate-resource mechanism → :allocate-resource context"
    (is (= :allocate-resource (kb/derive-context {:selection/mechanism :allocate-resource})))))


(deftest derive-context-pick-from-zone-is-modal-test
  (testing ":pick-from-zone mechanism → :modal (suppressed)"
    (is (= :modal (kb/derive-context {:selection/mechanism :pick-from-zone})))))


(deftest derive-context-reorder-is-modal-test
  (testing ":reorder mechanism → :modal (suppressed)"
    (is (= :modal (kb/derive-context {:selection/mechanism :reorder})))))


(deftest derive-context-n-slot-targeting-is-modal-test
  (testing ":n-slot-targeting mechanism → :modal (suppressed)"
    (is (= :modal (kb/derive-context {:selection/mechanism :n-slot-targeting})))))


;; ---------------------------------------------------------------------------
;; C. Hint reverse-lookup

(deftest hint-for-action-yield-space-test
  (testing "(hint-for-action :normal :yield) → \"Space\""
    (is (= "Space" (kb/hint-for-action :normal :yield)))))


(deftest hint-for-action-yield-all-shift-space-test
  (testing "(hint-for-action :normal :yield-all) → \"Shift+Space\""
    (is (= "Shift+Space" (kb/hint-for-action :normal :yield-all)))))


(deftest hint-for-action-cast-e-test
  (testing "(hint-for-action :normal :cast) → \"E\""
    (is (= "E" (kb/hint-for-action :normal :cast)))))


(deftest hint-for-action-undo-q-test
  (testing "(hint-for-action :normal :undo) → \"Q\""
    (is (= "Q" (kb/hint-for-action :normal :undo)))))


(deftest hint-for-action-cycle-r-test
  (testing "(hint-for-action :normal :cycle) → \"R\""
    (is (= "R" (kb/hint-for-action :normal :cycle)))))


(deftest hint-for-action-increment-w-test
  (testing "(hint-for-action :accumulate :increment) → \"W\""
    (is (= "W" (kb/hint-for-action :accumulate :increment)))))


(deftest hint-for-action-decrement-s-test
  (testing "(hint-for-action :accumulate :decrement) → \"S\""
    (is (= "S" (kb/hint-for-action :accumulate :decrement)))))


(deftest hint-for-action-confirm-space-test
  (testing "(hint-for-action :accumulate :confirm) → \"Space\""
    (is (= "Space" (kb/hint-for-action :accumulate :confirm)))))


(deftest hint-for-action-unknown-action-nil-test
  (testing "Unknown action returns nil"
    (is (nil? (kb/hint-for-action :normal :unknown-action)))))


(deftest hint-for-action-wrong-context-nil-test
  (testing "Action in wrong context returns nil"
    (is (nil? (kb/hint-for-action :binary-choice :yield)))))


;; ---------------------------------------------------------------------------
;; D. Action dispatch guards

(def ^:private base-state
  {:selected-card   :card-1
   :can-cast?       false
   :can-play-land?  false
   :can-cycle?      false
   :stack           []
   :pending-selection nil})


(deftest action-dispatch-cast-nil-when-no-play-test
  (testing ":cast returns nil when both can-cast? and can-play-land? are false"
    (is (nil? (kb/action-dispatch :cast base-state)))))


(deftest action-dispatch-cast-returns-cast-spell-when-can-cast-test
  (testing ":cast returns cast-spell vector when can-cast? is true"
    (let [state (assoc base-state :can-cast? true)
          result (kb/action-dispatch :cast state)]
      (is (= ::casting-events/cast-spell (first result)))
      (is (= {:object-id :card-1} (second result))))))


(deftest action-dispatch-cast-returns-play-land-when-can-play-land-test
  (testing ":cast returns play-land vector when can-play-land? is true (and can-cast? false)"
    (let [state (assoc base-state :can-play-land? true)
          result (kb/action-dispatch :cast state)]
      (is (= ::lands-events/play-land (first result)))
      (is (= :card-1 (second result))))))


(deftest action-dispatch-cast-prefers-cast-over-play-land-test
  (testing ":cast prefers cast-spell when both can-cast? and can-play-land? are true"
    (let [state (assoc base-state :can-cast? true :can-play-land? true)
          result (kb/action-dispatch :cast state)]
      (is (= ::casting-events/cast-spell (first result))))))


(deftest action-dispatch-yield-always-dispatches-test
  (testing ":yield always returns dispatch vector"
    (let [result (kb/action-dispatch :yield base-state)]
      (is (= [::priority-flow-events/yield] result)))))


(deftest action-dispatch-yield-all-always-dispatches-test
  (testing ":yield-all always returns dispatch vector"
    (let [result (kb/action-dispatch :yield-all base-state)]
      (is (= [::priority-flow-events/yield-all] result)))))


(deftest action-dispatch-undo-always-dispatches-test
  (testing ":undo always returns dispatch vector"
    (let [result (kb/action-dispatch :undo base-state)]
      (is (= [::history-events/pop-entry] result)))))


(deftest action-dispatch-cycle-nil-when-cannot-cycle-test
  (testing ":cycle returns nil when can-cycle? is false"
    (is (nil? (kb/action-dispatch :cycle base-state)))))


(deftest action-dispatch-cycle-returns-cycle-card-when-can-cycle-test
  (testing ":cycle returns cycle-card vector when can-cycle? is true"
    (let [state (assoc base-state :can-cycle? true)
          result (kb/action-dispatch :cycle state)]
      (is (= [::cycling-events/cycle-card :card-1] result)))))


(deftest action-dispatch-choose-1-binary-choice-action-mode-test
  (testing ":choose-1 dispatches toggle+confirm for first choice (action-mode)"
    (let [choice-1  {:choice/action :pay :choice/label "Pay 2 life"}
          choice-2  {:choice/action :decline :choice/label "Decline"}
          sel       {:selection/choices       [choice-1 choice-2]
                     :selection/valid-targets [:pay :decline]}
          state     (assoc base-state :pending-selection sel)
          result    (kb/action-dispatch :choose-1 state)]
      (is (map? result))
      (is (= [[::selection-events/toggle-selection :pay]
              [::selection-events/confirm-selection]]
             (:dispatch-n result))))))


(deftest action-dispatch-choose-2-binary-choice-action-mode-test
  (testing ":choose-2 dispatches toggle+confirm for second choice (action-mode)"
    (let [choice-1  {:choice/action :pay :choice/label "Pay 2 life"}
          choice-2  {:choice/action :decline :choice/label "Decline"}
          sel       {:selection/choices       [choice-1 choice-2]
                     :selection/valid-targets [:pay :decline]}
          state     (assoc base-state :pending-selection sel)
          result    (kb/action-dispatch :choose-2 state)]
      (is (map? result))
      (is (= [[::selection-events/toggle-selection :decline]
              [::selection-events/confirm-selection]]
             (:dispatch-n result))))))


(deftest action-dispatch-choose-1-full-choice-map-mode-test
  (testing ":choose-1 dispatches full choice map when no :selection/valid-targets"
    (let [choice-1  {:choice/action :graveyard :choice/label "Go to graveyard"}
          sel       {:selection/choices [choice-1]}
          state     (assoc base-state :pending-selection sel)
          result    (kb/action-dispatch :choose-1 state)]
      (is (map? result))
      (is (= [[::selection-events/toggle-selection choice-1]
              [::selection-events/confirm-selection]]
             (:dispatch-n result))))))


(deftest action-dispatch-choose-1-nil-when-no-choices-test
  (testing ":choose-1 returns nil when no choices available"
    (let [sel   {:selection/choices []}
          state (assoc base-state :pending-selection sel)]
      (is (nil? (kb/action-dispatch :choose-1 state))))))


(deftest action-dispatch-choose-3-nil-when-only-2-choices-test
  (testing ":choose-3 returns nil when fewer than 3 choices available"
    (let [sel   {:selection/choices [{:choice/action :a :choice/label "A"}
                                     {:choice/action :b :choice/label "B"}]}
          state (assoc base-state :pending-selection sel)]
      (is (nil? (kb/action-dispatch :choose-3 state))))))


(deftest action-dispatch-increment-test
  (testing ":increment returns increment-x-value event"
    (is (= [::cost-events/increment-x-value]
           (kb/action-dispatch :increment base-state)))))


(deftest action-dispatch-decrement-test
  (testing ":decrement returns decrement-x-value event"
    (is (= [::cost-events/decrement-x-value]
           (kb/action-dispatch :decrement base-state)))))


(deftest action-dispatch-confirm-test
  (testing ":confirm returns confirm-selection event"
    (is (= [::selection-events/confirm-selection]
           (kb/action-dispatch :confirm base-state)))))


;; ---------------------------------------------------------------------------
;; E. Key normalization

(deftest normalize-key-space-test
  (testing "event.key \" \" normalizes to \"Space\""
    (is (= "Space" (kb/normalize-key (mock-event " "))))))


(deftest normalize-key-shift-space-test
  (testing "event.key \" \" with shiftKey=true normalizes to \"Shift+Space\""
    (is (= "Shift+Space" (kb/normalize-key (mock-event " " true))))))


(deftest normalize-key-letter-e-test
  (testing "event.key \"e\" normalizes to \"e\""
    (is (= "e" (kb/normalize-key (mock-event "e"))))))


(deftest normalize-key-letter-shift-e-test
  (testing "event.key \"e\" with shiftKey=true normalizes to \"Shift+e\""
    (is (= "Shift+e" (kb/normalize-key (mock-event "e" true))))))


(deftest normalize-key-number-1-test
  (testing "event.key \"1\" normalizes to \"1\""
    (is (= "1" (kb/normalize-key (mock-event "1"))))))


;; ---------------------------------------------------------------------------
;; F. Allocate-N action dispatch

(def ^:private allocate-state
  "Base state with a pending allocate-resource selection containing a mana pool."
  (assoc base-state
         :pending-selection
         {:selection/mechanism      :allocate-resource
          :selection/remaining-pool {:white 1 :blue 2 :black 0 :red 1 :green 0 :colorless 3}}))

(deftest action-dispatch-allocate-1-happy-path-test
  (testing ":allocate-1 returns allocate-mana-color for the first available color"
    ;; color-order is [:white :blue :black :red :green :colorless]
    ;; Pool has white=1, blue=2, red=1, colorless=3 (black=0 and green=0 skipped)
    ;; First available is :white
    (let [result (kb/action-dispatch :allocate-1 allocate-state)]
      (is (= [::cost-events/allocate-mana-color :white] result)))))

(deftest action-dispatch-allocate-2-returns-second-available-color-test
  (testing ":allocate-2 returns allocate-mana-color for the second available color"
    ;; Available colors in order: white, blue, red, colorless — second is :blue
    (let [result (kb/action-dispatch :allocate-2 allocate-state)]
      (is (= [::cost-events/allocate-mana-color :blue] result)))))

(deftest action-dispatch-allocate-5-nil-when-fewer-than-5-colors-test
  (testing ":allocate-5 returns nil when fewer than 5 colors are available in pool"
    ;; allocate-state pool has only 4 available colors: white, blue, red, colorless
    (is (nil? (kb/action-dispatch :allocate-5 allocate-state)))))

(deftest action-dispatch-allocate-1-nil-when-pending-selection-nil-test
  (testing ":allocate-1 returns nil when pending-selection is nil"
    (is (nil? (kb/action-dispatch :allocate-1 base-state)))))

(deftest action-dispatch-allocate-color-order-test
  (testing "allocate-N respects color-order: white, blue, black, red, green, colorless"
    ;; Pool with all 6 colors present
    (let [state (assoc base-state
                       :pending-selection
                       {:selection/mechanism      :allocate-resource
                        :selection/remaining-pool {:white 1 :blue 1 :black 1
                                                   :red 1 :green 1 :colorless 1}})]
      (is (= [::cost-events/allocate-mana-color :white]     (kb/action-dispatch :allocate-1 state)))
      (is (= [::cost-events/allocate-mana-color :blue]      (kb/action-dispatch :allocate-2 state)))
      (is (= [::cost-events/allocate-mana-color :black]     (kb/action-dispatch :allocate-3 state)))
      (is (= [::cost-events/allocate-mana-color :red]       (kb/action-dispatch :allocate-4 state)))
      (is (= [::cost-events/allocate-mana-color :green]     (kb/action-dispatch :allocate-5 state))))))


;; ---------------------------------------------------------------------------
;; G. Unmapped key lookups return nil

(deftest keymap-normal-x-is-unmapped-test
  (testing "[:normal \"x\"] → nil (no binding for x in normal context)"
    (is (nil? (get kb/keymap [:normal "x"])))))

(deftest keymap-modal-e-is-unmapped-test
  (testing "[:modal \"e\"] → nil (:modal is a suppressed context with no bindings)"
    (is (nil? (get kb/keymap [:modal "e"])))))

(deftest keymap-normal-unknown-number-is-unmapped-test
  (testing "[:normal \"9\"] → nil (number keys not bound in normal context)"
    (is (nil? (get kb/keymap [:normal "9"])))))


;; ---------------------------------------------------------------------------
;; H. Integration tests: compose normalize-key → keymap → action-dispatch

(deftest integration-space-in-normal-context-yields-test
  (testing "Space key in :normal context dispatches yield"
    (let [event      (mock-event " ")                  ; raw space
          norm-key   (kb/normalize-key event)           ; → "Space"
          context    (kb/derive-context nil)            ; nil selection → :normal
          action     (get kb/keymap [context norm-key]) ; → :yield
          result     (kb/action-dispatch action base-state)]
      (is (= "Space" norm-key))
      (is (= :normal context))
      (is (= :yield action))
      (is (= [::priority-flow-events/yield] result)))))

(deftest integration-1-in-binary-choice-context-dispatches-choose-1-test
  (testing "\"1\" key in :binary-choice context dispatches toggle+confirm for first choice"
    (let [choice-1  {:choice/action :pay   :choice/label "Pay 2 life"}
          choice-2  {:choice/action :skip  :choice/label "Skip"}
          sel       {:selection/mechanism     :binary-choice
                     :selection/choices       [choice-1 choice-2]
                     :selection/valid-targets [:pay :skip]}
          event      (mock-event "1")
          norm-key   (kb/normalize-key event)                   ; → "1"
          context    (kb/derive-context sel)                     ; → :binary-choice
          action     (get kb/keymap [context norm-key])          ; → :choose-1
          state      (assoc base-state :pending-selection sel)
          result     (kb/action-dispatch action state)]
      (is (= "1" norm-key))
      (is (= :binary-choice context))
      (is (= :choose-1 action))
      (is (map? result))
      (is (= [[::selection-events/toggle-selection :pay]
              [::selection-events/confirm-selection]]
             (:dispatch-n result))))))
