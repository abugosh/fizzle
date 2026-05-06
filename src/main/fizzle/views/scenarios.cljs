(ns fizzle.views.scenarios
  "Scenario builder and library screens."
  (:require
    [fizzle.events.scenario :as scenario-events]
    [fizzle.subs.scenario :as subs-scenario]
    [re-frame.core :as rf]))


(defn library-view
  "Display saved scenarios or a prompt to create one."
  []
  (let [scenarios @(rf/subscribe [::subs-scenario/all-scenarios])]
    [:div {:class "p-4"}
     (if (empty? scenarios)
       [:div
        [:p {:class "text-text-muted mb-4"} "No scenarios yet"]
        [:button {:class "px-4 py-2 bg-accent text-surface rounded font-bold cursor-pointer"
                  :on-click #(rf/dispatch [::scenario-events/show-builder])}
         "New Scenario"]]
       [:div
        [:p "Scenarios: " (count scenarios)]
        [:button {:class "px-4 py-2 bg-accent text-surface rounded font-bold cursor-pointer"
                  :on-click #(rf/dispatch [::scenario-events/show-builder])}
         "New Scenario"]])]))


(defn builder-view
  "Scenario builder placeholder."
  []
  [:div {:class "p-4"}
   [:p "Scenario builder (placeholder)"]
   [:button {:class "px-4 py-2 bg-surface-raised text-text rounded cursor-pointer mt-4"
             :on-click #(rf/dispatch [::scenario-events/show-library])}
    "Back to Library"]])


(defn scenarios-screen
  "Main scenarios screen: dispatch to library-view or builder-view based on active-view."
  []
  (let [active-view @(rf/subscribe [::subs-scenario/active-view])]
    [:div {:class "h-full overflow-y-auto"}
     (case active-view
       :builder [builder-view]
       [library-view])]))
