(ns fizzle.views.modals
  "Selection modal router. Dispatches on :selection/mechanism (ADR-030).
   Each of the 7 mechanism keywords routes to the appropriate view component.
   Domain-specific sub-variants are handled inside each mechanism defmethod."
  (:require
    [fizzle.events.selection :as selection-events]
    [fizzle.subs.game :as subs]
    [fizzle.views.selection.accumulator :as accumulator]
    [fizzle.views.selection.binary-choice :as binary-choice-view]
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
  (:selection/mechanism selection))


(defn- object-target
  [s c zone sel-label unsel-label]
  [custom/object-target-modal s c
   {:select-event ::selection-events/toggle-selection
    :confirm-event ::selection-events/confirm-selection
    :default-zone zone :selected-label sel-label :unselected-label unsel-label}])


(defn- cast-time-targeting-labels
  "Pure function — compute modal labels for :cast-time-targeting domain.
   Handles both single-slot (select-count=1) and multi-slot (select-count>1).
   Returns {:header :unselected-label :selected-label}."
  [selection]
  (let [n (:selection/select-count selection)
        filled (count (:selection/selected selection))]
    (if (= n 1)
      {:header          "Choose target from battlefield"
       :unselected-label "Select a target"
       :selected-label   "1 target selected"}
      {:header          (str "Choose " n " targets from battlefield")
       :unselected-label (str "Select " n " targets")
       :selected-label   (str filled "/" n " targets selected")})))


;; === render-selection (ADR-033 unified dispatch) ===
;;
;; Multimethod that returns tagged tuples [:modal component] or [:inline component] or nil.
;; Dispatches on :selection/mechanism; domain-specific handling inside each defmethod.

(defmulti render-selection
  (fn [selection _cards] (modal-dispatch-key selection)))


(defmethod render-selection :pick-from-zone [s c]
  (case (:selection/domain s)
    :return-land-cost (let [component (object-target s c :battlefield "1 land selected" "Select a land to return to hand")]
                        [:modal component])
    [:modal [zone-pick/zone-pick-modal s c]]))


(defmethod render-selection :reorder [s _]
  (case (:selection/domain s)
    :scry           [:modal [reorder/scry-modal s]]
    :order-bottom   [:modal [reorder/order-bottom-modal]]
    :order-top      [:modal [reorder/order-top-modal]]
    :peek-and-reorder [:modal [reorder/peek-and-reorder-modal]]))


(defmethod render-selection :accumulate [s _]
  (case (:selection/domain s)
    :x-mana-cost  [:modal [accumulator/x-mana-selection-modal s]]
    :pay-x-life   [:modal [accumulator/pay-x-life-selection-modal s]]
    :storm-split  [:modal [accumulator/storm-split-modal s]]
    nil))


(defmethod render-selection :allocate-resource [_ _] nil)


(defmethod render-selection :n-slot-targeting [s c]
  (let [target-req (or (first (:selection/target-requirements s))
                       (:selection/target-requirement s))
        target-type (:target/type target-req)]
    (case (:selection/domain s)
      :player-target         [:modal [custom/player-target-modal s ::selection-events/confirm-selection]]
      :cast-time-targeting   (case target-type
                               :player [:modal [custom/player-target-modal s ::selection-events/confirm-selection]]
                               :any    [:modal [custom/any-target-modal s c ::selection-events/confirm-selection]]
                               (let [{:keys [selected-label unselected-label]}
                                     (cast-time-targeting-labels s)]
                                 [:modal (object-target s c :battlefield selected-label unselected-label)]))
      :ability-targeting     (if (= :player target-type)
                               [:modal [custom/player-target-modal s ::selection-events/confirm-selection]]
                               (if (= :any target-type)
                                 [:modal [custom/any-target-modal s c ::selection-events/confirm-selection]]
                                 [:modal (object-target s c :battlefield "1 target selected" "Select a target")]))
      :ability-cast-targeting [:modal [custom/ability-cast-targeting-modal s]]
      :select-attackers      [:modal [custom/attacker-selection-modal s c]]
      :assign-blockers       [:modal [custom/blocker-selection-modal s c]]
      ;; Unknown domain falls to object-target as safe default
      [:modal (object-target s c :battlefield "1 target selected" "Select a target")])))


(defmethod render-selection :pick-mode [s c]
  (case (:selection/domain s)
    :spell-mode      [:modal [custom/spell-mode-selection-modal s c]]
    :land-type-source [:modal [custom/land-type-selection-modal s]]
    :land-type-target [:modal [custom/land-type-selection-modal s]]
    nil))


(defmethod render-selection :binary-choice [s _]
  [:inline [binary-choice-view/binary-choice-view s]])


(defmethod render-selection :default [s c] [:modal [zone-pick/zone-pick-modal s c]])


;; === Public API ===

(defn selection-render
  "Returns tagged result: [:modal component], [:inline component], or nil.
   Used by core.cljs game-screen for unified routing."
  []
  (let [selection @(rf/subscribe [::subs/pending-selection])
        cards @(rf/subscribe [::subs/selection-cards])]
    (when selection
      (render-selection selection cards))))
