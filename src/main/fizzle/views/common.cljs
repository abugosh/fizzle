(ns fizzle.views.common)


(defn collapsible-zone
  "A collapsible zone wrapper. Takes a title string, a reagent atom
   for collapsed state, and child content."
  [title collapsed? & children]
  [:div {:class "mb-4"}
   [:div {:class "flex items-center cursor-pointer select-none gap-2"
          :on-click #(swap! collapsed? not)}
    [:span {:class "text-text-label text-xs font-bold"} title]
    [:span {:class "text-text-dim text-xs"}
     (if @collapsed? "\u25B6" "\u25BC")]]
   (when-not @collapsed?
     (into [:div {:class "mt-1.5"}] children))])
