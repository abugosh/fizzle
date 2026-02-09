(ns fizzle.views.import-modal
  (:require
    [fizzle.events.setup :as setup]
    [fizzle.subs.setup :as setup-subs]
    [fizzle.views.modals :as modals]
    [re-frame.core :as rf]))


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
