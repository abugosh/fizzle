(ns fizzle.views.history
  (:require
    [fizzle.history.events :as events]
    [fizzle.subs.history :as subs]
    [re-frame.core :as rf]))


(defn- step-controls
  []
  (let [can-back? @(rf/subscribe [::subs/can-step-back?])
        can-fwd? @(rf/subscribe [::subs/can-step-forward?])]
    [:div {:style {:display "flex" :gap "8px" :margin-bottom "12px"}}
     [:button {:style {:padding "4px 12px"
                       :border "1px solid #555"
                       :border-radius "4px"
                       :cursor (if can-back? "pointer" "default")
                       :background (if can-back? "#2a6a2a" "#222")
                       :color (if can-back? "#fff" "#555")
                       :font-size "13px"
                       :font-weight "bold"
                       :flex 1}
               :disabled (not can-back?)
               :on-click #(rf/dispatch [::events/step-back])}
      "\u25C0 Back"]
     [:button {:style {:padding "4px 12px"
                       :border "1px solid #555"
                       :border-radius "4px"
                       :cursor (if can-fwd? "pointer" "default")
                       :background (if can-fwd? "#2a6a2a" "#222")
                       :color (if can-fwd? "#fff" "#555")
                       :font-size "13px"
                       :font-weight "bold"
                       :flex 1}
               :disabled (not can-fwd?)
               :on-click #(rf/dispatch [::events/step-forward])}
      "Fwd \u25B6"]]))


(defn- action-log
  []
  (let [entries @(rf/subscribe [::subs/entries])
        position @(rf/subscribe [::subs/position])]
    [:div {:style {:flex 1 :overflow-y "auto" :margin-bottom "8px"}}
     (if (seq entries)
       (for [[idx entry] (map-indexed vector entries)]
         ^{:key idx}
         [:div {:style {:padding "4px 8px"
                        :font-size "12px"
                        :color (if (> idx position) "#666" "#eee")
                        :opacity (if (> idx position) 0.4 1)
                        :border-left (if (= idx position)
                                       "3px solid #4A9BD9"
                                       "3px solid transparent")
                        :background (if (= idx position) "#1a2a3a" "transparent")}}
          (str "T" (:entry/turn entry) ": " (:entry/description entry))])
       [:div {:style {:color "#555" :font-size "12px" :padding "4px 8px"}}
        "No actions yet"])]))


(defn- fork-list
  []
  (let [forks @(rf/subscribe [::subs/forks])
        current-branch @(rf/subscribe [::subs/current-branch])]
    [:div {:style {:border-top "1px solid #444"
                   :padding-top "8px"
                   :margin-top "8px"}}
     [:div {:style {:color "#999"
                    :font-size "11px"
                    :text-transform "uppercase"
                    :letter-spacing "1px"
                    :margin-bottom "4px"}}
      "Branches"]
     [:div {:style {:font-size "12px"
                    :padding "2px 8px"
                    :cursor (if (nil? current-branch) "default" "pointer")
                    :color (if (nil? current-branch) "#4A9BD9" "#ccc")
                    :font-weight (if (nil? current-branch) "bold" "normal")}
            :on-click (when (some? current-branch)
                        #(rf/dispatch [::events/switch-branch nil]))}
      "main"]
     (for [fork forks]
       (let [fork-id (:fork/id fork)
             active? (= fork-id current-branch)]
         ^{:key fork-id}
         [:div {:style {:font-size "12px"
                        :padding "2px 8px"
                        :cursor (if active? "default" "pointer")
                        :color (if active? "#4A9BD9" "#ccc")
                        :font-weight (if active? "bold" "normal")}
                :on-click (when-not active?
                            #(rf/dispatch [::events/switch-branch fork-id]))}
          (:fork/name fork)]))]))


(defn history-sidebar
  []
  [:div {:style {:width "250px"
                 :min-width "250px"
                 :height "100vh"
                 :background "#1a1a2a"
                 :border-left "1px solid #444"
                 :padding "12px"
                 :display "flex"
                 :flex-direction "column"
                 :font-family "monospace"
                 :color "#eee"
                 :overflow "hidden"}}
   [:div {:style {:color "#999"
                  :font-size "11px"
                  :text-transform "uppercase"
                  :letter-spacing "1px"
                  :margin-bottom "8px"}}
    "History"]
   [step-controls]
   [action-log]
   [fork-list]])
