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
         (for [[card-idx obj] (map-indexed vector cards)]
           ^{:key (:object/id obj)}
           [common/selection-card-view obj
            (contains? selected (:object/id obj))
            ::selection-events/toggle-selection
            true
            nil
            (inc card-idx)])
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
         (for [[card-idx obj] (map-indexed vector cards)]
           ^{:key (:object/id obj)}
           [common/selection-card-view obj
            (contains? selected (:object/id obj))
            ::selection-events/toggle-selection
            true
            attacker-toughness
            (inc card-idx)])
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
      (for [[badge-num [id label]] (map-indexed (fn [idx pair] [(inc idx) pair])
                                                [[game-state/human-player-id "You"] [game-state/opponent-player-id "Opponent"]])
            :when (show? id)]
        ^{:key id}
        [:div {:class "relative"}
         [:button {:class (str "py-4 px-8 m-2 rounded-lg cursor-pointer text-text text-base "
                               "font-bold min-w-[120px] transition-all duration-100 "
                               (if (contains? selected id)
                                 "border-[3px] border-border-accent bg-modal-selected-bg"
                                 "border-2 border-border bg-surface-raised"))
                   :on-click #(rf/dispatch [::selection-events/toggle-selection id])}
          label]
         [:span {:class "absolute top-1 right-1 text-xs font-mono bg-surface-raised border border-border rounded px-1 py-0.5"}
          (str "[" badge-num "]")]])]
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
         (for [[card-idx obj] (map-indexed vector cards)]
           ^{:key (:object/id obj)}
           [common/selection-card-view obj
            (contains? selected-set (:object/id obj))
            select-event
            true
            nil
            (inc card-idx)])
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
         (for [[item-idx item] (map-indexed vector targets)]
           ^{:key (:db/id item)}
           [:div {:class (str "cursor-pointer rounded px-3 py-2 text-sm border relative "
                              (if (contains? selected (:db/id item))
                                "border-health-good bg-health-good/20 text-text"
                                "border-perm-border bg-surface text-text hover:border-text-muted"))
                  :on-click #(rf/dispatch [::selection-events/toggle-selection (:db/id item)])}
            [:span {:class "absolute top-1 right-1 text-xs font-mono bg-surface-raised border border-border rounded px-1 py-0.5"}
             (str "[" (inc item-idx) "]")]
            (or (:stack-item/description item)
                (str (name (or (:stack-item/type item) :unknown)) " ability"))])
         [:div {:class "text-perm-text-tapped"} "No valid targets"])]
      [:div {:class "flex justify-end"}
       [common/confirm-button {:label "Confirm" :valid? valid?
                               :on-confirm #(rf/dispatch [::selection-events/confirm-selection])}]]]]))


;; === Any Target (player or battlefield creature) ===

(defn any-target-modal
  "Modal for :target/type :any — shows both players as buttons and battlefield
   creatures as card tiles. Valid-targets contains player keywords and creature UUIDs.
   Uses flat numbering: players [1]/[2], creatures [3]+ in keyboard order."
  [selection cards confirm-event]
  (let [selected (or (:selection/selected selection) #{})
        valid? @(rf/subscribe [::subs/selection-valid?])
        vt (set (:selection/valid-targets selection))
        show-player? (fn [id] (contains? vt id))
        ;; Build list of visible players to count them
        visible-players (filter (fn [[id _]] (show-player? id))
                                [[game-state/human-player-id "You"] [game-state/opponent-player-id "Opponent"]])
        player-count (count visible-players)
        creature-start-num (+ player-count 1)]
    [common/modal-wrapper {:title "Choose any target" :max-width "500px"}
     [:p {:class (str "m-0 mb-3 text-sm "
                      (if valid? "text-health-good" "text-health-danger"))}
      (if valid? "Target selected" "Select a player or creature")]
     ;; Players row
     [:div {:class "flex justify-center gap-3 mb-4"}
      (for [[badge-num [id label]] (map-indexed (fn [idx pair] [(inc idx) pair]) visible-players)]
        ^{:key id}
        [:div {:class "relative"}
         [:button {:class (str "py-3 px-6 rounded-lg cursor-pointer text-text text-sm "
                               "font-bold min-w-[100px] transition-all duration-100 "
                               (if (contains? selected id)
                                 "border-[3px] border-border-accent bg-modal-selected-bg"
                                 "border-2 border-border bg-surface-raised"))
                   :on-click #(rf/dispatch [::selection-events/toggle-selection id])}
          label]
         [:span {:class "absolute top-1 right-1 text-xs font-mono bg-surface-raised border border-border rounded px-1 py-0.5"}
          (str "[" badge-num "]")]])]
     ;; Creature objects (battlefield)
     (when (seq cards)
       [:div {:class "flex flex-wrap gap-2.5 mb-4 min-h-[60px]"}
        (for [[card-idx obj] (map-indexed vector cards)]
          ^{:key (:object/id obj)}
          [common/selection-card-view obj
           (contains? selected (:object/id obj))
           ::selection-events/toggle-selection
           true
           nil
           (+ creature-start-num card-idx)])])
     [:div {:class "flex justify-center"}
      [common/confirm-button {:label "Confirm" :valid? valid?
                              :on-confirm #(rf/dispatch [confirm-event])
                              :extra-class "py-2.5 px-8"}]]]))
