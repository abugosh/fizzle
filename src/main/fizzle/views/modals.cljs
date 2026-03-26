(ns fizzle.views.modals
  "Selection modal router. Dispatches to pattern-based components
   in views/selection/ directory."
  (:require
    [fizzle.events.selection :as selection-events]
    [fizzle.subs.game :as subs]
    [fizzle.views.selection.accumulator :as accumulator]
    [fizzle.views.selection.common :as common]
    [fizzle.views.selection.custom :as custom]
    [fizzle.views.selection.reorder :as reorder]
    [fizzle.views.selection.zone-pick :as zone-pick]
    [re-frame.core :as rf]))


;; Re-exported for import_modal.cljs, game_over.cljs, storm_split_test.cljs
(def modal-wrapper common/modal-wrapper)
(def confirm-button common/confirm-button)
(def cancel-button common/cancel-button)
(def storm-split-target-label accumulator/storm-split-target-label)


;; === Dispatch ===

(defn- modal-dispatch-key
  [selection]
  (let [sel-type (:selection/type selection)
        target-req (:selection/target-requirement selection)]
    (cond
      (:selection/pattern selection) (:selection/pattern selection)
      (and (#{:cast-time-targeting :ability-targeting} sel-type)
           (= :player (:target/type target-req)))
      :targeting-player
      (and (#{:cast-time-targeting :ability-targeting} sel-type)
           (= :any (:target/type target-req)))
      :targeting-any
      :else sel-type)))


(defmulti render-selection-modal
  (fn [selection _cards] (modal-dispatch-key selection)))


(defn- object-target
  [s c zone sel-label unsel-label]
  [custom/object-target-modal s c
   {:select-event ::selection-events/toggle-selection
    :confirm-event ::selection-events/confirm-selection
    :default-zone zone :selected-label sel-label :unselected-label unsel-label}])


;; Pattern-based
(defmethod render-selection-modal :zone-pick [s c] [zone-pick/zone-pick-modal s c])


(defmethod render-selection-modal :reorder [s _]
  (case (:selection/type s)
    :scry [reorder/scry-modal s]
    :order-bottom [reorder/order-bottom-modal]
    :order-top [reorder/order-top-modal]
    :peek-and-reorder [reorder/peek-and-reorder-modal]))


(defmethod render-selection-modal :accumulator [s _]
  (case (:selection/type s)
    :x-mana-cost [accumulator/x-mana-selection-modal s]
    :pay-x-life [accumulator/pay-x-life-selection-modal s]
    :storm-split [accumulator/storm-split-modal s]
    nil))


;; Custom
(defmethod render-selection-modal :targeting-player [s _]
  [custom/player-target-modal s ::selection-events/confirm-selection])


(defmethod render-selection-modal :targeting-any [s c]
  [custom/any-target-modal s c ::selection-events/confirm-selection])


(defmethod render-selection-modal :player-target [s _]
  [custom/player-target-modal s ::selection-events/confirm-selection])


(defmethod render-selection-modal :cast-time-targeting [s c]
  (object-target s c :graveyard "1 card selected" "Select a card"))


(defmethod render-selection-modal :ability-targeting [s c]
  (object-target s c :battlefield "1 target selected" "Select a target"))


(defmethod render-selection-modal :ability-cast-targeting [s _]
  [custom/ability-cast-targeting-modal s])


(defmethod render-selection-modal :return-land-cost [s c]
  (object-target s c :battlefield "1 land selected" "Select a land to return to hand"))


(defmethod render-selection-modal :spell-mode [s c] [custom/spell-mode-selection-modal s c])
(defmethod render-selection-modal :discard-specific-cost [s c] [zone-pick/zone-pick-modal s c])
(defmethod render-selection-modal :unless-pay [_ _] nil)


;; Combat
(defmethod render-selection-modal :select-attackers [s c]
  [custom/attacker-selection-modal s c])


(defmethod render-selection-modal :assign-blockers [s c]
  [custom/blocker-selection-modal s c])


(defmethod render-selection-modal :default [s c] [zone-pick/zone-pick-modal s c])


;; === Public API ===

(defn selection-modal
  []
  (let [selection @(rf/subscribe [::subs/pending-selection])
        cards @(rf/subscribe [::subs/selection-cards])]
    (when selection
      (render-selection-modal selection cards))))


(def mode-selector-modal custom/mode-selector-modal)
