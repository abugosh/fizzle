(ns fizzle.views.selection.reorder
  "Reorder pattern modal components for pile-assignment selections.
   Handles scry, order-bottom, and peek-and-reorder."
  (:require
    [fizzle.events.selection :as selection-events]
    [fizzle.subs.game :as subs]
    [fizzle.views.card-styles :as card-styles]
    [fizzle.views.selection.common :as common]
    [re-frame.core :as rf]))


;; === Shared card views ===

(defn- scry-card-view
  "Card in scry modal showing pile assignment color."
  [obj pile]
  (let [card (:object/card obj)
        card-name (or (:card/name card) "Unknown")
        card-types (:card/types card)
        card-colors (:card/colors card)
        bg-class (case pile
                   :top "bg-scry-top-bg"
                   :bottom "bg-scry-bottom-bg"
                   (card-styles/get-color-identity-bg-class card-colors card-types))]
    [:div {:class (str "w-[100px] p-2 rounded-md border border-border-accent "
                       "text-center text-[11px] text-text cursor-pointer "
                       bg-class)}
     card-name]))


(defn- order-card-view
  "Card in order-bottom/peek-and-reorder modal with sequence number."
  [obj ordered? index]
  (let [card (:object/card obj)
        card-name (or (:card/name card) "Unknown")
        object-id (:object/id obj)
        card-types (:card/types card)
        card-colors (:card/colors card)
        border-class (if ordered?
                       "border-[3px] border-border-accent"
                       (str "border-2 " (card-styles/get-type-border-class card-types false)))
        bg-class (card-styles/get-color-identity-bg-class card-colors card-types)]
    [:div {:class (str "rounded-md px-3.5 py-2.5 cursor-pointer min-w-[90px] text-center "
                       "select-none text-text transition-all duration-100 "
                       border-class " " bg-class)
           :on-click #(rf/dispatch [(if ordered?
                                      ::selection-events/unorder-card
                                      ::selection-events/order-card)
                                    object-id])}
     (if ordered?
       (str (inc index) ". " card-name)
       card-name)]))


;; === Scry modal ===

