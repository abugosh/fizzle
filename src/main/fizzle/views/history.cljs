(ns fizzle.views.history
  (:require
    [fizzle.history.events :as events]
    [fizzle.snapshot.events :as snapshot]
    [fizzle.subs.game :as game-subs]
    [fizzle.subs.history :as subs]
    [re-frame.core :as rf]
    [reagent.core :as r]))


(defn- step-btn-class
  [enabled?]
  (str "py-1 px-3 border border-border rounded text-[13px] font-bold flex-1 "
       (if enabled?
         "cursor-pointer bg-btn-enabled-bg text-white"
         "cursor-default bg-btn-disabled-bg text-border")))


(defn- step-controls
  []
  (let [can-back? @(rf/subscribe [::subs/can-step-back?])
        can-fwd? @(rf/subscribe [::subs/can-step-forward?])
        can-pop? @(rf/subscribe [::subs/can-pop?])]
    [:div {:class "flex gap-2 mb-3"}
     [:button {:class (step-btn-class can-back?)
               :disabled (not can-back?)
               :on-click #(rf/dispatch [::events/step-back])}
      "\u25C0 Back"]
     [:button {:class (step-btn-class can-pop?)
               :disabled (not can-pop?)
               :on-click #(rf/dispatch [::events/pop-entry])}
      "Undo"]
     [:button {:class (step-btn-class can-fwd?)
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
            position @(rf/subscribe [::subs/position])
            human-pid @(rf/subscribe [::game-subs/human-player-id])
            collapsed-set @collapsed]
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
                [:div {:class "px-2 py-1 text-[11px] text-text-label cursor-pointer select-none uppercase tracking-wider"
                       :on-click #(swap! collapsed
                                         (fn [s]
                                           (if (contains? s turn)
                                             (disj s turn)
                                             (conj s turn))))}
                 (str (if (contains? collapsed-set turn) "\u25B6 " "\u25BC ")
                      "Turn " turn)]
                (when-not (contains? collapsed-set turn)
                  (for [[i entry] (map-indexed vector entries)]
                    (let [abs-idx (+ offset i)]
                      ^{:key abs-idx}
                      [:div {:class (str "px-2 py-1 pl-4 text-xs cursor-pointer "
                                         "border-l-[3px] "
                                         (if (= abs-idx position)
                                           "text-text border-l-accent bg-mode-btn-bg"
                                           (if (> abs-idx position)
                                             "text-perm-text-tapped opacity-40 border-l-transparent"
                                             "text-text border-l-transparent")))
                             :on-click #(rf/dispatch [::events/jump-to abs-idx])}
                       (let [principal (:entry/principal entry)
                             is-opponent (and principal human-pid
                                              (not= principal human-pid))]
                         (str (when is-opponent "Opp: ")
                              (:entry/description entry)))])))])])
          [:div {:class "text-border text-xs px-2 py-1"}
           "No actions yet"])))))


