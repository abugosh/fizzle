(ns fizzle.views.modals
  "Selection modal router. Dispatches on :selection/mechanism (ADR-030).
   Each of the 7 mechanism keywords routes to the appropriate view component.
   Domain-specific sub-variants are handled inside each mechanism defmethod."
  (:require
    [fizzle.events.selection :as selection-events]
    [fizzle.subs.game :as subs]
    [fizzle.views.selection.accumulator :as accumulator]
    [fizzle.views.selection.common :as common]
    [fizzle.views.selection.custom :as custom]
    [fizzle.views.selection.reorder :as reorder]
    [fizzle.views.selection.replacement :as replacement-view]
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


(defmulti render-selection-modal
  (fn [selection _cards] (modal-dispatch-key selection)))


(defn- object-target
  [s c zone sel-label unsel-label]
  [custom/object-target-modal s c
   {:select-event ::selection-events/toggle-selection
    :confirm-event ::selection-events/confirm-selection
    :default-zone zone :selected-label sel-label :unselected-label unsel-label}])


;; -----------------------------------------------------------------------
;; :pick-from-zone — select N cards/objects from a zone and act on them.
;; Covers: :discard, :graveyard-return, :shuffle-to-library, :hand-reveal-discard,
;; :chain-bounce, :chain-bounce-target, :untap-lands, :discard-cost,
;; :return-land-cost, :sacrifice-cost, :exile-cost, :tutor, :peek-and-select, :pile-choice.
;; All render as zone-pick modal.
;;
;; Exception: :return-land-cost uses a targeted object-target modal.
;;
(defmethod render-selection-modal :pick-from-zone [s c]
  (case (:selection/domain s)
    :return-land-cost (object-target s c :battlefield "1 land selected" "Select a land to return to hand")
    [zone-pick/zone-pick-modal s c]))


;; -----------------------------------------------------------------------
;; :reorder — assign cards into ordered positions (piles, top/bottom of library).
;; Dispatches on domain to route to the correct reorder sub-component.

(defmethod render-selection-modal :reorder [s _]
  (case (:selection/domain s)
    :scry           [reorder/scry-modal s]
    :order-bottom   [reorder/order-bottom-modal]
    :order-top      [reorder/order-top-modal]
    :peek-and-reorder [reorder/peek-and-reorder-modal]))


;; -----------------------------------------------------------------------
;; :accumulate — distribute/increment a numeric value via stepper controls.
;; Covers: :storm-split, :x-mana-cost, :pay-x-life.

(defmethod render-selection-modal :accumulate [s _]
  (case (:selection/domain s)
    :x-mana-cost  [accumulator/x-mana-selection-modal s]
    :pay-x-life   [accumulator/pay-x-life-selection-modal s]
    :storm-split  [accumulator/storm-split-modal s]
    nil))


;; -----------------------------------------------------------------------
;; :allocate-resource — assign mana from a pool to typed cost slots.
;; The mana-allocation UI is handled by the mana pool component directly
;; (re-frame subscription, not a modal). No modal overlay needed.

(defmethod render-selection-modal :allocate-resource [_ _] nil)


;; -----------------------------------------------------------------------
;; :n-slot-targeting — fill N target slots from a valid-targets set.
;; Sub-dispatches on domain:
;;   :player-target         → player target picker
;;   :cast-time-targeting   → cast-time object targeting (any/player by target-req type)
;;   :ability-targeting     → ability target picker (any/player by target-req type)
;;   :ability-cast-targeting → combined ability+cast targeting modal
;;   :select-attackers      → attacker declaration modal
;;   :assign-blockers       → blocker assignment modal

(defmethod render-selection-modal :n-slot-targeting [s c]
  (let [target-req (:selection/target-requirement s)
        target-type (:target/type target-req)]
    (case (:selection/domain s)
      :player-target         [custom/player-target-modal s ::selection-events/confirm-selection]
      :cast-time-targeting   (if (= :player target-type)
                               [custom/player-target-modal s ::selection-events/confirm-selection]
                               (object-target s c :graveyard "1 card selected" "Select a card"))
      :ability-targeting     (if (= :player target-type)
                               [custom/player-target-modal s ::selection-events/confirm-selection]
                               (if (= :any target-type)
                                 [custom/any-target-modal s c ::selection-events/confirm-selection]
                                 (object-target s c :battlefield "1 target selected" "Select a target")))
      :ability-cast-targeting [custom/ability-cast-targeting-modal s]
      :select-attackers      [custom/attacker-selection-modal s c]
      :assign-blockers       [custom/blocker-selection-modal s c]
      ;; Unknown domain falls to object-target as safe default
      (object-target s c :battlefield "1 target selected" "Select a target"))))


;; -----------------------------------------------------------------------
;; :pick-mode — choose one named option from a finite non-card list.
;; Covers: :spell-mode, :land-type-source, :land-type-target.

(defmethod render-selection-modal :pick-mode [s c]
  (case (:selection/domain s)
    :spell-mode      [custom/spell-mode-selection-modal s c]
    :land-type-source [custom/land-type-selection-modal s]
    :land-type-target [custom/land-type-selection-modal s]
    nil))


;; -----------------------------------------------------------------------
;; :binary-choice — choose one action from a small fixed set of action keywords.
;; Covers: :replacement-choice (proceed/redirect), :unless-pay (pay/decline).

(defmethod render-selection-modal :binary-choice [s _]
  (case (:selection/domain s)
    :replacement-choice [replacement-view/replacement-choice-modal s]
    :unless-pay         nil
    nil))


(defmethod render-selection-modal :default [s c] [zone-pick/zone-pick-modal s c])


;; === Public API ===

(defn selection-modal
  []
  (let [selection @(rf/subscribe [::subs/pending-selection])
        cards @(rf/subscribe [::subs/selection-cards])]
    (when selection
      (render-selection-modal selection cards))))

