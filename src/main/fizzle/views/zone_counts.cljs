(ns fizzle.views.zone-counts
  (:require
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]))


(defn gy-count-class
  "Returns Tailwind class string for graveyard count based on threshold status."
  [threshold?]
  (if threshold?
    "text-accent font-bold"
    "text-text"))


(defn format-zone-count
  "Format a zone label and count for display."
  [label count]
  (str label ": " count))


(defn- zone-row
  "Render a single row of zone counts for one player."
  [label counts]
  (when counts
    (let [{:keys [graveyard library exile]} counts
          threshold? (:threshold? counts)]
      [:div {:class "flex gap-4 text-xs"}
       [:span {:class "text-text-label w-6"} label]
       [:span {:class (gy-count-class (boolean threshold?))}
        (format-zone-count "GY" graveyard)]
       [:span {:class "text-text"}
        (format-zone-count "Lib" library)]
       [:span {:class "text-text"}
        (format-zone-count "Exile" exile)]])))


(defn zone-counts-view
  "Always-visible zone counts for player and opponent.
   Shows GY, Library, and Exile counts with threshold indicator."
  []
  (let [player @(rf/subscribe [::subs/player-zone-counts])
        opponent @(rf/subscribe [::subs/opponent-zone-counts])]
    (when (or player opponent)
      [:div {:class "mb-4"}
       [:div {:class "text-text-label mb-1.5 text-xs"} "ZONES"]
       [zone-row "You" player]
       [zone-row "Opp" opponent]])))
