(ns fizzle.views.battlefield
  (:require
    [fizzle.engine.rules :as rules]
    [fizzle.events.abilities :as ability-events]
    [fizzle.events.ui :as ui-events]
    [fizzle.subs.game :as subs]
    [fizzle.views.card-styles :as card-styles]
    [fizzle.views.opponent :as opponent]
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


(def ^:private all-colors [:white :blue :black :red :green])


(defn get-mana-ability-buttons
  "Return one button spec per distinct mana ability activation path.

   Each entry is a map {:ability-index :color :amount :sac?}. Multiple mana
   abilities that produce the same color get separate entries (e.g. Crystal
   Vein's tap-for-{C} and tap+sac-for-{C}{C}). Abilities that produce
   {:any N} — whether via :ability/produces or an :add-mana effect — expand
   into one entry per color (W/U/B/R/G).

   The UI uses these to render distinguishable buttons and to dispatch
   activation with an explicit ability-index, which is required when two
   mana abilities on the same permanent produce the same color."
  [obj]
  (let [abilities (get-in obj [:object/card :card/abilities])
        mana-abilities (->> abilities
                            (map-indexed vector)
                            (filter (fn [[_ a]] (= :mana (:ability/type a)))))]
    (vec
      (mapcat
        (fn [[idx ability]]
          (let [produces (:ability/produces ability)
                effect-mana (->> (:ability/effects ability)
                                 (filter #(= :add-mana (:effect/type %)))
                                 first
                                 :effect/mana)
                mana-map (or produces effect-mana {})
                sac? (boolean (get-in ability [:ability/cost :sacrifice-self]))]
            (if (contains? mana-map :any)
              (let [amount (:any mana-map)]
                (for [color all-colors]
                  {:ability-index idx :color color :amount amount :sac? sac?}))
              (for [[color amount] mana-map]
                {:ability-index idx :color color :amount amount :sac? sac?}))))
        mana-abilities))))


(defn get-granted-mana-abilities
  "Get granted mana abilities from object grants.
   Returns vector of grant maps with :grant/type :ability and mana ability data."
  [obj]
  (->> (or (:object/grants obj) [])
       (filterv (fn [grant]
                  (and (= :ability (:grant/type grant))
                       (= :mana (:ability/type (:grant/data grant))))))))


(defn get-activated-abilities
  "Get non-mana activated abilities with their indices."
  [obj]
  (let [abilities (get-in obj [:object/card :card/abilities])]
    (->> abilities
         (map-indexed vector)
         (filter (fn [[_idx ability]] (= :activated (:ability/type ability))))
         (into []))))


(defn- mana-button
  [object-id {:keys [ability-index color amount sac?]} tapped?]
  (let [symbol (get mana-symbols color (name color))
        symbols (apply str (repeat amount symbol))
        label (if sac? (str "Sac: " symbols) symbols)]
    [:button {:class (str "py-0.5 px-1.5 mx-0.5 border rounded text-[11px] font-bold "
                          (if tapped?
                            "cursor-default border-border bg-surface-dim text-border"
                            (str "cursor-pointer "
                                 (if sac?
                                   "border-amber-500 bg-amber-100 text-amber-800"
                                   (str "border-border " (mana-bg-class color))))))
              :disabled tapped?
              :on-click #(rf/dispatch [::ability-events/activate-mana-ability
                                       object-id color nil ability-index])}
     label]))


(defn- granted-ability-button
  [object-id grant]
  (let [ability (:grant/data grant)
        produces (:ability/produces ability)
        color (first (keys produces))
        symbol (get mana-symbols color (name color))]
    [:button {:class "py-0.5 px-1.5 mx-0.5 border border-amber-500 rounded text-[11px] font-bold cursor-pointer bg-amber-100 text-amber-800"
              :on-click #(rf/dispatch [::ability-events/activate-granted-mana-ability
                                       object-id (:grant/id grant)])}
     (str "Sac: " symbol)]))


