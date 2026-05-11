(ns fizzle.views.selection.sequence-picker
  "Sequence picker component for storm object-target selection.
   Shows available targets and growing ordered sequence.

   The player picks targets one at a time in the order they want storm copies
   to resolve. The first-picked target resolves first (LIFO stack semantics
   are handled in the confirm handler).

   Event handlers:
     ::pick-storm-target — appends a target UUID to :selection/sequence
     ::undo-storm-target — removes the last UUID from :selection/sequence

   Component:
     storm-object-sequence-view — inline picker rendered by modals.cljs"
  (:require
    [fizzle.events.selection :as selection-events]
    [fizzle.subs.game :as subs]
    [fizzle.views.selection.common :as common]
    [re-frame.core :as rf]))


;; =====================================================
;; Event Handlers
;; =====================================================

(rf/reg-event-db
  ::pick-storm-target
  (fn [db [_ target-uuid]]
    (let [selection (:game/pending-selection db)
          sequence  (:selection/sequence selection)
          max-picks (:selection/max-picks selection)]
      (if (< (count sequence) max-picks)
        (assoc-in db [:game/pending-selection :selection/sequence]
                  (conj sequence target-uuid))
        db))))


(rf/reg-event-db
  ::undo-storm-target
  (fn [db [_]]
    (let [sequence (get-in db [:game/pending-selection :selection/sequence])]
      (if (seq sequence)
        (assoc-in db [:game/pending-selection :selection/sequence]
                  (pop sequence))
        db))))


;; =====================================================
;; Component
;; =====================================================

(defn storm-object-sequence-view
  "Inline sequence picker for storm object targeting.

   Reads :selection/valid-targets, :selection/sequence, :selection/max-picks,
   and :selection/source-name from the selection map.

   Renders:
     - Header with spell name and copy count
     - Clickable target buttons (disabled when sequence is full)
     - Ordered sequence list showing current picks
     - Undo and Confirm controls"
  [selection]
  (let [valid-targets (:selection/valid-targets selection)
        sequence      (:selection/sequence selection)
        max-picks     (:selection/max-picks selection)
        source-name   (:selection/source-name selection)
        target-names  @(rf/subscribe [::subs/storm-object-sequence-target-names])
        full?         (= (count sequence) max-picks)]
    [:div {:class "mb-4 border-2 border-border-accent rounded-lg p-3"}
     [:div {:class "text-text-label mb-3 text-xs"}
      (str "STORM TARGETS — "
           (or source-name "SPELL")
           " ("
           max-picks
           (if (= 1 max-picks) " COPY)" " COPIES)"))]
     [:div {:class "mb-3"}
      [:div {:class "text-text text-xs mb-2"}
       "Pick targets in resolution order (first picked resolves first):"]
      [:div {:class "flex flex-row gap-2 flex-wrap"}
       (map-indexed
         (fn [idx target-id]
           (let [card-name (get target-names target-id "Unknown")]
             ^{:key (str target-id "-" idx)}
             [:button {:class (str "py-1.5 px-3 border rounded font-bold text-sm "
                                   (if full?
                                     "cursor-not-allowed border-border bg-btn-disabled-bg text-perm-text-tapped"
                                     "cursor-pointer border-border-accent bg-surface-elevated text-text hover:bg-surface-raised"))
                       :disabled full?
                       :on-click #(rf/dispatch [::pick-storm-target target-id])}
              card-name]))
         valid-targets)]]
     [:div {:class "mb-3"}
      [:div {:class (str "text-xs mb-1 "
                         (if full? "text-health-good" "text-health-danger"))}
       (str "Sequence (" (count sequence) " / " max-picks "):")]
      (if (empty? sequence)
        [:div {:class "text-perm-text-tapped text-xs"} "(empty — click targets above)"]
        [:ol {:class "m-0 pl-5 text-sm text-text"}
         (map-indexed
           (fn [idx target-id]
             (let [card-name (get target-names target-id "Unknown")]
               ^{:key (str "seq-" idx)}
               [:li card-name]))
           sequence)])]
     [:div {:class "flex gap-3"}
      [:button {:class (str "py-2 px-4 border rounded text-sm font-bold "
                            (if (empty? sequence)
                              "cursor-not-allowed border-border bg-btn-disabled-bg text-perm-text-tapped"
                              "cursor-pointer border-border bg-surface-dim text-perm-text"))
                :disabled (empty? sequence)
                :on-click #(rf/dispatch [::undo-storm-target])}
       "Undo"]
      [common/confirm-button
       {:label    "Confirm"
        :valid?   full?
        :on-confirm #(rf/dispatch [::selection-events/confirm-selection])}]]]))
