(ns fizzle.core
  (:require
    [fizzle.bots.interceptor]
    [fizzle.engine.effects-registry]
    [fizzle.events.abilities]
    [fizzle.events.calculator :as calc-events]
    [fizzle.events.casting]
    [fizzle.events.cleanup]
    [fizzle.events.cycling]
    [fizzle.events.db-effect :as db-effect]
    [fizzle.events.init]
    [fizzle.events.lands]
    [fizzle.events.opening-hand]
    [fizzle.events.phases]
    [fizzle.events.priority-flow]
    [fizzle.events.resolution]
    [fizzle.events.selection]
    [fizzle.events.selection.library]
    [fizzle.events.selection.untap]
    [fizzle.events.selection.zone-ops]
    [fizzle.events.setup :as setup]
    [fizzle.events.ui :as ui-events]
    [fizzle.history.events]
    [fizzle.history.interceptor :as history-interceptor]
    [fizzle.snapshot.events :as snapshot]
    [fizzle.subs.calculator]
    [fizzle.subs.game :as subs]
    [fizzle.subs.history]
    [fizzle.subs.opening-hand]
    [fizzle.subs.setup]
    [fizzle.views.battlefield :as battlefield]
    [fizzle.views.calculator :as calculator]
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
    [reagent.dom.client :as rdom]))


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
   [collapsible-left-column "Graveyard" ::subs/gy-collapsed ::ui-events/toggle-gy-collapsed [graveyard/graveyard-view]]
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
    [zone-counts/zone-counts-view]
    [calculator/calculator-panel]]
   ;; Right sidebar: history
   [collapsible-right-column "History" ::subs/history-collapsed ::ui-events/toggle-history-collapsed [history/history-sidebar]]
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
                :on-click #(rf/dispatch [::ui-events/set-active-screen :game])}
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


(defonce root (rdom/create-root (.getElementById js/document "app")))

(defn ^:dev/after-load mount-root
  []
  (rf/clear-subscription-cache!)
  (rdom/render root [app]))


(defn init
  []
  (history-interceptor/register!)
  (db-effect/register!)            ; Custom :db effect handler — SBA+bot chokepoint
  ;; Always run normal init-setup first (sets up setup screen + config)
  (rf/dispatch-sync [::setup/init-setup])
  ;; Initialize calculator (restores queries from localStorage if available)
  (rf/dispatch-sync [::calc-events/init-calculator])
  ;; If the URL has a snapshot hash, restore it (overrides active-screen to :game)
  (let [hash     (.-hash js/location)
        restored (snapshot/restore-from-hash-handler hash)]
    (when restored
      ;; Clear the hash so refresh doesn't re-restore
      (.replaceState js/history nil "" (.-pathname js/location))
      (rf/dispatch-sync [:fizzle.snapshot.events/restore-from-snapshot restored])))
  (mount-root))
