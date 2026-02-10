(ns fizzle.views.battlefield
  (:require
    [fizzle.events.abilities :as ability-events]
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]))


(def ^:private mana-symbols
  {:white "W" :blue "U" :black "B" :red "R" :green "G"})


(defn mana-bg-class
  "Returns Tailwind bg + text classes for a mana button by color."
  [color]
  (case color
    :white "bg-mana-bg-white text-mana-white"
    :blue "bg-mana-bg-blue text-mana-blue"
    :black "bg-mana-bg-black text-mana-black"
    :red "bg-mana-bg-red text-mana-red"
    :green "bg-mana-bg-green text-mana-green"
    "bg-surface-dim text-mana-colorless"))


(defn get-producible-colors
  "Get the mana colors a permanent can produce from its abilities."
  [obj]
  (let [abilities (get-in obj [:object/card :card/abilities])
        mana-abilities (filter #(= :mana (:ability/type %)) abilities)]
    (if (seq mana-abilities)
      (let [produces-maps (keep :ability/produces mana-abilities)
            effect-mana-maps (->> mana-abilities
                                  (mapcat :ability/effects)
                                  (filter #(= :add-mana (:effect/type %)))
                                  (keep :effect/mana))
            all-mana-maps (concat produces-maps effect-mana-maps)
            has-any? (some :any all-mana-maps)]
        (if has-any?
          [:white :blue :black :red :green]
          (distinct (mapcat keys all-mana-maps))))
      [])))


(defn get-activated-abilities
  "Get non-mana activated abilities with their indices."
  [obj]
  (let [abilities (get-in obj [:object/card :card/abilities])]
    (->> abilities
         (map-indexed vector)
         (filter (fn [[_idx ability]] (= :activated (:ability/type ability))))
         (into []))))


(defn- mana-button
  [object-id mana-color tapped?]
  (let [symbol (get mana-symbols mana-color (name mana-color))]
    [:button {:class (str "py-0.5 px-1.5 mx-0.5 border border-border rounded text-[11px] font-bold "
                          (if tapped?
                            "cursor-default bg-surface-dim text-border"
                            (str "cursor-pointer " (mana-bg-class mana-color))))
              :disabled tapped?
              :on-click #(rf/dispatch [::ability-events/activate-mana-ability object-id mana-color])}
     symbol]))


(defn- ability-button
  [object-id ability-index label tapped?]
  [:button {:class (str "py-0.5 px-2 m-0.5 border border-border rounded text-[11px] font-bold "
                        (if tapped?
                          "cursor-default bg-surface-dim text-border"
                          "cursor-pointer bg-ability-bg text-ability-text"))
            :disabled tapped?
            :on-click #(rf/dispatch [::ability-events/activate-ability object-id ability-index])}
   label])


(defn- permanent-view
  [obj]
  (let [card-name (get-in obj [:object/card :card/name])
        object-id (:object/id obj)
        tapped? (:object/tapped obj)
        counters (:object/counters obj)
        producible-colors (get-producible-colors obj)
        activated-abilities (get-activated-abilities obj)]
    [:div {:class (str "border-2 rounded-md p-2 mr-1.5 mb-1.5 min-w-[100px] text-center "
                       (if tapped?
                         "border-perm-border-tapped bg-perm-bg-tapped text-perm-text-tapped rotate-[6deg]"
                         "border-perm-border bg-perm-bg text-perm-text"))}
     [:div {:class "text-[13px] mb-1"}
      card-name
      (when tapped? " (tapped)")]
     (when (and counters (seq counters))
       [:div {:class "text-[11px] text-text-dim mb-1"}
        (for [[counter-type count] counters]
          ^{:key counter-type}
          [:span {:class "mr-1.5"}
           (str (name counter-type) ": " count)])])
     (when (seq producible-colors)
       [:div {:class "flex justify-center flex-wrap"}
        (for [color producible-colors]
          ^{:key color}
          [mana-button object-id color tapped?])])
     (when (seq activated-abilities)
       [:div {:class "flex justify-center flex-wrap mt-1"}
        (for [[idx ability] activated-abilities]
          (let [label (or (:ability/name ability)
                          (when-let [desc (:ability/description ability)]
                            (if (> (count desc) 15)
                              (str (subs desc 0 12) "...")
                              desc))
                          "Activate")]
            ^{:key idx}
            [ability-button object-id idx label tapped?]))])]))


(defn battlefield-view
  []
  (let [{:keys [lands non-lands]} @(rf/subscribe [::subs/battlefield])]
    [:div {:class "mb-4"}
     [:div {:class "text-text-label mb-1.5 text-xs"} "BATTLEFIELD"]
     (if (or (seq non-lands) (seq lands))
       [:div
        (when (seq non-lands)
          [:div {:class "flex flex-wrap mb-2"}
           (for [obj non-lands]
             ^{:key (:object/id obj)}
             [permanent-view obj])])
        (when (and (seq non-lands) (seq lands))
          [:hr {:class "border-border mb-2"}])
        (when (seq lands)
          [:div {:class "flex flex-wrap"}
           (for [obj lands]
             ^{:key (:object/id obj)}
             [permanent-view obj])])]
       [:div {:class "text-border text-[13px]"} "No permanents"])]))
