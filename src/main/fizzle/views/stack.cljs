(ns fizzle.views.stack
  (:require
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]))


(defn- stack-item-view
  [item]
  (let [card-name (or (get-in item [:object/card :card/name])
                      (:stack-item/card-name item))
        desc (:stack-item/description item)
        label (cond
                (and card-name desc) (str card-name ": " desc)
                card-name card-name
                desc desc
                :else (when-let [t (:stack-item/type item)]
                        (str "Stack: " (name t))))]
    [:div {:class "border border-perm-border-tapped rounded py-1.5 px-2.5 mb-1 bg-surface-raised text-perm-text text-[13px]"}
     label]))


(defn stack-view
  []
  (let [stack @(rf/subscribe [::subs/stack])]
    [:div {:class "mb-4"}
     [:div {:class "text-text-label mb-1.5 text-xs"} "STACK"]
     (if (seq stack)
       [:div
        (for [[idx item] (map-indexed vector stack)]
          ^{:key idx}
          [stack-item-view item])]
       [:div {:class "text-border text-[13px]"} "Empty"])]))