(defn fork-controls
  "Fork CRUD controls: create, inline rename, delete.
   Layout-agnostic — renders as a plain div."
  []
  (let [editing (r/atom nil)]
    (fn []
      (let [forks @(rf/subscribe [::subs/forks])
            current-branch @(rf/subscribe [::subs/current-branch])
            editing-state @editing]
        [:div
         [:div {:class "mb-2"}
          [:button {:class "py-1 px-2 text-xs border border-border rounded bg-surface-hover text-perm-text cursor-pointer"
                    :on-click #(rf/dispatch
                                 [::events/create-fork
                                  (str "Fork " (inc (count forks)))])}
           "+ New Fork"]]
         [:div {:class (str "text-xs px-2 py-0.5 "
                            (if (nil? current-branch)
                              "text-accent font-bold"
                              "text-perm-text"))}
          "main"]
         (for [fork forks]
           (let [fork-id (:fork/id fork)
                 active? (= fork-id current-branch)
                 editing? (= fork-id (:fork-id editing-state))]
             ^{:key fork-id}
             [:div {:class "flex items-center gap-1 px-2 py-0.5 text-xs"}
              (if editing?
                [:input {:class "bg-surface-raised border border-border-accent rounded-sm text-text text-xs px-1 py-px flex-1"
                         :default-value (:value editing-state)
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
                [:span {:class (str "flex-1 cursor-default "
                                    (if active?
                                      "text-accent font-bold"
                                      "text-perm-text"))
                        :on-double-click #(reset! editing
                                                  {:fork-id fork-id
                                                   :value (:fork/name fork)})}
                 (:fork/name fork)])
              [:span {:class "cursor-pointer text-perm-text-tapped text-[11px]"
                      :on-click #(rf/dispatch [::events/delete-fork fork-id])}
               "\u2715"]]))]))))


(defn- render-tree-node
  [node current-branch depth]
  [:div
   ;; Dynamic padding-left based on tree depth — kept as inline style since it's computed
   [:div {:class (str "px-2 py-0.5 text-xs "
                      (if (= (:fork/id node) current-branch)
                        "cursor-default text-accent font-bold"
                        "cursor-pointer text-perm-text"))
          :style {:padding-left (str (+ 8 (* depth 16)) "px")}
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
     [:div {:class (str "px-2 py-0.5 text-xs "
                        (if (nil? current-branch)
                          "cursor-default text-accent font-bold"
                          "cursor-pointer text-perm-text"))
            :on-click (when (some? current-branch)
                        #(rf/dispatch [::events/switch-branch nil]))}
      "main"]
     (for [node tree]
       ^{:key (:fork/id node)}
       [render-tree-node node current-branch 1])]))


(defn fork-list-data
  "Pure rendering logic for the fork/branch list.
   Extracted for testability — takes no args, reads subscriptions."
  []
  (let [forks @(rf/subscribe [::subs/forks])
        current-branch @(rf/subscribe [::subs/current-branch])]
    [:div {:class "border-t border-surface-elevated pt-2 mt-2"}
     [:div {:class "text-text-label text-[11px] uppercase tracking-widest mb-1"}
      "Branches"]
     [:div {:class (str "text-xs px-2 py-0.5 "
                        (if (nil? current-branch)
                          "cursor-default text-accent font-bold"
                          "cursor-pointer text-perm-text"))
            :on-click (when (some? current-branch)
                        #(rf/dispatch [::events/switch-branch nil]))}
      "main"]
     (for [fork forks]
       (let [fork-id (:fork/id fork)
             active? (= fork-id current-branch)]
         ^{:key fork-id}
         [:div {:class (str "text-xs px-2 py-0.5 "
                            (if active?
                              "cursor-default text-accent font-bold"
                              "cursor-pointer text-perm-text"))
                :on-click (when-not active?
                            #(rf/dispatch [::events/switch-branch fork-id]))}
          (:fork/name fork)]))]))


(defn- share-button
  "Share button that copies a snapshot URL to clipboard.
   Shows feedback: 'Copied!' for 2s, or distinct error messages on failure.
   Disabled when the player doesn't have priority or the stack is non-empty."
  []
  (let [status    @(rf/subscribe [::snapshot/share-status])
        can-share? @(rf/subscribe [::game-subs/can-share?])
        disabled? (not can-share?)]
    [:div {:class "border-t border-surface-elevated pt-2 mt-2"}
     [:button
      {:class    (str "py-1 px-2 text-xs border rounded w-full "
                      (cond
                        disabled?
                        "border-border text-text-muted bg-surface cursor-not-allowed opacity-50"
                        (= status :copied)
                        "border-accent text-accent bg-surface-hover cursor-pointer"
                        (#{:error-too-large :error-clipboard} status)
                        "border-red-500 text-red-400 bg-surface-hover cursor-pointer"
                        :else
                        "border-border text-perm-text bg-surface-hover cursor-pointer"))
       :disabled disabled?
       :on-click #(when-not disabled? (rf/dispatch [::snapshot/share-position]))}
      (case status
        :copied           "Copied!"
        :error-too-large  "State too large to share"
        :error-clipboard  "Failed to copy — check clipboard permission"
        "Share")]]))


(defn history-sidebar
  []
  [:div {:class "bg-surface-raised flex flex-col font-mono text-text overflow-hidden"}
   [:div {:class "text-text-label text-[11px] uppercase tracking-widest mb-2"}
    "History"]
   [step-controls]
   [action-log]
   [fork-list-data]
   [share-button]])
