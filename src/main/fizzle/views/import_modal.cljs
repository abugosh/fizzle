(ns fizzle.views.import-modal
  (:require
    [fizzle.events.setup :as setup]
    [fizzle.subs.setup :as setup-subs]
    [fizzle.views.modals :as modals]
    [re-frame.core :as rf]))


(defn- format-help
  "Collapsible format guide for decklist text."
  []
  [:details {:class "text-xs text-text-muted mb-3"}
   [:summary {:class "cursor-pointer select-none hover:text-text"}
    "Format help"]
   [:div {:class "mt-2 p-3 bg-surface-dim border border-border rounded font-mono space-y-2"}
    [:p {:class "text-text-label font-sans font-bold mb-1"} "Accepted formats"]
    [:div
     [:div {:class "text-text-label font-sans mb-0.5"} "Card lines:"]
     [:div "4 Dark Ritual"]
     [:div "4 Dark Ritual (VIS) 72"]
     [:div {:class "text-text-muted font-sans italic"} "Set codes (Moxfield) are stripped automatically"]]
    [:div
     [:div {:class "text-text-label font-sans mb-0.5"} "Sideboard (any of):"]
     [:div "Sideboard"]
     [:div "Sideboard:"]
     [:div "SB: 2 Merchant Scroll"]]
    [:div
     [:div {:class "text-text-label font-sans mb-0.5"} "Ignored:"]
     [:div "// comments"]
     [:div {:class "text-text-muted font-sans italic"} "blank lines"]]
    [:p {:class "text-text-muted font-sans mt-1"}
     "Works with Moxfield and MTGGoldfish text exports. Card names must match implemented cards exactly (case-insensitive)."]]])


(defn import-deck-modal
  "Modal for importing or editing a deck.
   Shows name field, textarea for decklist, error display, and action buttons."
  []
  (let [modal @(rf/subscribe [::setup-subs/import-modal])
        valid? @(rf/subscribe [::setup-subs/import-valid?])
        editing? (some? (:editing-deck-id modal))]
    [modals/modal-wrapper {:title (if editing? "Edit Deck" "Import Deck")
                           :max-width "600px"}
     ;; Deck name field
     [:div {:class "mb-3"}
      [:input {:class "w-full bg-surface-raised border border-border rounded px-2 py-1 text-sm text-text"
               :type "text"
               :placeholder "Deck name..."
               :value (or (:name modal) "")
               :on-change #(rf/dispatch [::setup/set-import-name (.. % -target -value)])}]]
     ;; Textarea for decklist
     [:div {:class "mb-3"}
      [:textarea {:class "h-[300px] w-full bg-surface-dim border border-border rounded p-3 text-sm text-text font-mono resize-none"
                  :placeholder "Paste decklist here...\n\n4 Dark Ritual\n4 Cabal Ritual\n...\n\nSideboard\n2 Merchant Scroll"
                  :value (or (:text modal) "")
                  :on-change #(rf/dispatch [::setup/set-import-text (.. % -target -value)])}]]
     ;; Format help
     [format-help]
     ;; Error display
     (when-let [errors (:errors modal)]
       [:div {:class "border border-error rounded p-3 mt-3 text-sm"}
        [:div {:class "text-error font-bold mb-1"}
         (str "Unrecognized cards (" (count errors) "):")]
        (for [card-name errors]
          ^{:key card-name}
          [:div {:class "text-error"} card-name])])
     ;; Button row
     [:div {:class "flex justify-end gap-3 mt-4"}
      [modals/cancel-button {:label "Cancel"
                             :on-cancel #(rf/dispatch [::setup/close-import-modal])}]
      [modals/confirm-button {:label (if editing? "Save" "Import")
                              :valid? valid?
                              :on-confirm #(rf/dispatch [::setup/confirm-import])}]]]))
