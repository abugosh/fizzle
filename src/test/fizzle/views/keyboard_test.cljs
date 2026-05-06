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
    [fizzle.events.selection.storm :as storm-events]
    [fizzle.history.events :as history-events]
    [fizzle.views.keyboard :as kb]
    [re-frame.core :as rf]))


;; ---------------------------------------------------------------------------
;; Helpers

(defn- mock-event
  "Build a minimal JS-like event map for normalize-key testing."
  ([key] (mock-event key false))
  ([key shift?]
   #js {:key key :shiftKey shift?})
  ([key shift? tag-name]
   #js {:key key :shiftKey shift? :target #js {:tagName tag-name}}))


;; ---------------------------------------------------------------------------
;; A. Keymap lookup

(deftest keymap-normal-e-cast-and-yield-test
  (testing "[:normal \"e\"] → :cast-and-yield"
    (is (= :cast-and-yield (get kb/keymap [:normal "e"])))))


(deftest keymap-normal-f-cast-test
  (testing "[:normal \"f\"] → :cast"
    (is (= :cast (get kb/keymap [:normal "f"])))))


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


(deftest keymap-zone-pick-space-confirm-test
  (testing "[:zone-pick \"Space\"] → :confirm"
    (is (= :confirm (get kb/keymap [:zone-pick "Space"])))))


(deftest keymap-zone-pick-escape-secondary-test
  (testing "[:zone-pick \"Escape\"] → :secondary"
    (is (= :secondary (get kb/keymap [:zone-pick "Escape"])))))


(deftest keymap-flat-targeting-space-confirm-test
  (testing "[:flat-targeting \"Space\"] → :confirm"
    (is (= :confirm (get kb/keymap [:flat-targeting "Space"])))))


(deftest keymap-flat-targeting-escape-secondary-test
  (testing "[:flat-targeting \"Escape\"] → :secondary"
    (is (= :secondary (get kb/keymap [:flat-targeting "Escape"])))))


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


(deftest derive-context-pick-from-zone-is-zone-pick-test
  (testing ":pick-from-zone mechanism → :zone-pick context"
    (is (= :zone-pick (kb/derive-context {:selection/mechanism :pick-from-zone})))))


(deftest derive-context-reorder-is-modal-test
  (testing ":reorder mechanism → :modal (suppressed)"
    (is (= :modal (kb/derive-context {:selection/mechanism :reorder})))))


(deftest derive-context-n-slot-targeting-is-flat-targeting-test
  (testing ":n-slot-targeting mechanism → :flat-targeting context"
    (is (= :flat-targeting (kb/derive-context {:selection/mechanism :n-slot-targeting})))))


;; ---------------------------------------------------------------------------
;; C. Hint reverse-lookup

(deftest hint-for-action-yield-space-test
  (testing "(hint-for-action :normal :yield) → \"Space\""
    (is (= "Space" (kb/hint-for-action :normal :yield)))))


(deftest hint-for-action-yield-all-shift-space-test
  (testing "(hint-for-action :normal :yield-all) → \"Shift+Space\""
    (is (= "Shift+Space" (kb/hint-for-action :normal :yield-all)))))


(deftest hint-for-action-cast-and-yield-e-test
  (testing "(hint-for-action :normal :cast-and-yield) → \"E\""
    (is (= "E" (kb/hint-for-action :normal :cast-and-yield)))))


(deftest hint-for-action-cast-f-test
  (testing "(hint-for-action :normal :cast) → \"F\""
    (is (= "F" (kb/hint-for-action :normal :cast)))))


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


(deftest action-dispatch-cast-and-yield-nil-when-no-play-test
  (testing ":cast-and-yield returns nil when both can-cast? and can-play-land? are false"
    (is (nil? (kb/action-dispatch :cast-and-yield base-state)))))


(deftest action-dispatch-cast-and-yield-returns-vector-when-can-cast-test
  (testing ":cast-and-yield returns cast-and-yield vector when can-cast? is true"
    (let [state (assoc base-state :can-cast? true)
          result (kb/action-dispatch :cast-and-yield state)]
      (is (= ::priority-flow-events/cast-and-yield (first result)))
      (is (= {:object-id :card-1} (second result))))))


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


;; ---------------------------------------------------------------------------
;; I. Text-input suppression

(deftest text-input-target-input-element-test
  (testing "INPUT element target is recognized as text input"
    (is (true? (kb/text-input-target? (mock-event "e" false "INPUT"))))))


(deftest text-input-target-textarea-element-test
  (testing "TEXTAREA element target is recognized as text input"
    (is (true? (kb/text-input-target? (mock-event "e" false "TEXTAREA"))))))


(deftest text-input-target-div-element-test
  (testing "DIV element target is NOT a text input"
    (is (false? (kb/text-input-target? (mock-event "e" false "DIV"))))))


(deftest text-input-target-nil-target-test
  (testing "Event with no target does not crash (nil-safe)"
    (is (false? (kb/text-input-target? #js {:key "e" :shiftKey false})))))


;; ---------------------------------------------------------------------------
;; J. Storm-split context: derive-context

(deftest derive-context-storm-split-domain-returns-storm-split-test
  (testing ":accumulate mechanism + :storm-split domain → :storm-split context"
    (is (= :storm-split
           (kb/derive-context {:selection/mechanism :accumulate
                               :selection/domain    :storm-split})))))


(deftest derive-context-accumulate-x-mana-cost-domain-returns-accumulate-test
  (testing ":accumulate mechanism + :x-mana-cost domain → :accumulate context (no regression)"
    (is (= :accumulate
           (kb/derive-context {:selection/mechanism :accumulate
                               :selection/domain    :x-mana-cost})))))


(deftest derive-context-accumulate-pay-x-life-domain-returns-accumulate-test
  (testing ":accumulate mechanism + :pay-x-life domain → :accumulate context (no regression)"
    (is (= :accumulate
           (kb/derive-context {:selection/mechanism :accumulate
                               :selection/domain    :pay-x-life})))))


(deftest derive-context-accumulate-no-domain-returns-accumulate-test
  (testing ":accumulate mechanism with no domain → :accumulate context (no regression)"
    (is (= :accumulate
           (kb/derive-context {:selection/mechanism :accumulate})))))


;; ---------------------------------------------------------------------------
;; K. Storm-split keymap chord entries

(deftest keymap-storm-split-1-chord-start-test
  (testing "[:storm-split \"1\"] → :chord-start"
    (is (= :chord-start (get kb/keymap [:storm-split "1"])))))


(deftest keymap-storm-split-2-chord-start-test
  (testing "[:storm-split \"2\"] → :chord-start"
    (is (= :chord-start (get kb/keymap [:storm-split "2"])))))


(deftest keymap-storm-split-1-w-storm-add-all-1-test
  (testing "[:storm-split \"1>w\"] → :storm-add-all-1"
    (is (= :storm-add-all-1 (get kb/keymap [:storm-split "1>w"])))))


(deftest keymap-storm-split-1-s-storm-clear-1-test
  (testing "[:storm-split \"1>s\"] → :storm-clear-1"
    (is (= :storm-clear-1 (get kb/keymap [:storm-split "1>s"])))))


(deftest keymap-storm-split-1-shift-w-storm-inc-1-test
  (testing "[:storm-split \"1>Shift+W\"] → :storm-inc-1"
    (is (= :storm-inc-1 (get kb/keymap [:storm-split "1>Shift+W"])))))


(deftest keymap-storm-split-1-shift-s-storm-dec-1-test
  (testing "[:storm-split \"1>Shift+S\"] → :storm-dec-1"
    (is (= :storm-dec-1 (get kb/keymap [:storm-split "1>Shift+S"])))))


(deftest keymap-storm-split-2-w-storm-add-all-2-test
  (testing "[:storm-split \"2>w\"] → :storm-add-all-2"
    (is (= :storm-add-all-2 (get kb/keymap [:storm-split "2>w"])))))


(deftest keymap-storm-split-2-s-storm-clear-2-test
  (testing "[:storm-split \"2>s\"] → :storm-clear-2"
    (is (= :storm-clear-2 (get kb/keymap [:storm-split "2>s"])))))


(deftest keymap-storm-split-2-shift-w-storm-inc-2-test
  (testing "[:storm-split \"2>Shift+W\"] → :storm-inc-2"
    (is (= :storm-inc-2 (get kb/keymap [:storm-split "2>Shift+W"])))))


(deftest keymap-storm-split-2-shift-s-storm-dec-2-test
  (testing "[:storm-split \"2>Shift+S\"] → :storm-dec-2"
    (is (= :storm-dec-2 (get kb/keymap [:storm-split "2>Shift+S"])))))


(deftest keymap-storm-split-space-confirm-test
  (testing "[:storm-split \"Space\"] → :confirm"
    (is (= :confirm (get kb/keymap [:storm-split "Space"])))))


;; ---------------------------------------------------------------------------
;; L. Storm-split action-dispatch

(def ^:private t1 :player-1)
(def ^:private t2 :player-2)


(def ^:private storm-split-state
  "Base state with a :storm-split pending-selection:
   2 valid-targets, copy-count 6, allocation {t1 3 t2 1}."
  (assoc base-state
         :pending-selection
         {:selection/mechanism    :accumulate
          :selection/domain       :storm-split
          :selection/copy-count   6
          :selection/valid-targets [t1 t2]
          :selection/allocation    {t1 3 t2 1}}))


(deftest action-dispatch-storm-add-all-1-test
  (testing ":storm-add-all-1 dispatches adjust-storm-split for t1 with remaining delta"
    ;; remaining = copy-count - total-allocated = 6 - (3+1) = 2
    (let [result (kb/action-dispatch :storm-add-all-1 storm-split-state)]
      (is (= [::storm-events/adjust-storm-split t1 2] result)))))


(deftest action-dispatch-storm-clear-1-test
  (testing ":storm-clear-1 dispatches adjust-storm-split for t1 with negative current allocation"
    ;; current allocation for t1 = 3, delta = -3
    (let [result (kb/action-dispatch :storm-clear-1 storm-split-state)]
      (is (= [::storm-events/adjust-storm-split t1 -3] result)))))


(deftest action-dispatch-storm-inc-1-test
  (testing ":storm-inc-1 dispatches adjust-storm-split for t1 with delta +1"
    (let [result (kb/action-dispatch :storm-inc-1 storm-split-state)]
      (is (= [::storm-events/adjust-storm-split t1 1] result)))))


(deftest action-dispatch-storm-dec-2-test
  (testing ":storm-dec-2 dispatches adjust-storm-split for t2 with delta -1"
    (let [result (kb/action-dispatch :storm-dec-2 storm-split-state)]
      (is (= [::storm-events/adjust-storm-split t2 -1] result)))))


(deftest action-dispatch-storm-clear-2-test
  (testing ":storm-clear-2 dispatches adjust-storm-split for t2 with negative current allocation"
    ;; current allocation for t2 = 1, delta = -1
    (let [result (kb/action-dispatch :storm-clear-2 storm-split-state)]
      (is (= [::storm-events/adjust-storm-split t2 -1] result)))))


(deftest action-dispatch-storm-inc-2-test
  (testing ":storm-inc-2 dispatches adjust-storm-split for t2 with delta +1"
    (let [result (kb/action-dispatch :storm-inc-2 storm-split-state)]
      (is (= [::storm-events/adjust-storm-split t2 1] result)))))


(deftest action-dispatch-storm-dec-1-test
  (testing ":storm-dec-1 dispatches adjust-storm-split for t1 with delta -1"
    (let [result (kb/action-dispatch :storm-dec-1 storm-split-state)]
      (is (= [::storm-events/adjust-storm-split t1 -1] result)))))


(deftest action-dispatch-storm-add-all-2-out-of-range-test
  (testing ":storm-add-all-2 returns nil when only 1 valid-target exists"
    (let [state (assoc-in storm-split-state
                          [:pending-selection :selection/valid-targets]
                          [t1])
          result (kb/action-dispatch :storm-add-all-2 state)]
      (is (nil? result)))))


(deftest action-dispatch-storm-add-all-2-two-targets-test
  (testing ":storm-add-all-2 dispatches adjust-storm-split for t2 with remaining delta"
    ;; remaining = 6 - (3+1) = 2, so t2 gets +2
    (let [result (kb/action-dispatch :storm-add-all-2 storm-split-state)]
      (is (= [::storm-events/adjust-storm-split t2 2] result)))))


;; ---------------------------------------------------------------------------
;; M. Storm-split chord hints

(deftest hint-for-action-storm-add-all-1-w-test
  (testing "(hint-for-action :storm-split :storm-add-all-1) → \"1 W\""
    (is (= "1 W" (kb/hint-for-action :storm-split :storm-add-all-1)))))


(deftest hint-for-action-storm-clear-1-s-test
  (testing "(hint-for-action :storm-split :storm-clear-1) → \"1 S\""
    (is (= "1 S" (kb/hint-for-action :storm-split :storm-clear-1)))))


(deftest hint-for-action-storm-inc-1-shift-w-test
  (testing "(hint-for-action :storm-split :storm-inc-1) → \"1 ⇧W\""
    (is (= "1 ⇧W" (kb/hint-for-action :storm-split :storm-inc-1)))))


(deftest hint-for-action-storm-dec-1-shift-s-test
  (testing "(hint-for-action :storm-split :storm-dec-1) → \"1 ⇧S\""
    (is (= "1 ⇧S" (kb/hint-for-action :storm-split :storm-dec-1)))))


(deftest hint-for-action-storm-add-all-2-w-test
  (testing "(hint-for-action :storm-split :storm-add-all-2) → \"2 W\""
    (is (= "2 W" (kb/hint-for-action :storm-split :storm-add-all-2)))))


(deftest hint-for-action-storm-clear-2-s-test
  (testing "(hint-for-action :storm-split :storm-clear-2) → \"2 S\""
    (is (= "2 S" (kb/hint-for-action :storm-split :storm-clear-2)))))


(deftest hint-for-action-storm-inc-2-shift-w-test
  (testing "(hint-for-action :storm-split :storm-inc-2) → \"2 ⇧W\""
    (is (= "2 ⇧W" (kb/hint-for-action :storm-split :storm-inc-2)))))


(deftest hint-for-action-storm-dec-2-shift-s-test
  (testing "(hint-for-action :storm-split :storm-dec-2) → \"2 ⇧S\""
    (is (= "2 ⇧S" (kb/hint-for-action :storm-split :storm-dec-2)))))


(deftest hint-for-action-accumulate-increment-regression-test
  (testing "(hint-for-action :accumulate :increment) still returns \"W\" (regression)"
    (is (= "W" (kb/hint-for-action :accumulate :increment)))))


;; ---------------------------------------------------------------------------
;; N. Chord fallthrough behavioral tests (handle-keydown)

(def ^:private storm-split-selection
  "A storm-split pending-selection for use in handle-keydown tests."
  {:selection/mechanism    :accumulate
   :selection/domain       :storm-split
   :selection/copy-count   6
   :selection/valid-targets [t1 t2]
   :selection/allocation    {t1 3 t2 1}})


(deftest chord-fallthrough-1-space-confirms-test
  (testing "Prefix \"1\" + Space falls through to [:storm-split \"Space\"] → :confirm dispatch"
    ;; When prefix is "1" and Space is pressed:
    ;;   - composed "1>Space" is not in keymap → composed-action is nil
    ;;   - prefix cleared
    ;;   - standalone [:storm-split "Space"] → :confirm → dispatches confirm-selection
    (let [dispatched        (atom [])
          chord-prefix      (atom "1")
          pending-ref       (atom storm-split-selection)
          app-ref           (atom base-state)
          selection-cards-ref (atom [])
          event           #js {:key " " :shiftKey false
                               :target #js {:tagName "div"}
                               :preventDefault (fn [])}]
      (with-redefs [rf/dispatch (fn [v] (swap! dispatched conj v))]
        (#'kb/handle-keydown event pending-ref app-ref chord-prefix selection-cards-ref))
      (is (nil? @chord-prefix) "chord-prefix-ref should be cleared")
      (is (= [[::selection-events/confirm-selection]] @dispatched)
          "confirm-selection should be dispatched"))))


(deftest chord-fallthrough-1-2-sets-new-prefix-test
  (testing "Prefix \"1\" + \"2\" → composed \"1>2\" not found, standalone \"2\" is :chord-start → new prefix \"2\""
    ;; When prefix is "1" and "2" is pressed:
    ;;   - composed "1>2" is not in keymap → composed-action is nil
    ;;   - prefix cleared
    ;;   - standalone [:storm-split "2"] → :chord-start → set new prefix to "2"
    ;;   - no dispatch fires
    (let [dispatched        (atom [])
          chord-prefix      (atom "1")
          pending-ref       (atom storm-split-selection)
          app-ref           (atom base-state)
          selection-cards-ref (atom [])
          event           #js {:key "2" :shiftKey false
                               :target #js {:tagName "div"}
                               :preventDefault (fn [])}]
      (with-redefs [rf/dispatch (fn [v] (swap! dispatched conj v))]
        (#'kb/handle-keydown event pending-ref app-ref chord-prefix selection-cards-ref))
      (is (= "2" @chord-prefix) "chord-prefix-ref should be updated to \"2\"")
      (is (empty? @dispatched) "no dispatch should fire"))))


(deftest chord-fallthrough-1-q-clears-prefix-test
  (testing "Prefix \"1\" + \"q\" → composed \"1>q\" not found, standalone [:storm-split \"q\"] not in keymap → no dispatch"
    ;; When prefix is "1" and "q" is pressed:
    ;;   - composed "1>q" is not in keymap → composed-action is nil
    ;;   - prefix cleared
    ;;   - standalone [:storm-split "q"] is also not in keymap → no dispatch
    (let [dispatched        (atom [])
          chord-prefix      (atom "1")
          pending-ref       (atom storm-split-selection)
          app-ref           (atom base-state)
          selection-cards-ref (atom [])
          event           #js {:key "q" :shiftKey false
                               :target #js {:tagName "div"}
                               :preventDefault (fn [])}]
      (with-redefs [rf/dispatch (fn [v] (swap! dispatched conj v))]
        (#'kb/handle-keydown event pending-ref app-ref chord-prefix selection-cards-ref))
      (is (nil? @chord-prefix) "chord-prefix-ref should be cleared")
      (is (empty? @dispatched) "no dispatch should fire"))))


(deftest chord-composed-1-w-dispatches-test
  (testing "Prefix \"1\" + \"w\" → composed \"1>w\" → :storm-add-all-1 → adjust-storm-split dispatch"
    ;; When prefix is "1" and "w" is pressed:
    ;;   - composed "1>w" → :storm-add-all-1
    ;;   - remaining = copy-count(6) - total-allocated(3+1=4) = 2
    ;;   - dispatches [::storm-events/adjust-storm-split t1 2]
    (let [dispatched        (atom [])
          chord-prefix      (atom "1")
          pending-ref       (atom storm-split-selection)
          app-ref           (atom base-state)
          selection-cards-ref (atom [])
          event           #js {:key "w" :shiftKey false
                               :target #js {:tagName "div"}
                               :preventDefault (fn [])}]
      (with-redefs [rf/dispatch (fn [v] (swap! dispatched conj v))]
        (#'kb/handle-keydown event pending-ref app-ref chord-prefix selection-cards-ref))
      (is (nil? @chord-prefix) "chord-prefix-ref should be cleared after composed chord")
      (is (= [[::storm-events/adjust-storm-split t1 2]] @dispatched)
          "adjust-storm-split for t1 with delta 2 should be dispatched"))))


;; ---------------------------------------------------------------------------
;; O. Flat-targeting candidate selection

(deftest select-nth-candidate-3-cards-index-0-test
  (testing "select-nth-candidate with 3 cards → returns toggle-selection for card at index 0"
    (let [card-1     {:object/id :card-uuid-1}
          card-2     {:object/id :card-uuid-2}
          card-3     {:object/id :card-uuid-3}
          selection-cards [card-1 card-2 card-3]
          pending-sel {:selection/domain :object-target
                       :selection/valid-targets [:card-uuid-1 :card-uuid-2 :card-uuid-3]}
          result     (#'kb/select-nth-candidate selection-cards pending-sel 0)]
      (is (= [::selection-events/toggle-selection :card-uuid-1] result)))))


(deftest select-nth-candidate-3-cards-out-of-bounds-test
  (testing "select-nth-candidate with 3 cards, n=5 → returns nil (out-of-bounds)"
    (let [card-1     {:object/id :card-uuid-1}
          card-2     {:object/id :card-uuid-2}
          card-3     {:object/id :card-uuid-3}
          selection-cards [card-1 card-2 card-3]
          pending-sel {:selection/domain :object-target
                       :selection/valid-targets [:card-uuid-1 :card-uuid-2 :card-uuid-3]}
          result     (#'kb/select-nth-candidate selection-cards pending-sel 5)]
      (is (nil? result)))))


(deftest select-nth-candidate-nil-selection-cards-test
  (testing "select-nth-candidate with nil selection-cards → returns nil"
    (let [pending-sel {:selection/domain :object-target
                       :selection/valid-targets []}
          result     (#'kb/select-nth-candidate nil pending-sel 0)]
      (is (nil? result)))))


(deftest select-nth-candidate-empty-selection-cards-test
  (testing "select-nth-candidate with empty [] selection-cards → returns nil"
    (let [pending-sel {:selection/domain :object-target
                       :selection/valid-targets []}
          result     (#'kb/select-nth-candidate [] pending-sel 0)]
      (is (nil? result)))))


(deftest select-nth-candidate-cast-time-targeting-domain-players-first-test
  (testing "select-nth-candidate :cast-time-targeting domain: player keywords numbered first, creatures after"
    (let [creature-1 {:object/id :creature-uuid-1}
          creature-2 {:object/id :creature-uuid-2}
          player-1   :player-1
          player-2   :player-2
          selection-cards [creature-1 creature-2]
          ;; valid-targets contains both player keywords and creature UUIDs
          pending-sel {:selection/domain :cast-time-targeting
                       :selection/valid-targets [:creature-uuid-1 player-1 :creature-uuid-2 player-2]}
          ;; Expected order: player-1, player-2 (sorted), then creature-1, creature-2
          result-0   (#'kb/select-nth-candidate selection-cards pending-sel 0)
          result-1   (#'kb/select-nth-candidate selection-cards pending-sel 1)
          result-2   (#'kb/select-nth-candidate selection-cards pending-sel 2)
          result-3   (#'kb/select-nth-candidate selection-cards pending-sel 3)]
      ;; First two should be players (sorted alphabetically)
      (is (= [::selection-events/toggle-selection player-1] result-0))
      (is (= [::selection-events/toggle-selection player-2] result-1))
      ;; Next two should be creatures in original order with :object/id extracted
      (is (= [::selection-events/toggle-selection :creature-uuid-1] result-2))
      (is (= [::selection-events/toggle-selection :creature-uuid-2] result-3)))))


(deftest select-nth-candidate-player-only-no-creatures-test
  (testing "select-nth-candidate: empty selection-cards with player keywords in valid-targets → players still selectable"
    (let [player-1   :player-1
          player-2   :player-2
          ;; No creatures — selection-cards is empty (subscription filtered out player keywords)
          selection-cards []
          pending-sel {:selection/domain :cast-time-targeting
                       :selection/valid-targets [player-2 player-1]}
          result-0   (#'kb/select-nth-candidate selection-cards pending-sel 0)
          result-1   (#'kb/select-nth-candidate selection-cards pending-sel 1)
          result-2   (#'kb/select-nth-candidate selection-cards pending-sel 2)]
      ;; Players sorted: player-1 first, player-2 second
      (is (= [::selection-events/toggle-selection player-1] result-0))
      (is (= [::selection-events/toggle-selection player-2] result-1))
      ;; Out of bounds → nil
      (is (nil? result-2)))))


(deftest select-nth-candidate-action-dispatch-select-1-test
  (testing ":select-1 dispatches toggle-selection for first candidate"
    (let [card-1     {:object/id :card-uuid-1}
          card-2     {:object/id :card-uuid-2}
          selection-cards [card-1 card-2]
          pending-sel {:selection/mechanism :n-slot-targeting
                       :selection/domain :object-target
                       :selection/valid-targets [:card-uuid-1 :card-uuid-2]}
          state      (assoc base-state :pending-selection pending-sel
                            :selection-cards selection-cards)
          result     (kb/action-dispatch :select-1 state)]
      (is (= [::selection-events/toggle-selection :card-uuid-1] result)))))


(deftest select-nth-candidate-action-dispatch-select-9-out-of-bounds-test
  (testing ":select-9 returns nil when only 3 candidates exist"
    (let [card-1     {:object/id :card-uuid-1}
          card-2     {:object/id :card-uuid-2}
          card-3     {:object/id :card-uuid-3}
          selection-cards [card-1 card-2 card-3]
          pending-sel {:selection/mechanism :n-slot-targeting
                       :selection/domain :object-target
                       :selection/valid-targets [:card-uuid-1 :card-uuid-2 :card-uuid-3]}
          state      (assoc base-state :pending-selection pending-sel
                            :selection-cards selection-cards)
          result     (kb/action-dispatch :select-9 state)]
      (is (nil? result)))))


(deftest keymap-flat-targeting-1-through-9-test
  (testing "flat-targeting keymap has bindings for 1-9"
    (is (= :select-1 (get kb/keymap [:flat-targeting "1"])))
    (is (= :select-2 (get kb/keymap [:flat-targeting "2"])))
    (is (= :select-3 (get kb/keymap [:flat-targeting "3"])))
    (is (= :select-4 (get kb/keymap [:flat-targeting "4"])))
    (is (= :select-5 (get kb/keymap [:flat-targeting "5"])))
    (is (= :select-6 (get kb/keymap [:flat-targeting "6"])))
    (is (= :select-7 (get kb/keymap [:flat-targeting "7"])))
    (is (= :select-8 (get kb/keymap [:flat-targeting "8"])))
    (is (= :select-9 (get kb/keymap [:flat-targeting "9"])))))


;; ---------------------------------------------------------------------------
;; P. Zone-pick chord keymap entries

(deftest keymap-zone-pick-1-through-9-chord-start-test
  (testing "zone-pick keymap has chord-start bindings for 1-9"
    (is (= :chord-start (get kb/keymap [:zone-pick "1"])))
    (is (= :chord-start (get kb/keymap [:zone-pick "2"])))
    (is (= :chord-start (get kb/keymap [:zone-pick "3"])))
    (is (= :chord-start (get kb/keymap [:zone-pick "4"])))
    (is (= :chord-start (get kb/keymap [:zone-pick "5"])))
    (is (= :chord-start (get kb/keymap [:zone-pick "6"])))
    (is (= :chord-start (get kb/keymap [:zone-pick "7"])))
    (is (= :chord-start (get kb/keymap [:zone-pick "8"])))
    (is (= :chord-start (get kb/keymap [:zone-pick "9"])))))


;; ---------------------------------------------------------------------------
;; Q. Zone-pick chord resolution: zone-pick-toggle-dispatch

(defn- make-card
  "Build a minimal card object for zone-pick chord tests.
   cmc is the card's mana value; object-id is the :object/id keyword."
  [object-id cmc]
  {:object/id object-id
   :object/card {:card/cmc cmc :card/name (name object-id) :card/types #{:instant}}})


(defn- make-land-card
  "Build a minimal land card object for zone-pick chord tests."
  [object-id]
  {:object/id object-id
   :object/card {:card/name (name object-id) :card/types #{:land}}})


(def ^:private zone-pick-selection
  "A zone-pick pending-selection for use in chord dispatch tests."
  {:selection/mechanism :pick-from-zone
   :selection/domain    :graveyard-return})


(deftest zone-pick-toggle-dispatch-basic-chord-test
  (testing "prefix \"2\", key \"1\" returns toggle-selection for first card in second pile"
    ;; 3 piles: lands, cmc=1, cmc=2
    ;; pile index 2 (1-based "2") → cmc=1 pile (index 1 of [[lands ..][1 ..][2 ..]])
    ;; card index 1 (1-based "1") → first card in that pile
    (let [land-1  (make-land-card :land-uuid)
          card-a  (make-card :card-a 1)
          card-b  (make-card :card-b 1)
          card-c  (make-card :card-c 2)
          selection-cards [land-1 card-a card-b card-c]
          result (#'kb/zone-pick-toggle-dispatch selection-cards zone-pick-selection "2" "1")]
      (is (= [::selection-events/toggle-selection :card-a] result)))))


(deftest zone-pick-toggle-dispatch-pile-1-card-2-test
  (testing "prefix \"1\", key \"2\" returns toggle-selection for second card in first pile"
    ;; First pile is lands (pile index 0 = 1-based "1")
    ;; Second card in lands pile
    (let [land-1  (make-land-card :land-a)
          land-2  (make-land-card :land-b)
          card-a  (make-card :card-a 1)
          selection-cards [land-1 land-2 card-a]
          result (#'kb/zone-pick-toggle-dispatch selection-cards zone-pick-selection "1" "2")]
      (is (= [::selection-events/toggle-selection :land-b] result)))))


(deftest zone-pick-toggle-dispatch-out-of-bounds-pile-test
  (testing "prefix \"5\" with only 3 piles returns nil"
    (let [land-1 (make-land-card :land-a)
          card-a (make-card :card-a 1)
          card-b (make-card :card-b 2)
          selection-cards [land-1 card-a card-b]
          result (#'kb/zone-pick-toggle-dispatch selection-cards zone-pick-selection "5" "1")]
      (is (nil? result)))))


(deftest zone-pick-toggle-dispatch-out-of-bounds-card-test
  (testing "prefix \"1\", key \"4\" with pile containing 2 cards returns nil"
    (let [card-a (make-card :card-a 1)
          card-b (make-card :card-b 1)
          selection-cards [card-a card-b]
          result (#'kb/zone-pick-toggle-dispatch selection-cards zone-pick-selection "1" "4")]
      (is (nil? result)))))


(deftest zone-pick-toggle-dispatch-non-numeric-prefix-test
  (testing "non-numeric prefix \"w\" returns nil (NaN check)"
    (let [card-a (make-card :card-a 1)
          selection-cards [card-a]
          result (#'kb/zone-pick-toggle-dispatch selection-cards zone-pick-selection "w" "1")]
      (is (nil? result)))))


(deftest zone-pick-toggle-dispatch-non-numeric-key-test
  (testing "non-numeric second key \"w\" returns nil (NaN check)"
    (let [card-a (make-card :card-a 1)
          selection-cards [card-a]
          result (#'kb/zone-pick-toggle-dispatch selection-cards zone-pick-selection "1" "w")]
      (is (nil? result)))))


(deftest zone-pick-toggle-dispatch-zero-prefix-test
  (testing "prefix \"0\" returns nil (0 - 1 = -1, nth with negative index = nil)"
    (let [card-a (make-card :card-a 1)
          selection-cards [card-a]
          result (#'kb/zone-pick-toggle-dispatch selection-cards zone-pick-selection "0" "1")]
      (is (nil? result)))))


(deftest zone-pick-toggle-dispatch-empty-selection-cards-test
  (testing "empty selection-cards returns nil"
    (let [result (#'kb/zone-pick-toggle-dispatch [] zone-pick-selection "1" "1")]
      (is (nil? result)))))


(deftest zone-pick-toggle-dispatch-single-pile-single-card-test
  (testing "single pile, single card: prefix \"1\", key \"1\" returns correct object-id"
    (let [card-a (make-card :card-a 3)
          selection-cards [card-a]
          result (#'kb/zone-pick-toggle-dispatch selection-cards zone-pick-selection "1" "1")]
      (is (= [::selection-events/toggle-selection :card-a] result)))))


;; ---------------------------------------------------------------------------
;; R. Zone-pick chord handle-keydown integration

(def ^:private zone-pick-selection-for-integration
  "A zone-pick pending-selection for integration tests."
  {:selection/mechanism :pick-from-zone
   :selection/domain    :graveyard-return})


(deftest handle-keydown-zone-pick-chord-dispatches-toggle-selection-test
  (testing "In zone-pick context, chord prefix \"2\" + \"1\" dispatches toggle-selection for first card of second pile"
    ;; Piles: [:lands [land-a]], [1 [card-a card-b]]
    ;; prefix="2" → pile index 1 → cmc=1 pile
    ;; key="1" → first card → :card-a
    (let [dispatched          (atom [])
          chord-prefix        (atom "2")
          land-a              (make-land-card :land-a)
          card-a              (make-card :card-a 1)
          card-b              (make-card :card-b 1)
          selection-cards     [land-a card-a card-b]
          pending-ref         (atom zone-pick-selection-for-integration)
          app-ref             (atom base-state)
          selection-cards-ref (atom selection-cards)
          event               #js {:key "1" :shiftKey false
                                   :target #js {:tagName "div"}
                                   :preventDefault (fn [])}]
      (with-redefs [rf/dispatch (fn [v] (swap! dispatched conj v))]
        (#'kb/handle-keydown event pending-ref app-ref chord-prefix selection-cards-ref))
      (is (nil? @chord-prefix) "chord-prefix-ref should be cleared after chord resolution")
      (is (= [[::selection-events/toggle-selection :card-a]] @dispatched)
          "toggle-selection for :card-a should be dispatched"))))


;; ---------------------------------------------------------------------------
;; S. Secondary action dispatch (Escape key)

(deftest action-dispatch-secondary-tutor-domain-test
  (testing ":secondary with domain :tutor → dispatches cancel + confirm"
    (let [sel   {:selection/domain :tutor}
          state (assoc base-state :pending-selection sel)
          result (kb/action-dispatch :secondary state)]
      (is (map? result))
      (is (= [[::selection-events/cancel-selection]
              [::selection-events/confirm-selection]]
             (:dispatch-n result))))))


(deftest action-dispatch-secondary-pile-choice-domain-test
  (testing ":secondary with domain :pile-choice → dispatches select-random-pile-choice"
    (let [sel   {:selection/domain :pile-choice}
          state (assoc base-state :pending-selection sel)
          result (kb/action-dispatch :secondary state)]
      (is (= [::selection-events/select-random-pile-choice] result)))))


(deftest action-dispatch-secondary-exile-cost-domain-test
  (testing ":secondary with domain :exile-cost → dispatches cancel-exile-cards-selection"
    (let [sel   {:selection/domain :exile-cost}
          state (assoc base-state :pending-selection sel)
          result (kb/action-dispatch :secondary state)]
      (is (= [::cost-events/cancel-exile-cards-selection] result)))))


(deftest action-dispatch-secondary-discard-domain-default-test
  (testing ":secondary with domain :discard → dispatches cancel-selection (default)"
    (let [sel   {:selection/domain :discard}
          state (assoc base-state :pending-selection sel)
          result (kb/action-dispatch :secondary state)]
      (is (= [::selection-events/cancel-selection] result)))))


(deftest action-dispatch-secondary-nil-domain-default-test
  (testing ":secondary with domain nil (flat-targeting) → dispatches cancel-selection (default)"
    (let [sel   {:selection/domain nil}
          state (assoc base-state :pending-selection sel)
          result (kb/action-dispatch :secondary state)]
      (is (= [::selection-events/cancel-selection] result)))))


(deftest action-dispatch-secondary-no-pending-selection-test
  (testing ":secondary with nil pending-selection → returns nil"
    (let [state base-state
          result (kb/action-dispatch :secondary state)]
      (is (nil? result)))))
