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


(defn top-stack-item-name
  [stack]
  (when-let [item (first stack)]
    (let [card-name (or (get-in item [:object/card :card/name])
                        (:stack-item/card-name item))
          desc (:stack-item/description item)]
      (cond
        card-name card-name
        desc desc
        :else (when-let [t (:stack-item/type item)]
                (name t))))))


(defn controls-view
  []
  (let [can-cast? @(rf/subscribe [::subs/can-cast?])
        can-play-land? @(rf/subscribe [::subs/can-play-land?])
        can-cycle? @(rf/subscribe [::subs/can-cycle?])
        selected @(rf/subscribe [::subs/selected-card])
        card-info @(rf/subscribe [::subs/selected-card-info])
        stack @(rf/subscribe [::subs/stack])
        play-enabled? (or can-cast? can-play-land?)
        play-label (cond
                     (and card-info (not (:land? card-info)))
                     (str "Cast " (:name card-info))

                     (and card-info (:land? card-info))
                     (str "Play " (:name card-info))

                     :else "Play")
        play-yield-label (cond
                           (and card-info (not (:land? card-info)))
                           (str "Cast & Yield " (:name card-info))

                           (and card-info (:land? card-info))
                           (str "Play & Yield " (:name card-info))

                           :else "Play & Yield")]
    [:div {:class "mb-4"}
     [:button {:class (btn-class play-enabled?)
               :disabled (not play-enabled?)
               :on-click (cond
                           can-cast? #(rf/dispatch [::events/cast-spell])
                           can-play-land? #(rf/dispatch [::events/play-land selected])
                           :else identity)}
      play-label]
     [:button {:class (btn-class play-enabled?)
               :disabled (not play-enabled?)
               :on-click (cond
                           can-cast? #(rf/dispatch [::events/cast-and-yield])
                           can-play-land? #(rf/dispatch [::events/play-land selected])
                           :else identity)}
      play-yield-label]
     (when can-cycle?
       [:button {:class (btn-class true)
                 :on-click #(rf/dispatch [::events/cycle-card selected])}
        (str "Cycle " (:name card-info))])
     [:button {:class (btn-class true)
               :on-click #(rf/dispatch [::events/yield])}
      (if-let [n (top-stack-item-name stack)]
        (str "Yield: " n)
        "Yield")]
     [:button {:class (btn-class true)
               :on-click #(rf/dispatch [::events/yield-all])}
      (if (seq stack)
        (str "Yield All (" (count stack) ")")
        "Yield All")]]))
