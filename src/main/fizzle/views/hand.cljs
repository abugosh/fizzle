(ns fizzle.views.hand
  (:require
    [fizzle.events.game :as events]
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]))


(defn- card-view
  [obj selected?]
  (let [card-name (get-in obj [:object/card :card/name])
        object-id (:object/id obj)]
    [:div {:class (str "border-2 rounded-md px-3.5 py-2.5 mx-1.5 cursor-pointer "
                       "min-w-[100px] text-center select-none text-text "
                       (if selected?
                         "border-border-selected bg-surface-hover"
                         "border-border bg-surface-raised"))
           :on-click #(rf/dispatch [::events/select-card object-id])}
     card-name]))


(defn hand-view
  []
  (let [hand @(rf/subscribe [::subs/hand])
        selected @(rf/subscribe [::subs/selected-card])]
    [:div {:class "mb-4"}
     [:div {:class "text-text-label mb-1.5 text-xs"} "HAND"]
     [:div {:class "flex flex-wrap"}
      (for [obj hand]
        ^{:key (:object/id obj)}
        [card-view obj (= (:object/id obj) selected)])]]))
