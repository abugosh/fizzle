(ns fizzle.core
  (:require
    [fizzle.events.game :as events]
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]
    [reagent.dom :as rdom]))


(defn app
  []
  (let [hand @(rf/subscribe [::subs/hand])
        mana @(rf/subscribe [::subs/mana-pool])
        storm @(rf/subscribe [::subs/storm-count])
        stack @(rf/subscribe [::subs/stack])]
    [:div {:style {:padding "20px"}}
     [:h1 "Fizzle"]
     [:p (str "Hand: " (count hand) " cards")]
     [:p (str "Mana: " mana)]
     [:p (str "Storm: " storm)]
     [:p (str "Stack: " (count stack) " items")]]))


(defn ^:dev/after-load mount-root
  []
  (rf/clear-subscription-cache!)
  (rdom/render [app] (.getElementById js/document "app")))


(defn init
  []
  (rf/dispatch-sync [::events/init-game])
  (mount-root))
