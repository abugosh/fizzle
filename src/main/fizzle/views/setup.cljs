(ns fizzle.views.setup
  (:require
    [fizzle.events.setup :as setup]
    [fizzle.subs.setup :as setup-subs]
    [re-frame.core :as rf]))


(defn- preset-bar
  []
  (let [presets @(rf/subscribe [::setup-subs/saved-presets])
        last-preset @(rf/subscribe [::setup-subs/last-preset])]
    [:div {:class "flex items-center gap-2"}
     (if (seq presets)
       [:select {:class "bg-surface-raised border border-border rounded px-2 py-1 text-sm text-text"
                 :value (or last-preset "")
                 :on-change #(let [v (.. % -target -value)]
                               (when (seq v)
                                 (rf/dispatch [::setup/load-preset v])))}
        [:option {:value ""} "Select preset..."]
        (for [name presets]
          ^{:key name}
          [:option {:value name} name])]
       [:span {:class "text-text-dim text-sm"} "No saved presets"])
     [:button {:class "px-3 py-1 text-sm border border-border rounded cursor-pointer bg-surface-raised text-text"
               :on-click #(when-let [name (js/prompt "Preset name:")]
                            (when (seq (.trim name))
                              (rf/dispatch [::setup/save-preset (.trim name)])))}
      "Save As"]
     (when last-preset
       [:button {:class "px-3 py-1 text-sm border border-border rounded cursor-pointer bg-surface-raised text-text"
                 :on-click #(rf/dispatch [::setup/delete-preset last-preset])}
        "Delete"])
     [:button {:class (str "px-3 py-1 text-sm border border-border rounded "
                           (if last-preset
                             "cursor-pointer bg-btn-enabled-bg text-white"
                             "cursor-default bg-btn-disabled-bg text-border"))
               :disabled (nil? last-preset)
               :on-click #(rf/dispatch [::setup/quick-start])}
      "Quick Start"]]))


(defn- deck-selector
  []
  (let [decks @(rf/subscribe [::setup-subs/available-decks])
        selected @(rf/subscribe [::setup-subs/selected-deck])]
    [:div {:class "flex items-center gap-2"}
     [:label {:class "text-text-label text-xs font-bold uppercase tracking-wider"} "Deck:"]
     [:select {:class "bg-surface-raised border border-border rounded px-2 py-1 text-sm text-text"
               :value (if selected (name selected) "")
               :on-change #(rf/dispatch [::setup/select-deck (keyword (.. % -target -value))])}
      (for [{:keys [deck/id deck/name]} decks]
        ^{:key id}
        [:option {:value (cljs.core/name id)} name])]]))


(defn- card-row
  [{:keys [card/id card/name count]} direction]
  [:div {:class "flex justify-between items-center text-sm py-0.5"}
   (if (= direction :to-side)
     [:<>
      [:span (str count "x " name)]
      [:button {:class "px-1 text-text-muted cursor-pointer"
                :on-click #(rf/dispatch [::setup/move-to-side id])}
       "\u2192"]]
     [:<>
      [:button {:class "px-1 text-text-muted cursor-pointer"
                :on-click #(rf/dispatch [::setup/move-to-main id])}
       "\u2190"]
      [:span (str count "x " name)]])])


(def ^:private type-order [:land :instant :sorcery :artifact :creature :enchantment :other])


(def ^:private type-labels
  {:land "Lands" :instant "Instants" :sorcery "Sorceries"
   :artifact "Artifacts" :creature "Creatures" :enchantment "Enchantments"
   :other "Other"})


(defn- main-deck-panel
  []
  (let [grouped @(rf/subscribe [::setup-subs/current-main-grouped])
        main-count @(rf/subscribe [::setup-subs/main-count])]
    [:div {:class "p-2 border-r border-border"}
     [:div {:class (str "text-xs font-bold uppercase tracking-wider mb-2 "
                        (if (>= main-count 60) "text-text-label" "text-error"))}
      (str "Maindeck (" main-count ")")]
     (for [card-type type-order
           :let [entries (get grouped card-type)]
           :when (seq entries)]
       ^{:key card-type}
       [:div {:class "mb-2"}
        [:div {:class "text-text-label text-xs uppercase"} (get type-labels card-type)]
        (for [entry entries]
          ^{:key (:card/id entry)}
          [card-row entry :to-side])])]))


(defn- sideboard-panel
  []
  (let [side-count @(rf/subscribe [::setup-subs/side-count])
        card-lookup @(rf/subscribe [::setup-subs/current-side-named])]
    [:div {:class "p-2"}
     [:div {:class (str "text-xs font-bold uppercase tracking-wider mb-2 "
                        "text-text-label")}
      (str "Sideboard (" side-count ")")]
     (for [entry card-lookup]
       ^{:key (:card/id entry)}
       [card-row entry :to-main])]))


(defn- deck-panels
  []
  [:div {:class "grid grid-cols-[2fr_1fr] border border-border rounded"}
   [main-deck-panel]
   [sideboard-panel]])


(defn- opponent-config
  []
  (let [clock-turns @(rf/subscribe [::setup-subs/clock-turns])]
    [:div {:class "flex items-center gap-2"}
     [:label {:class "text-text-label text-xs font-bold uppercase tracking-wider"}
      "Goldfish Clock (turns)"]
     [:input {:class "bg-surface-raised border border-border rounded px-2 py-1 text-sm text-text w-16"
              :type "number"
              :min 1
              :max 20
              :value (or clock-turns 4)
              :on-change #(let [n (js/parseInt (.. % -target -value))]
                            (when-not (js/isNaN n)
                              (rf/dispatch [::setup/set-clock-turns n])))}]]))


(defn- action-buttons
  []
  (let [valid? @(rf/subscribe [::setup-subs/deck-valid?])]
    [:div {:class "flex gap-2 justify-end"}
     [:button {:class (str "py-2 px-5 border border-border rounded font-bold text-sm "
                           (if valid?
                             "cursor-pointer bg-btn-enabled-bg text-white"
                             "cursor-default bg-btn-disabled-bg text-border opacity-50"))
               :disabled (not valid?)
               :on-click #(rf/dispatch [::setup/start-game])}
      "Start Game"]]))


(defn setup-screen
  []
  [:div {:class "max-w-4xl mx-auto p-4 space-y-4"}
   [preset-bar]
   [deck-selector]
   [deck-panels]
   [opponent-config]
   [action-buttons]])
