(ns fizzle.core
  (:require
    [fizzle.events.game :as events]
    [fizzle.events.selection]
    [fizzle.events.setup :as setup]
    [fizzle.history.events]
    [fizzle.history.interceptor :as history-interceptor]
    [fizzle.subs.game :as subs]
    [fizzle.subs.history]
    [fizzle.subs.setup]
    [fizzle.views.battlefield :as battlefield]
    [fizzle.views.controls :as controls]
    [fizzle.views.graveyard :as graveyard]
    [fizzle.views.hand :as hand]
    [fizzle.views.history :as history]
    [fizzle.views.mana-pool :as mana-pool]
    [fizzle.views.modals :as modals]
    [fizzle.views.opponent :as opponent]
    [fizzle.views.phase-bar :as phase-bar]
    [fizzle.views.setup :as setup-view]
    [fizzle.views.stack :as stack]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.dom :as rdom]))


(defonce stack-collapsed? (r/atom false))
(defonce gy-collapsed? (r/atom false))
(defonce history-collapsed? (r/atom false))


(defn- collapsible-right-column
  [title collapsed? & children]
  [:div {:class (str "border-l border-border bg-surface-raised overflow-y-auto "
                     (if @collapsed? "right-col-collapsed" "right-col-expanded"))}
   [:div {:class "flex items-center cursor-pointer select-none gap-2 p-2 border-b border-border"
          :on-click #(swap! collapsed? not)}
    [:span {:class "text-text-label text-xs font-bold uppercase tracking-wider"} title]
    [:span {:class "text-text-dim text-xs"}
     (if @collapsed? "\u25B6" "\u25BC")]]
   (when-not @collapsed?
     (into [:div {:class "p-2"}] children))])


(defn- nav-btn-class
  [active?]
  (str "px-3 py-1 text-sm rounded cursor-pointer "
       (if active?
         "bg-accent text-surface font-bold"
         "bg-surface-raised text-text-muted")))


(defn- game-screen
  []
  [:div {:class "game-grid"}
   ;; Left column: primary interaction zones
   [:div {:class "p-4 overflow-y-auto min-w-[400px]"}
    [phase-bar/phase-bar-view]
    [opponent/life-view]
    [battlefield/battlefield-view]
    [hand/hand-view]
    [controls/controls-view]
    [:div {:class "flex gap-8"}
     [mana-pool/mana-pool-view]
     [mana-pool/storm-count-view]]]
   ;; Right columns: reference panels
   [collapsible-right-column "Stack" stack-collapsed? [stack/stack-view]]
   [collapsible-right-column "Graveyard" gy-collapsed? [graveyard/graveyard-view]]
   [collapsible-right-column "History" history-collapsed? [history/history-sidebar]]
   ;; Bottom: reserved for calculator panel
   [:div {:class "col-span-full"}]
   ;; Modals (overlay, not in grid flow)
   [modals/selection-modal]
   [modals/mode-selector-modal]])


(defn app
  []
  (let [screen @(rf/subscribe [::subs/active-screen])]
    [:div {:class "min-h-screen bg-surface font-mono text-text"}
     ;; Header with nav
     [:div {:class "flex items-center gap-4 px-4 py-2 border-b border-border bg-surface-raised"}
      [:h1 {:class "text-text font-bold text-lg"} "Fizzle"]
      [:button {:class (nav-btn-class (= screen :setup))
                :on-click #(rf/dispatch [::setup/restore-setup])}
       "Setup"]
      [:button {:class (nav-btn-class (= screen :game))
                :on-click #(rf/dispatch [::events/set-active-screen :game])}
       "Game"]
      (when (= screen :game)
        [:button {:class "px-3 py-1 text-sm rounded cursor-pointer bg-surface-raised text-text-muted"
                  :on-click #(rf/dispatch [::setup/new-game])}
         "New Game"])]
     ;; Screen content
     (case screen
       :setup [setup-view/setup-screen]
       :opening-hand [:div {:class "p-8 text-center text-text"}
                      [:p "Opening hand screen (coming soon)"]]
       [game-screen])]))


(defn ^:dev/after-load mount-root
  []
  (rf/clear-subscription-cache!)
  (rdom/render [app] (.getElementById js/document "app")))


(defn init
  []
  (history-interceptor/register!)
  (rf/dispatch-sync [::setup/init-setup])
  (mount-root))
