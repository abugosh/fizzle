(ns fizzle.views.selection.custom
  "Custom modal components for genuinely unique selection types."
  (:require
    [fizzle.db.game-state :as game-state]
    [fizzle.events.selection :as selection-events]
    [fizzle.subs.game :as subs]
    [fizzle.views.selection.common :as common]
    [re-frame.core :as rf]))


;; === Combat Modals ===

(defn attacker-selection-modal
  [selection cards]
  (let [selected (or (:selection/selected selection) #{})
        valid? @(rf/subscribe [::subs/selection-valid?])]
    [:div {:class common/overlay-class}
     [:div {:class (common/container-class {})}
      [:h2 {:class "text-text m-0 mb-2 text-lg"} "Declare Attackers"]
      [:p {:class (str "m-0 mb-4 text-sm "
                       (if valid? "text-health-good" "text-health-danger"))}
       (if valid? "Attackers selected" "Select creatures to attack with")]
      [:div {:class "flex flex-wrap gap-2.5 mb-5 min-h-[60px]"}
       (if (seq cards)
         (for [obj cards]
           ^{:key (:object/id obj)}
           [common/selection-card-view obj
            (contains? selected (:object/id obj))
            ::selection-events/toggle-selection])
         [:div {:class "text-perm-text-tapped"} "No eligible attackers"])]
      [:div {:class "flex justify-end"}
       [common/confirm-button {:label "Confirm" :valid? valid?
                               :on-confirm #(rf/dispatch [::selection-events/confirm-selection])}]]]]))


(defn blocker-selection-modal
  [selection cards]
  (let [selected (or (:selection/selected selection) #{})
        valid? @(rf/subscribe [::subs/selection-valid?])
        attacker-display @(rf/subscribe [::subs/blocker-attacker-display])
        attacker-toughness (:effective-toughness attacker-display)
        attacker-name (:card-name attacker-display)]
    [:div {:class common/overlay-class}
     [:div {:class (common/container-class {})}
      [:h2 {:class "text-text m-0 mb-2 text-lg"}
       (str "Assign Blockers" (when attacker-name (str " for " attacker-name)))]
      (when attacker-display
        [:p {:class "m-0 mb-2 text-sm text-text-dim"}
         (str "Attacker: "
              (:effective-power attacker-display) "/"
              attacker-toughness)])
      [:p {:class (str "m-0 mb-4 text-sm "
                       (if valid? "text-health-good" "text-health-danger"))}
       (if valid? "Blockers assigned" "Select blockers (or confirm with none)")]
      [:div {:class "flex flex-wrap gap-2.5 mb-5 min-h-[60px]"}
       (if (seq cards)
         (for [obj cards]
           ^{:key (:object/id obj)}
           [common/selection-card-view obj
            (contains? selected (:object/id obj))
            ::selection-events/toggle-selection
            true
            attacker-toughness])
         [:div {:class "text-perm-text-tapped"} "No eligible blockers"])]
      [:div {:class "flex justify-end"}
       [common/confirm-button {:label "Confirm" :valid? valid?
                               :on-confirm #(rf/dispatch [::selection-events/confirm-selection])}]]]]))


;; === Player Target ===

(defn player-target-modal
  [selection confirm-event]
  (let [selected (or (:selection/selected selection) #{})
        valid? @(rf/subscribe [::subs/selection-valid?])
        vt (some-> (:selection/valid-targets selection) set)
        show? (fn [id] (or (nil? vt) (contains? vt id)))]
    [common/modal-wrapper {:title "Choose target player" :max-width "400px" :text-align "center"}
     [:div {:class "flex justify-center mb-5"}
      (for [[id label] [[game-state/human-player-id "You"] [game-state/opponent-player-id "Opponent"]]
            :when (show? id)]
        ^{:key id}
        [:button {:class (str "py-4 px-8 m-2 rounded-lg cursor-pointer text-text text-base "
                              "font-bold min-w-[120px] transition-all duration-100 "
                              (if (contains? selected id)
                                "border-[3px] border-border-accent bg-modal-selected-bg"
                                "border-2 border-border bg-surface-raised"))
                  :on-click #(rf/dispatch [::selection-events/toggle-selection id])}
         label])]
     [:div {:class "flex justify-center"}
      [common/confirm-button {:label "Confirm" :valid? valid?
                              :on-confirm #(rf/dispatch [confirm-event])
                              :extra-class "py-2.5 px-8"}]]]))


;; === Object Target ===

(defn object-target-modal
  [selection cards {:keys [select-event confirm-event default-zone
                           selected-label unselected-label]}]
  (let [selected (or (:selection/selected selection) #{})
        ;; Use set for O(1) membership — works for both set and vector :selected.
        ;; (contains? vector id) checks by index, not value; (contains? set id) is correct.
        selected-set (set selected)
        ;; Read zone from :selection/target-requirements (plural, fizzle-29gc) or
        ;; legacy :selection/target-requirement (singular) for older selection types.
        zone-name (name (or (get-in selection [:selection/target-requirements 0 :target/zone])
                            (get-in selection [:selection/target-requirement :target/zone])
                            default-zone))
        valid? @(rf/subscribe [::subs/selection-valid?])]
    [:div {:class common/overlay-class}
     [:div {:class (common/container-class {})}
      [:h2 {:class "text-text m-0 mb-2 text-lg"} (str "Choose target from " zone-name)]
      [:p {:class (str "m-0 mb-4 text-sm "
                       (if valid? "text-health-good" "text-health-danger"))}
       (if valid? selected-label unselected-label)]
      [:div {:class "flex flex-wrap gap-2.5 mb-5 min-h-[60px]"}
       (if (seq cards)
         (for [obj cards]
           ^{:key (:object/id obj)}
           [common/selection-card-view obj
            (contains? selected-set (:object/id obj))
            select-event])
         [:div {:class "text-perm-text-tapped"} "No valid targets"])]
      [:div {:class "flex justify-end"}
       [common/confirm-button {:label "Confirm" :valid? valid?
                               :on-confirm #(rf/dispatch [confirm-event])}]]]]))


;; === Ability Cast Targeting (stack items, not cards) ===

(defn ability-cast-targeting-modal
  [selection]
  (let [targets @(rf/subscribe [::subs/ability-cast-targets])
        selected (or (:selection/selected selection) #{})
        valid? @(rf/subscribe [::subs/selection-valid?])]
    [:div {:class common/overlay-class}
     [:div {:class (common/container-class {})}
      [:h2 {:class "text-text m-0 mb-2 text-lg"} "Counter target ability"]
      [:p {:class (str "m-0 mb-4 text-sm "
                       (if valid? "text-health-good" "text-health-danger"))}
       (if valid? "1 target selected" "Select an ability to counter")]
      [:div {:class "flex flex-col gap-2.5 mb-5 min-h-[60px]"}
       (if (seq targets)
         (for [item targets]
           ^{:key (:db/id item)}
           [:div {:class (str "cursor-pointer rounded px-3 py-2 text-sm border "
                              (if (contains? selected (:db/id item))
                                "border-health-good bg-health-good/20 text-text"
                                "border-perm-border bg-surface text-text hover:border-text-muted"))
                  :on-click #(rf/dispatch [::selection-events/toggle-selection (:db/id item)])}
            (or (:stack-item/description item)
                (str (name (or (:stack-item/type item) :unknown)) " ability"))])
         [:div {:class "text-perm-text-tapped"} "No valid targets"])]
      [:div {:class "flex justify-end"}
       [common/confirm-button {:label "Confirm" :valid? valid?
                               :on-confirm #(rf/dispatch [::selection-events/confirm-selection])}]]]]))


;; === Any Target (player or battlefield creature) ===

(defn any-target-modal
  "Modal for :target/type :any — shows both players as buttons and battlefield
   creatures as card tiles. Valid-targets contains player keywords and creature UUIDs."
  [selection cards confirm-event]
  (let [selected (or (:selection/selected selection) #{})
        valid? @(rf/subscribe [::subs/selection-valid?])
        vt (set (:selection/valid-targets selection))
        show-player? (fn [id] (contains? vt id))]
    [common/modal-wrapper {:title "Choose any target" :max-width "500px"}
     [:p {:class (str "m-0 mb-3 text-sm "
                      (if valid? "text-health-good" "text-health-danger"))}
      (if valid? "Target selected" "Select a player or creature")]
     ;; Players row
     [:div {:class "flex justify-center gap-3 mb-4"}
      (for [[id label] [[game-state/human-player-id "You"] [game-state/opponent-player-id "Opponent"]]
            :when (show-player? id)]
        ^{:key id}
        [:button {:class (str "py-3 px-6 rounded-lg cursor-pointer text-text text-sm "
                              "font-bold min-w-[100px] transition-all duration-100 "
                              (if (contains? selected id)
                                "border-[3px] border-border-accent bg-modal-selected-bg"
                                "border-2 border-border bg-surface-raised"))
                  :on-click #(rf/dispatch [::selection-events/toggle-selection id])}
         label])]
     ;; Creature objects (battlefield)
     (when (seq cards)
       [:div {:class "flex flex-wrap gap-2.5 mb-4 min-h-[60px]"}
        (for [obj cards]
          ^{:key (:object/id obj)}
          [common/selection-card-view obj
           (contains? selected (:object/id obj))
           ::selection-events/toggle-selection])])
     [:div {:class "flex justify-center"}
      [common/confirm-button {:label "Confirm" :valid? valid?
                              :on-confirm #(rf/dispatch [confirm-event])
                              :extra-class "py-2.5 px-8"}]]]))


;; === Mode Selector / Spell Mode ===

(def ^:private mode-btn-class
  (str "w-full py-3 px-4 mb-2 border-2 border-border-accent rounded-lg "
       "cursor-pointer bg-mode-btn-bg text-text text-left "
       "transition-all duration-100 hover:bg-mode-btn-hover"))


(def ^:private dismiss-btn-class
  (str "w-full py-2 px-4 mt-2 border border-border rounded "
       "cursor-pointer bg-surface-dim text-text-label text-[13px]"))


;; === Land Type Selection ===

(def ^:private land-type-display-names
  "Human-readable display names for the 5 basic land types."
  {:plains   "Plains"
   :island   "Island"
   :swamp    "Swamp"
   :mountain "Mountain"
   :forest   "Forest"})


(defn land-type-selection-modal
  "Modal for :land-type-source and :land-type-target selection.
   Displays basic land type options as clickable buttons.
   Source selection: choose one of 5 types. Target: choose one of 4 (source excluded)."
  [selection]
  (let [selected (or (:selection/selected selection) #{})
        options (:selection/options selection)
        valid? @(rf/subscribe [::subs/selection-valid?])
        title (if (= :land-type-source (:selection/domain selection))
                "Choose source land type"
                (str "Change " (land-type-display-names (:selection/source-type selection) "land")
                     "s to..."))]
    [common/modal-wrapper {:title title :max-width "400px" :text-align "center"}
     [:div {:class "flex flex-wrap justify-center gap-2 mb-5"}
      (for [land-type options]
        ^{:key land-type}
        [:button {:class (str "py-3 px-5 rounded-lg cursor-pointer text-text text-sm "
                              "font-bold min-w-[100px] transition-all duration-100 "
                              (if (contains? selected land-type)
                                "border-[3px] border-border-accent bg-modal-selected-bg"
                                "border-2 border-border bg-surface-raised"))
                  :on-click #(rf/dispatch [::selection-events/toggle-selection land-type])}
         (land-type-display-names land-type (name land-type))])]
     [:div {:class "flex justify-center"}
      [common/confirm-button {:label "Confirm" :valid? valid?
                              :on-confirm #(rf/dispatch [::selection-events/confirm-selection])
                              :extra-class "py-2.5 px-8"}]]]))


(defn spell-mode-selection-modal
  [selection _cards]
  [:div {:class common/overlay-class
         :on-click #(rf/dispatch [::selection-events/cancel-selection])}
   [:div {:class (common/container-class {:max-width "400px"})
          :on-click #(.stopPropagation %)}
    [:h2 {:class "text-text m-0 mb-4 text-lg text-center"} "Choose one"]
    [:div {:class "flex flex-col"}
     (for [mode (:selection/candidates selection)]
       ^{:key (:mode/label mode)}
       [:button {:class mode-btn-class
                 :on-click #(rf/dispatch [::selection-events/toggle-selection mode])}
        [:div {:class "font-bold text-sm"} (:mode/label mode)]])]
    [:button {:class dismiss-btn-class
              :on-click #(rf/dispatch [::selection-events/cancel-selection])}
     "Cancel"]]])