(defn scry-modal
  "Modal for scry — assign cards to top or bottom piles."
  [selection]
  (let [scry-data @(rf/subscribe [::subs/scry-cards])
        all-cards (:cards scry-data)
        top-pile-ids (set (:selection/top-pile selection))
        bottom-pile-ids (set (:selection/bottom-pile selection))
        assigned (into top-pile-ids bottom-pile-ids)
        unassigned-cards (remove #(contains? assigned (:object/id %)) all-cards)
        top-cards (filter #(contains? top-pile-ids (:object/id %)) all-cards)
        bottom-cards (filter #(contains? bottom-pile-ids (:object/id %)) all-cards)]
    [:div {:class common/overlay-class}
     [:div {:class (common/container-class {})}
      [:h2 {:class common/header-class}
       (str "Scry " (count all-cards))]
      [:div {:class "mb-4"}
       [:h3 {:class "text-health-good text-sm m-0 mb-2"} "Top of Library (first)"]
       [:div {:class "flex flex-wrap gap-2 min-h-[40px]"}
        (for [obj top-cards]
          ^{:key (:object/id obj)}
          [:div {:on-click #(rf/dispatch [::selection-events/scry-unassign (:object/id obj)])}
           [scry-card-view obj :top]])]]
      [:div {:class "mb-4"}
       [:h3 {:class "text-text-muted text-sm m-0 mb-2"} "Revealed Cards"]
       [:div {:class "flex flex-wrap gap-2 min-h-[40px]"}
        (for [obj unassigned-cards]
          ^{:key (:object/id obj)}
          [:div {:class "flex gap-1 items-center"}
           [scry-card-view obj nil]
           [:div {:class "flex flex-col gap-0.5"}
            [:button {:class "py-1 px-2 bg-scry-top-bg border border-health-good rounded text-text cursor-pointer text-[10px]"
                      :on-click #(rf/dispatch [::selection-events/scry-assign-top (:object/id obj)])}
             "Top"]
            [:button {:class "py-1 px-2 bg-scry-bottom-bg border border-health-critical rounded text-text cursor-pointer text-[10px]"
                      :on-click #(rf/dispatch [::selection-events/scry-assign-bottom (:object/id obj)])}
             "Bottom"]]])]]
      [:div {:class "mb-5"}
       [:h3 {:class "text-health-critical text-sm m-0 mb-2"} "Bottom of Library (last)"]
       [:div {:class "flex flex-wrap gap-2 min-h-[40px]"}
        (for [obj bottom-cards]
          ^{:key (:object/id obj)}
          [:div {:on-click #(rf/dispatch [::selection-events/scry-unassign (:object/id obj)])}
           [scry-card-view obj :bottom]])]]
      [:button {:class "w-full p-3 bg-accent border-none rounded-lg text-white text-base cursor-pointer"
                :on-click #(rf/dispatch [::selection-events/confirm-selection])}
       "Confirm"]]]))


;; === Order-bottom modal ===

(defn- sequencing-modal
  "Generic sequencing modal for order-bottom and peek-and-reorder.
   Configuration:
     :title       - modal header
     :instruction - help text
     :sub-key     - subscription keyword for card data
     :extra-buttons-fn - (fn [data]) -> optional extra buttons"
  [{:keys [title instruction sub-key extra-buttons-fn]}]
  (let [data @(rf/subscribe [sub-key])
        ordered-cards (:ordered-cards data)
        unsequenced-cards (:unsequenced-cards data)
        all-ordered? (:all-ordered? data)]
    [common/modal-wrapper {:title title}
     [:p {:class "text-text-muted text-sm m-0 mb-4"} instruction]
     [:div {:class "mb-4"}
      [:h3 {:class "text-text-muted text-sm m-0 mb-2"} "Unsequenced"]
      [:div {:class "flex flex-wrap gap-2.5 min-h-[40px]"}
       (if (seq unsequenced-cards)
         (for [obj unsequenced-cards]
           ^{:key (:object/id obj)}
           [order-card-view obj false nil])
         [:div {:class "text-perm-text-tapped text-sm"} "All cards sequenced"])]]
     [:div {:class "mb-5"}
      [:h3 {:class "text-health-good text-sm m-0 mb-2"}
       (str "Ordered (" (count ordered-cards) ")")]
      [:div {:class "flex flex-wrap gap-2.5 min-h-[40px]"}
       (for [[idx obj] (map-indexed vector ordered-cards)]
         ^{:key (:object/id obj)}
         [order-card-view obj true idx])]]
     [:div {:class "flex justify-end gap-3"}
      [common/cancel-button {:label "Any Order"
                             :on-cancel #(rf/dispatch [::selection-events/any-order])}]
      (when extra-buttons-fn
        (extra-buttons-fn data))
      [common/confirm-button {:label "Confirm"
                              :valid? all-ordered?
                              :on-confirm #(rf/dispatch [::selection-events/confirm-selection])}]]]))


(defn order-bottom-modal
  "Modal for ordering cards on bottom of library."
  []
  [sequencing-modal
   {:title "Order cards for bottom of library"
    :instruction "Click cards to order them (first click = closest to rest of library). Click ordered cards to unsequence."
    :sub-key ::subs/order-bottom-cards}])


(defn order-top-modal
  "Modal for ordering cards on top of library."
  []
  [sequencing-modal
   {:title "Order cards for top of library"
    :instruction "Click cards to set order (first click = top of library). Click ordered cards to unsequence."
    :sub-key ::subs/order-top-cards}])


(defn peek-and-reorder-modal
  "Modal for reordering cards on top of library (Portent)."
  []
  [sequencing-modal
   {:title "Reorder top of library"
    :instruction "Click cards to set order (first click = top of library). Click ordered cards to unsequence."
    :sub-key ::subs/peek-and-reorder-cards
    :extra-buttons-fn
    (fn [data]
      (when (:may-shuffle? data)
        [common/cancel-button
         {:label "Shuffle Library"
          :on-cancel #(rf/dispatch [::selection-events/shuffle-and-confirm])}]))}])