(defn- ability-button
  [object-id ability-index label tapped?]
  [:button {:class (str "py-0.5 px-2 m-0.5 border border-border rounded text-[11px] font-bold "
                        (if tapped?
                          "cursor-default bg-surface-dim text-border"
                          "cursor-pointer bg-ability-bg text-ability-text"))
            :disabled tapped?
            :on-click #(rf/dispatch [::ability-events/activate-ability object-id ability-index])}
   label])


(defn pt-text-class
  "Returns Tailwind text color class for a P/T modification."
  [mod]
  (case mod
    :buffed "text-green-400"
    :debuffed "text-red-400"
    ""))


(defn- permanent-view
  "Render a permanent card. Set show-buttons? false for opponent cards."
  ([obj] (permanent-view obj true))
  ([obj show-buttons?]
   (let [card-name (get-in obj [:object/card :card/name])
         object-id (:object/id obj)
         tapped? (:object/tapped obj)
         counters (:object/counters obj)
         mana-buttons (get-mana-ability-buttons obj)
         activated-abilities (get-activated-abilities obj)
         granted-abilities (get-granted-mana-abilities obj)
         card-types (get-in obj [:object/card :card/types])
         card-colors (get-in obj [:object/card :card/colors])
         border-class (card-styles/get-type-border-class card-types tapped?)
         bg-class (if tapped?
                    "bg-perm-bg-tapped"
                    (card-styles/get-color-identity-bg-class card-colors card-types))
         text-class (if tapped? "text-perm-text-tapped" "text-perm-text")
         creature-display (:creature/display obj)]
     [:div {:class (str "border-2 rounded-md p-2 mr-1.5 mb-1.5 min-w-[100px] text-center "
                        border-class " " bg-class " " text-class
                        (when tapped? " rotate-[6deg]"))}
      [:div {:class "text-[13px] mb-1"}
       card-name]
      (when (and counters (seq counters))
        [:div {:class "text-[11px] text-text-dim mb-1"}
         (for [[counter-type count] counters]
           ^{:key counter-type}
           [:span {:class "mr-1.5"}
            (str (name counter-type) ": " count)])])
      (when creature-display
        [:div {:class "text-[13px] font-bold mb-1 flex items-center justify-center gap-1"}
         [:span {:class (pt-text-class (:power-mod creature-display))}
          (str (:effective-power creature-display))]
         "/"
         [:span {:class (pt-text-class (:toughness-mod creature-display))}
          (str (:effective-toughness creature-display))]
         (when (pos? (:damage-marked creature-display))
           [:span {:class "ml-1 px-1 rounded bg-red-700 text-white text-[11px] font-bold"}
            (str (:damage-marked creature-display))])
         (when (:summoning-sick creature-display)
           [:span {:class "ml-1 px-1 rounded text-[10px] text-text-dim border border-text-dim"
                   :title "Summoning sickness"} "sick"])
         (when (:attacking creature-display)
           [:span {:class "ml-1 px-1 rounded bg-red-600 text-white text-[10px] font-bold"
                   :title "Attacking"} "ATK"])
         (when (:blocking creature-display)
           [:span {:class "ml-1 px-1 rounded bg-blue-600 text-white text-[10px] font-bold"
                   :title "Blocking"} "BLK"])])
      (when (and show-buttons? (seq mana-buttons))
        [:div {:class "flex justify-center flex-wrap"}
         (for [[i spec] (map-indexed vector mana-buttons)]
           ^{:key i}
           [mana-button object-id spec tapped?])])
      (when (and show-buttons? (seq activated-abilities))
        [:div {:class "flex justify-center flex-wrap mt-1"}
         (for [[idx ability] activated-abilities]
           (let [label (or (:ability/name ability)
                           (when-let [desc (:ability/description ability)]
                             (if (> (count desc) 15)
                               (str (subs desc 0 12) "...")
                               desc))
                           "Activate")]
             ^{:key idx}
             [ability-button object-id idx label tapped?]))])
      (when (and show-buttons? (seq granted-abilities))
        [:div {:class "flex justify-center flex-wrap mt-1"}
         (for [grant granted-abilities]
           ^{:key (:grant/id grant)}
           [granted-ability-button object-id grant])])])))


(defn- empty-row-placeholder
  "Render an empty row placeholder with a faint type label."
  [label]
  [:div {:class "min-h-[24px] flex items-center text-border text-[11px] italic"}
   label])


(defn- permanent-row
  "Render a row of permanents or an empty placeholder."
  [objects label show-buttons?]
  (if (seq objects)
    [:div {:class "flex flex-wrap mb-2"}
     (for [obj objects]
       ^{:key (:object/id obj)}
       [permanent-view obj show-buttons?])]
    [:div {:class "mb-2"}
     [empty-row-placeholder label]]))


(defn- stop-dot
  "Clickable stop indicator dot for a phase."
  [phase stops role]
  (let [has-stop? (contains? stops phase)]
    [:div {:class "flex justify-center cursor-pointer py-0.5"
           :on-click #(rf/dispatch [::ui-events/toggle-stop role phase])}
     [:div {:class (str "w-1.5 h-1.5 rounded-full "
                        (if has-stop? "bg-accent" "bg-surface-dim"))}]]))


(defn- phase-column
  "A single phase column: opponent dot, phase label, player dot — vertically stacked."
  [phase current-phase player-stops opponent-stops]
  [:div {:class "flex flex-col items-center"}
   [stop-dot phase opponent-stops :opponent]
   [:span {:class (str "py-1 px-2 rounded text-[11px] "
                       (if (= phase current-phase)
                         "bg-accent text-white"
                         "bg-transparent text-perm-text-tapped"))}
    (name phase)]
   [stop-dot phase player-stops :player]])


(defn phase-bar-section
  "Phase bar with life totals flanking it."
  []
  (let [current-phase (or @(rf/subscribe [::subs/current-phase]) :main1)
        current-turn (or @(rf/subscribe [::subs/current-turn]) 1)
        player-life @(rf/subscribe [::subs/player-life])
        opponent-life @(rf/subscribe [::subs/opponent-life])
        player-stops (or @(rf/subscribe [::subs/player-stops]) #{})
        opponent-stops (or @(rf/subscribe [::subs/opponent-stops]) #{})]
    [:div {:class "flex items-center justify-between gap-4 py-3 px-4 mb-2 mt-2 bg-surface-raised border-y border-surface-dim"}
     ;; Opponent life (left)
     [:div {:class "flex items-center gap-2"}
      [:span {:class "text-text-dim text-xs"} "Opp:"]
      [:span {:class (str "text-lg font-bold " (opponent/opponent-health-class opponent-life))}
       opponent-life]]
     ;; Phase indicators (center)
     [:div {:class "flex items-center gap-3"}
      [:span {:class "font-bold text-text text-sm"}
       (str "Turn " current-turn)]
      [:div {:class "flex items-center gap-1"}
       ;; Labels column
       [:div {:class "flex flex-col items-end mr-1"}
        [:span {:class "text-[10px] text-text-dim leading-[14px]"} "Opp"]
        [:span {:class "py-1 text-[11px] text-transparent"} "\u00a0"]
        [:span {:class "text-[10px] text-text-dim leading-[14px]"} "You"]]
       ;; Phase columns
       (for [phase rules/phases]
         ^{:key (name phase)}
         [phase-column phase current-phase player-stops opponent-stops])]]
     ;; Player life (right)
     [:div {:class "flex items-center gap-2"}
      [:span {:class "text-text-dim text-xs"} "You:"]
      [:span {:class (str "text-lg font-bold " (opponent/player-health-class player-life))}
       player-life]]]))


(defn battlefield-view
  "6-row mirrored battlefield: opponent (lands/other/creatures) + player (creatures/other/lands)"
  []
  (let [opponent-bf @(rf/subscribe [::subs/opponent-battlefield])
        player-bf @(rf/subscribe [::subs/battlefield])]
    [:div {:class "mb-4"}
     ;; Opponent battlefield (top 3 rows)
     [:div {:class "text-text-label mb-1.5 text-xs"} "OPPONENT BATTLEFIELD"]
     [permanent-row (:lands opponent-bf) "Lands" false]
     [permanent-row (:other opponent-bf) "Other" false]
     [permanent-row (:creatures opponent-bf) "Creatures" false]
     ;; Player battlefield (bottom 3 rows)
     [:div {:class "text-text-label mb-1.5 text-xs"} "YOUR BATTLEFIELD"]
     [permanent-row (:creatures player-bf) "Creatures" true]
     [permanent-row (:other player-bf) "Other" true]
     [permanent-row (:lands player-bf) "Lands" true]]))
