(ns fizzle.core
  (:require
    [fizzle.bots.interceptor :as bot-interceptor]
    [fizzle.events.game :as events]
    [fizzle.events.opening-hand]
    [fizzle.events.selection]
    [fizzle.events.setup :as setup]
    [fizzle.history.events]
    [fizzle.history.interceptor :as history-interceptor]
    [fizzle.subs.game :as subs]
    [fizzle.subs.history]
    [fizzle.subs.opening-hand]
    [fizzle.subs.setup]
    [fizzle.views.battlefield :as battlefield]
    [fizzle.views.controls :as controls]
    [fizzle.views.game-over :as game-over]
    [fizzle.views.graveyard :as graveyard]
    [fizzle.views.hand :as hand]
    [fizzle.views.history :as history]
    [fizzle.views.mana-pool :as mana-pool]
    [fizzle.views.modals :as modals]
    [fizzle.views.opening-hand :as opening-hand]
    [fizzle.views.setup :as setup-view]
    [fizzle.views.stack :as stack]
    [fizzle.views.zone-counts :as zone-counts]
    [re-frame.core :as rf]
    [reagent.dom :as rdom]))


(defn- collapsible-right-column
  [title sub-key toggle-event & children]
  (let [collapsed? @(rf/subscribe [sub-key])]
    [:div {:class (str "border-l border-border bg-surface-raised overflow-y-auto "
                       (if collapsed? "right-col-collapsed" "right-col-expanded"))}
     [:div {:class "flex items-center cursor-pointer select-none gap-2 p-2 border-b border-border"
            :on-click #(rf/dispatch [toggle-event])}
      [:span {:class "text-text-label text-xs font-bold uppercase tracking-wider"} title]
      [:span {:class "text-text-dim text-xs"}
       (if collapsed? "\u25B6" "\u25BC")]]
     (when-not collapsed?
       (into [:div {:class "p-2"}] children))]))


(defn- collapsible-left-column
  [title sub-key toggle-event & children]
  (let [collapsed? @(rf/subscribe [sub-key])]
    [:div {:class (str "border-r border-border bg-surface-raised overflow-y-auto "
                       (if collapsed? "left-col-collapsed" "left-col-expanded"))}
     [:div {:class "flex items-center cursor-pointer select-none gap-2 p-2 border-b border-border"
            :on-click #(rf/dispatch [toggle-event])}
      [:span {:class "text-text-label text-xs font-bold uppercase tracking-wider"} title]
      [:span {:class "text-text-dim text-xs"}
       (if collapsed? "\u25B6" "\u25BC")]]
     (when-not collapsed?
       (into [:div {:class "p-2"}] children))]))


(defn- nav-btn-class
  [active?]
  (str "px-3 py-1 text-sm rounded cursor-pointer "
       (if active?
         "bg-accent text-surface font-bold"
         "bg-surface-raised text-text-muted")))


(defn- game-screen
  []
  [:div {:class "game-grid min-h-0"}
   ;; Left sidebar: graveyard
   [collapsible-left-column "Graveyard" ::subs/gy-collapsed ::events/toggle-gy-collapsed [graveyard/graveyard-view]]
   ;; Center column: primary interaction zones
   [:div {:class "p-4 overflow-y-auto min-w-[400px]"}
    [battlefield/battlefield-view]
    [stack/stack-view]
    [battlefield/phase-bar-section]
    [hand/hand-view]
    [controls/controls-view]
    [mana-pool/unless-pay-view]
    [:div {:class "flex gap-8"}
     [mana-pool/mana-pool-view]
     [mana-pool/storm-count-view]]
    [zone-counts/zone-counts-view]]
   ;; Right sidebar: history
   [collapsible-right-column "History" ::subs/history-collapsed ::events/toggle-history-collapsed [history/history-sidebar]]
   ;; Bottom: reserved for calculator panel
   [:div {:class "col-span-full"}]
   ;; Modals (overlay, not in grid flow)
   [modals/selection-modal]
   [modals/mode-selector-modal]
   [game-over/game-over-modal]])


(defn app
  []
  (let [screen @(rf/subscribe [::subs/active-screen])]
    [:div {:class "h-screen flex flex-col bg-surface font-mono text-text overflow-hidden"}
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
       :opening-hand [opening-hand/opening-hand-screen]
       [game-screen])]))


(defn ^:dev/after-load mount-root
  []
  (rf/clear-subscription-cache!)
  (rdom/render [app] (.getElementById js/document "app")))


(defn init
  []
  (history-interceptor/register!)
  (bot-interceptor/register!)
  (rf/dispatch-sync [::setup/init-setup])
  (mount-root))
