(ns fizzle.views.game-over
  (:require
    [fizzle.events.ui :as ui-events]
    [fizzle.subs.game :as subs]
    [fizzle.views.modals :as modals]
    [re-frame.core :as rf]))


(defn- condition-text
  "Human-readable text for the win/loss condition."
  [condition outcome]
  (case [condition outcome]
    [:life-zero :win] "Opponent's life reached 0"
    [:life-zero :loss] "Your life reached 0"
    [:empty-library :win] "Opponent failed to draw from empty library"
    [:empty-library :loss] "You failed to draw from empty library"
    (name condition)))


(defn- stat-row
  [label value]
  [:div {:class "flex justify-between py-1 border-b border-border"}
   [:span {:class "text-text-muted text-sm"} label]
   [:span {:class "text-text font-bold text-sm"} value]])


(defn game-over-modal
  "Modal overlay announcing game result with practice stats.
   Dismissable — game controls remain active after dismiss."
  []
  (let [show? @(rf/subscribe [::subs/show-game-over-modal?])
        result @(rf/subscribe [::subs/game-result])]
    (when (and show? result)
      (let [{:keys [outcome turn condition storm-count
                    opponent-life opponent-library-size]} result
            win? (= :win outcome)]
        [modals/modal-wrapper
         {:title (if win? "You Win!" "You Lose")
          :max-width "400px"
          :border-color (if win? "health-good" "health-danger")
          :text-align "center"}
         ;; Condition text
         [:p {:class (str "text-sm m-0 mb-4 "
                          (if win? "text-health-good" "text-health-danger"))}
          (condition-text condition outcome)]
         ;; Stats
         [:div {:class "mb-5 text-left"}
          [stat-row "Turn" turn]
          [stat-row "Win Condition" (condition-text condition outcome)]
          [stat-row "Storm Count" storm-count]
          [stat-row "Opponent Life" opponent-life]
          [stat-row "Opponent Library" (str opponent-library-size " cards")]]
         ;; Dismiss button
         [:button {:class (str "w-full py-3 border-none rounded-lg text-white text-base "
                               "cursor-pointer "
                               (if win? "bg-health-good" "bg-health-danger"))
                   :on-click #(rf/dispatch [::ui-events/dismiss-game-over])}
          "Continue Playing"]]))))
