(ns fizzle.views.history
  (:require
    [fizzle.history.events :as events]
    [fizzle.subs.history :as subs]
    [re-frame.core :as rf]
    [reagent.core :as r]))


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


(defn action-log
  "Turn-grouped action log with collapsible sections and click-to-rewind.
   Layout-agnostic — renders as a plain div, no width/position assumptions."
  []
  (let [collapsed (r/atom #{})]
    (fn []
      (let [groups @(rf/subscribe [::subs/entries-by-turn])
            position @(rf/subscribe [::subs/position])]
        (if (seq groups)
          (let [indexed-groups (second
                                 (reduce (fn [[offset acc] group]
                                           (let [n (count (:entries group))]
                                             [(+ offset n)
                                              (conj acc (assoc group :offset offset))]))
                                         [0 []]
                                         groups))]
            [:div
             (for [{:keys [turn entries offset]} indexed-groups]
               ^{:key (str "turn-" turn)}
               [:div
                [:div {:style {:padding "4px 8px"
                               :font-size "11px"
                               :color "#999"
                               :cursor "pointer"
                               :user-select "none"
                               :text-transform "uppercase"
                               :letter-spacing "0.5px"}
                       :on-click #(swap! collapsed
                                         (fn [s]
                                           (if (contains? s turn)
                                             (disj s turn)
                                             (conj s turn))))}
                 (str (if (contains? @collapsed turn) "\u25B6 " "\u25BC ")
                      "Turn " turn)]
                (when-not (contains? @collapsed turn)
                  (for [[i entry] (map-indexed vector entries)]
                    (let [abs-idx (+ offset i)]
                      ^{:key abs-idx}
                      [:div {:style {:padding "4px 8px"
                                     :padding-left "16px"
                                     :font-size "12px"
                                     :cursor "pointer"
                                     :color (cond
                                              (= abs-idx position) "#eee"
                                              (> abs-idx position) "#666"
                                              :else "#eee")
                                     :opacity (if (> abs-idx position) 0.4 1)
                                     :border-left (if (= abs-idx position)
                                                    "3px solid #4A9BD9"
                                                    "3px solid transparent")
                                     :background (if (= abs-idx position)
                                                   "#1a2a3a"
                                                   "transparent")}
                             :on-click #(rf/dispatch [::events/jump-to abs-idx])}
                       (:entry/description entry)])))])])
          [:div {:style {:color "#555" :font-size "12px" :padding "4px 8px"}}
           "No actions yet"])))))


(defn fork-controls
  "Fork CRUD controls: create, inline rename, delete.
   Layout-agnostic — renders as a plain div."
  []
  (let [editing (r/atom nil)]
    (fn []
      (let [forks @(rf/subscribe [::subs/forks])
            current-branch @(rf/subscribe [::subs/current-branch])]
        [:div
         [:div {:style {:margin-bottom "8px"}}
          [:button {:style {:padding "4px 8px"
                            :font-size "12px"
                            :border "1px solid #555"
                            :border-radius "4px"
                            :background "#2a2a3a"
                            :color "#ccc"
                            :cursor "pointer"}
                    :on-click #(rf/dispatch
                                 [::events/create-fork
                                  (str "Fork " (inc (count forks)))])}
           "+ New Fork"]]
         [:div {:style {:font-size "12px"
                        :padding "2px 8px"
                        :color (if (nil? current-branch) "#4A9BD9" "#ccc")
                        :font-weight (if (nil? current-branch) "bold" "normal")}}
          "main"]
         (for [fork forks]
           (let [fork-id (:fork/id fork)
                 active? (= fork-id current-branch)
                 editing? (= fork-id (:fork-id @editing))]
             ^{:key fork-id}
             [:div {:style {:display "flex"
                            :align-items "center"
                            :gap "4px"
                            :padding "2px 8px"
                            :font-size "12px"}}
              (if editing?
                [:input {:style {:background "#1a1a2a"
                                 :border "1px solid #4A9BD9"
                                 :border-radius "2px"
                                 :color "#eee"
                                 :font-size "12px"
                                 :padding "1px 4px"
                                 :flex 1}
                         :default-value (:value @editing)
                         :auto-focus true
                         :on-key-down (fn [e]
                                        (case (.-key e)
                                          "Enter" (do
                                                    (rf/dispatch
                                                      [::events/rename-fork
                                                       fork-id
                                                       (.. e -target -value)])
                                                    (reset! editing nil))
                                          "Escape" (reset! editing nil)
                                          nil))
                         :on-blur (fn [e]
                                    (rf/dispatch
                                      [::events/rename-fork
                                       fork-id
                                       (.. e -target -value)])
                                    (reset! editing nil))}]
                [:span {:style {:flex 1
                                :cursor "default"
                                :color (if active? "#4A9BD9" "#ccc")
                                :font-weight (if active? "bold" "normal")}
                        :on-double-click #(reset! editing
                                                  {:fork-id fork-id
                                                   :value (:fork/name fork)})}
                 (:fork/name fork)])
              [:span {:style {:cursor "pointer"
                              :color "#666"
                              :font-size "11px"}
                      :on-click #(rf/dispatch [::events/delete-fork fork-id])}
               "\u2715"]]))]))))


(defn- render-tree-node
  [node current-branch depth]
  [:div
   [:div {:style {:padding "2px 8px"
                  :padding-left (str (+ 8 (* depth 16)) "px")
                  :font-size "12px"
                  :cursor (if (= (:fork/id node) current-branch)
                            "default"
                            "pointer")
                  :color (if (= (:fork/id node) current-branch)
                           "#4A9BD9"
                           "#ccc")
                  :font-weight (if (= (:fork/id node) current-branch)
                                 "bold"
                                 "normal")}
          :on-click (when-not (= (:fork/id node) current-branch)
                      #(rf/dispatch [::events/switch-branch (:fork/id node)]))}
    (:fork/name node)]
   (for [child (:children node)]
     ^{:key (:fork/id child)}
     [render-tree-node child current-branch (inc depth)])])


(defn fork-tree
  "Hierarchical fork tree with indentation and branch switching.
   Layout-agnostic — renders as a plain div."
  []
  (let [tree @(rf/subscribe [::subs/fork-tree])
        current-branch @(rf/subscribe [::subs/current-branch])]
    [:div
     [:div {:style {:padding "2px 8px"
                    :font-size "12px"
                    :cursor (if (nil? current-branch) "default" "pointer")
                    :color (if (nil? current-branch) "#4A9BD9" "#ccc")
                    :font-weight (if (nil? current-branch) "bold" "normal")}
            :on-click (when (some? current-branch)
                        #(rf/dispatch [::events/switch-branch nil]))}
      "main"]
     (for [node tree]
       ^{:key (:fork/id node)}
       [render-tree-node node current-branch 1])]))


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
