(ns fizzle.views.selection.custom
  "Custom modal components for genuinely unique selection types."
  (:require
    [clojure.string :as str]
    [fizzle.events.game :as events]
    [fizzle.events.selection :as selection-events]
    [fizzle.subs.game :as subs]
    [fizzle.views.card-styles :as card-styles]
    [fizzle.views.selection.common :as common]
    [re-frame.core :as rf]))


;; === Player Target ===

(defn player-target-modal
  "Modal for selecting a player as target."
  [selection confirm-event]
  (let [selected (or (:selection/selected selection) #{})
        valid? @(rf/subscribe [::subs/selection-valid?])
        vt (some-> (:selection/valid-targets selection) set)
        show? (fn [id] (or (nil? vt) (contains? vt id)))]
    [common/modal-wrapper {:title "Choose target player" :max-width "400px" :text-align "center"}
     [:div {:class "flex justify-center mb-5"}
      (for [[id label] [[:player-1 "You"] [:opponent "Opponent"]]
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
  "Modal for selecting an object as target."
  [selection cards {:keys [select-event confirm-event default-zone
                           selected-label unselected-label]}]
  (let [selected (or (:selection/selected selection) #{})
        zone-name (name (or (get-in selection [:selection/target-requirement :target/zone]) default-zone))
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
           (let [oid (:object/id obj)
                 card-types (get-in obj [:object/card :card/types])
                 card-colors (get-in obj [:object/card :card/colors])]
             ^{:key oid}
             [:div {:class (str "rounded-md px-3.5 py-2.5 cursor-pointer min-w-[90px] text-center "
                                "select-none text-text transition-all duration-100 "
                                (if (contains? selected oid)
                                  "border-[3px] border-border-accent"
                                  (str "border-2 " (card-styles/get-type-border-class card-types false)))
                                " " (card-styles/get-color-identity-bg-class card-colors card-types))
                    :on-click #(rf/dispatch [select-event oid])}
              (get-in obj [:object/card :card/name])]))
         [:div {:class "text-perm-text-tapped"} "No valid targets"])]
      [:div {:class "flex justify-end"}
       [common/confirm-button {:label "Confirm" :valid? valid?
                               :on-confirm #(rf/dispatch [confirm-event])}]]]]))


;; === Ability Cast Targeting ===

(defn ability-cast-targeting-modal
  "Modal for targeting an ability (Stifle)."
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


;; === Mode Selector (Pre-cast) ===

(def ^:private mode-btn-class
  (str "w-full py-3 px-4 mb-2 border-2 border-border-accent rounded-lg "
       "cursor-pointer bg-mode-btn-bg text-text text-left "
       "transition-all duration-100 hover:bg-mode-btn-hover"))


(def ^:private dismiss-btn-class
  (str "w-full py-2 px-4 mt-2 border border-border rounded "
       "cursor-pointer bg-surface-dim text-text-label text-[13px]"))


(defn- format-mana-cost
  [mc]
  (if (or (nil? mc) (empty? mc))
    "{0}"
    (let [syms [[:colorless nil] [:white "W"] [:blue "U"] [:black "B"] [:red "R"] [:green "G"]]]
      (apply str (for [[k s] syms
                       :let [n (get mc k 0)]
                       :when (pos? n)]
                   (if s (apply str (repeat n (str "{" s "}")))
                       (str "{" n "}")))))))


(defn- format-additional-costs
  [costs]
  (when (seq costs)
    (->> costs
         (map (fn [c]
                (case (:cost/type c)
                  :pay-life (str "Pay " (:cost/amount c) " life")
                  :return-lands (str "Return " (:cost/count c) " " (name (:cost/subtype c)) "s")
                  :sacrifice (str "Sacrifice " (:cost/count c) " " (name (:cost/subtype c)))
                  "Additional cost")))
         (str/join ", "))))


(defn mode-selector-modal
  "Modal for selecting casting mode (Normal Cast / Flashback)."
  []
  (let [pending @(rf/subscribe [::subs/pending-mode-selection])]
    (when pending
      [:div {:class common/overlay-class
             :on-click #(rf/dispatch [::events/cancel-mode-selection])}
       [:div {:class (common/container-class {:max-width "400px"})
              :on-click #(.stopPropagation %)}
        [:h2 {:class "text-text m-0 mb-4 text-lg text-center"} "Choose casting mode"]
        [:div {:class "flex flex-col"}
         (for [mode (:modes pending)
               :let [fmana (format-mana-cost (:mode/mana-cost mode))
                     fadd (format-additional-costs (:mode/additional-costs mode))]]
           ^{:key (:mode/id mode)}
           [:button {:class mode-btn-class
                     :on-click #(rf/dispatch [::events/select-casting-mode mode])}
            [:div {:class "font-bold text-sm mb-1"}
             (case (:mode/id mode) :primary "Normal Cast" :flashback "Flashback"
                   (name (:mode/id mode)))]
            [:div {:class "text-[13px] text-text-muted"}
             fmana
             (when fadd [:span {:class "ml-2 text-cost-text"} (str "+ " fadd)])]])]
        [:button {:class dismiss-btn-class
                  :on-click #(rf/dispatch [::events/cancel-mode-selection])}
         "Cancel"]]])))


;; === Spell Mode Selection ===

(defn spell-mode-selection-modal
  "Modal for spell-mode selection (REB, BEB). Auto-confirms on click."
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
