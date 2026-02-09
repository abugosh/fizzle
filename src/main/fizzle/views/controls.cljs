(ns fizzle.views.controls
  (:require
    [fizzle.events.game :as events]
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]))


(defn- btn-class
  [enabled?]
  (str "py-2 px-5 mr-2.5 border border-border rounded font-bold text-sm "
       (if enabled?
         "cursor-pointer bg-btn-enabled-bg text-white"
         "cursor-default bg-btn-disabled-bg text-border")))


(defn controls-view
  []
  (let [can-cast? @(rf/subscribe [::subs/can-cast?])
        can-play-land? @(rf/subscribe [::subs/can-play-land?])
        selected @(rf/subscribe [::subs/selected-card])
        stack @(rf/subscribe [::subs/stack])]
    [:div {:class "mb-4"}
     [:button {:class (btn-class can-cast?)
               :disabled (not can-cast?)
               :on-click #(rf/dispatch [::events/cast-spell])}
      "Cast"]
     [:button {:class (btn-class can-play-land?)
               :disabled (not can-play-land?)
               :on-click #(rf/dispatch [::events/play-land selected])}
      "Play Land"]
     [:button {:class (btn-class (seq stack))
               :disabled (empty? stack)
               :on-click #(rf/dispatch [::events/resolve-top])}
      "Resolve"]]))
