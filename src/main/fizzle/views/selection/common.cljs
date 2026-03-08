(ns fizzle.views.selection.common
  "Shared helpers for selection modal components."
  (:require
    [fizzle.views.card-styles :as card-styles]
    [re-frame.core :as rf]))


(def overlay-class
  "fixed inset-0 bg-black/80 flex items-center justify-center z-[1000]")


(defn container-class
  [{:keys [max-width border-color]
    :or {max-width "600px" border-color "border-accent"}}]
  (str "bg-surface-raised border-2 border-" border-color
       " rounded-xl p-6 w-[90%] max-w-[" max-width "]"))


(def header-class "text-text text-lg m-0 mb-4")


(defn confirm-button
  [{:keys [label valid? on-confirm extra-class]}]
  [:button {:class (str "py-2 px-5 border-none rounded font-bold text-sm "
                        (if valid?
                          "cursor-pointer bg-btn-enabled-bg text-white"
                          "cursor-not-allowed bg-surface-dim text-perm-text-tapped")
                        (when extra-class (str " " extra-class)))
            :disabled (not valid?)
            :on-click on-confirm}
   label])


(defn cancel-button
  [{:keys [label on-cancel]}]
  [:button {:class "py-2 px-5 border border-border rounded cursor-pointer bg-surface-dim text-perm-text text-sm"
            :on-click on-cancel}
   label])


(defn modal-wrapper
  [{:keys [title max-width border-color text-align]} & children]
  [:div {:class overlay-class}
   (into [:div {:class (str (container-class {:max-width max-width
                                              :border-color border-color})
                            (when text-align (str " text-" text-align)))}
          [:h2 {:class header-class} title]]
         children)])


(defn- pt-display
  "Inline P/T display for a creature object in a selection card.
   Shows 'P/T' with power and toughness values.
   Also shows combat math preview (power vs opposing toughness) when context is provided."
  [creature-display opposing-toughness]
  (let [p (:effective-power creature-display)
        t (:effective-toughness creature-display)]
    [:div {:class "text-[12px] font-bold mt-1"}
     (str p "/" t)
     (when (and opposing-toughness (some? p))
       [:span {:class (str " text-[11px] ml-1 "
                           (if (>= p opposing-toughness)
                             "text-green-400"
                             "text-red-400"))}
        (str "(" p " vs " opposing-toughness ")")])]))


(defn selection-card-view
  "A card in a selection modal. Supports optional selectable? param for
   hand-reveal style (dimmed non-selectable cards).
   When obj has :creature/display, shows effective P/T below card name.
   When opposing-toughness is provided, shows combat math preview."
  ([obj selected? toggle-event]
   (selection-card-view obj selected? toggle-event true nil))
  ([obj selected? toggle-event selectable?]
   (selection-card-view obj selected? toggle-event selectable? nil))
  ([obj selected? toggle-event selectable? opposing-toughness]
   (let [card-name (get-in obj [:object/card :card/name])
         object-id (:object/id obj)
         card-types (get-in obj [:object/card :card/types])
         card-colors (get-in obj [:object/card :card/colors])
         creature-display (:creature/display obj)
         border-class (cond
                        selected? "border-[3px] border-border-accent"
                        (not selectable?) "border-2 border-border opacity-50"
                        :else (str "border-2 " (card-styles/get-type-border-class card-types false)))
         bg-class (card-styles/get-color-identity-bg-class card-colors card-types)]
     [:div {:class (str "rounded-md px-3.5 py-2.5 min-w-[90px] text-center "
                        "select-none text-text transition-all duration-100 "
                        (if selectable? "cursor-pointer " "cursor-not-allowed ")
                        border-class " " bg-class)
            :on-click (when selectable?
                        #(rf/dispatch [toggle-event object-id]))}
      card-name
      (when creature-display
        [pt-display creature-display opposing-toughness])])))


(defn stepper-button-class
  [enabled?]
  (str "w-10 h-10 border border-border rounded cursor-"
       (if enabled? "pointer" "not-allowed")
       " text-white text-xl "
       (if enabled? "bg-surface-elevated" "bg-btn-disabled-bg")))
