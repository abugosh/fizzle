(ns fizzle.views.timer
  (:require
    [fizzle.subs.game :as subs]
    [re-frame.core :as rf]
    [reagent.core :as reagent]))


(defn format-elapsed
  [elapsed-ms]
  (let [total-s (max 0 (quot (or elapsed-ms 0) 1000))
        h (quot total-s 3600)
        m (quot (mod total-s 3600) 60)
        s (mod total-s 60)]
    (if (pos? h)
      (str h ":" (when (< m 10) "0") m ":" (when (< s 10) "0") s)
      (str m ":" (when (< s 10) "0") s))))


(defn timer-display
  []
  (let [now-ms (reagent/atom (js/Date.now))
        interval-id (atom nil)]
    (reagent/create-class
      {:component-did-mount
       (fn [_]
         (reset! interval-id
                 (js/setInterval #(reset! now-ms (js/Date.now)) 1000)))
       :component-will-unmount
       (fn [_]
         (when-let [id @interval-id]
           (js/clearInterval id)))
       :reagent-render
       (fn []
         (let [start-ms @(rf/subscribe [::subs/timer-start-ms])
               game-over? @(rf/subscribe [::subs/game-over?])]
           (when start-ms
             (when game-over?
               (when-let [id @interval-id]
                 (js/clearInterval id)
                 (reset! interval-id nil)))
             [:span {:class "text-text text-sm ml-3"}
              (format-elapsed (- @now-ms start-ms))])))})))
