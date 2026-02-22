(ns fizzle.cards.iggy-pop
  "Iggy Pop decklist definition.

   Decklist uses :card/id keywords only — no var references to card definitions.
   Card definitions live in per-card files under cards/ subdirectories.")


;; Iggy Pop decklist - structured constant for setup screen.
;; Main deck matches the current 60-card configuration.
;; Sideboard uses only implemented cards (updated as more cards are added).
(def iggy-pop-decklist
  {:deck/id :iggy-pop
   :deck/name "Iggy Pop"
   :deck/main [{:card/id :dark-ritual :count 4}
               {:card/id :cabal-ritual :count 4}
               {:card/id :brain-freeze :count 4}
               {:card/id :city-of-brass :count 3}
               {:card/id :gemstone-mine :count 4}
               {:card/id :polluted-delta :count 3}
               {:card/id :underground-river :count 1}
               {:card/id :cephalid-coliseum :count 2}
               {:card/id :island :count 2}
               {:card/id :swamp :count 1}
               {:card/id :lotus-petal :count 4}
               {:card/id :lions-eye-diamond :count 4}
               {:card/id :careful-study :count 2}
               {:card/id :mental-note :count 3}
               {:card/id :opt :count 4}
               {:card/id :intuition :count 4}
               {:card/id :deep-analysis :count 3}
               {:card/id :recoup :count 1}
               {:card/id :ill-gotten-gains :count 4}
               {:card/id :orims-chant :count 1}
               {:card/id :ray-of-revelation :count 1}
               {:card/id :flash-of-insight :count 1}]
   :deck/side [{:card/id :merchant-scroll :count 2}
               {:card/id :seal-of-cleansing :count 2}
               {:card/id :city-of-traitors :count 2}
               {:card/id :careful-study :count 2}
               {:card/id :deep-analysis :count 1}
               {:card/id :mental-note :count 1}
               {:card/id :opt :count 1}
               {:card/id :orims-chant :count 1}
               {:card/id :flash-of-insight :count 1}
               {:card/id :ray-of-revelation :count 1}
               {:card/id :cephalid-coliseum :count 1}]})
