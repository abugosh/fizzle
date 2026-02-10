(ns fizzle.views.opening-hand
  (:require
    [fizzle.engine.sorting :as sorting]
    [fizzle.events.opening-hand :as oh-events]
    [fizzle.subs.game :as game-subs]
    [fizzle.subs.opening-hand :as oh-subs]
    [re-frame.core :as rf]))


(defn- card-view
  [obj sculpted? selected? on-click]
  (let [card-name (get-in obj [:object/card :card/name])
        object-id (:object/id obj)]
    [:div {:class (str "border-2 rounded-md px-3.5 py-2.5 mx-1.5 cursor-pointer "
                       "min-w-[100px] text-center select-none text-text "
                       (cond
                         selected? "border-border-selected bg-surface-hover"
                         sculpted? "border-accent bg-surface-raised ring-2 ring-accent"
                         :else "border-border bg-surface-raised"))
           :on-click (when on-click #(on-click object-id))}
     card-name
     (when sculpted?
       [:div {:class "text-xs text-accent mt-1"} "\u2605"])]))


(defn- viewing-phase
  []
  (let [hand @(rf/subscribe [::game-subs/hand])
        sculpted-ids @(rf/subscribe [::oh-subs/sculpted-ids])
        mulligan-count @(rf/subscribe [::oh-subs/mulligan-count])
        library-count @(rf/subscribe [::oh-subs/library-count])]
    [:div {:class "max-w-2xl mx-auto p-8 text-center"}
     [:h2 {:class "text-text text-xl font-bold mb-2"} "Opening Hand"]
     [:div {:class "flex justify-center gap-4 text-text-muted text-sm mb-6"}
      [:span (str "Mulligans: " mulligan-count)]
      [:span (str "Library: " library-count " cards")]]
     [:div {:class "flex flex-wrap justify-center mb-6"}
      (for [obj (sorting/sort-cards hand)]
        ^{:key (:object/id obj)}
        [card-view obj
         (contains? sculpted-ids (:object/id obj))
         false
         nil])]
     [:div {:class "flex gap-4 justify-center mt-6"}
      [:button {:class "py-2 px-5 rounded font-bold text-sm cursor-pointer bg-btn-enabled-bg text-white"
                :on-click #(rf/dispatch [::oh-events/keep])}
       "Keep"]
      [:button {:class "py-2 px-5 rounded font-bold text-sm cursor-pointer bg-surface-raised text-text border border-border"
                :on-click #(rf/dispatch [::oh-events/mulligan])}
       "Mulligan"]]]))


(defn- bottoming-phase
  []
  (let [hand @(rf/subscribe [::game-subs/hand])
        sculpted-ids @(rf/subscribe [::oh-subs/sculpted-ids])
        mulligan-count @(rf/subscribe [::oh-subs/mulligan-count])
        bottom-selection @(rf/subscribe [::oh-subs/bottom-selection])
        valid? @(rf/subscribe [::oh-subs/bottom-selection-valid?])]
    [:div {:class "max-w-2xl mx-auto p-8 text-center"}
     [:h2 {:class "text-text text-xl font-bold mb-2"}
      (str "Put " mulligan-count " card" (when (> mulligan-count 1) "s")
           " on the bottom of your library")]
     [:div {:class "text-text-muted text-sm mb-6"}
      (str "Selected: " (count bottom-selection) " / " mulligan-count)]
     [:div {:class "flex flex-wrap justify-center mb-6"}
      (for [obj (sorting/sort-cards hand)]
        ^{:key (:object/id obj)}
        [card-view obj
         (contains? sculpted-ids (:object/id obj))
         (contains? bottom-selection (:object/id obj))
         #(rf/dispatch [::oh-events/toggle-bottom %])])]
     [:div {:class "flex justify-center mt-6"}
      [:button {:class (str "py-2 px-5 rounded font-bold text-sm "
                            (if valid?
                              "cursor-pointer bg-btn-enabled-bg text-white"
                              "cursor-default bg-btn-disabled-bg text-border opacity-50"))
                :disabled (not valid?)
                :on-click #(rf/dispatch [::oh-events/confirm-bottom])}
       "Confirm"]]]))


(defn opening-hand-screen
  []
  (let [phase @(rf/subscribe [::oh-subs/phase])]
    (case phase
      :bottoming [bottoming-phase]
      [viewing-phase])))
