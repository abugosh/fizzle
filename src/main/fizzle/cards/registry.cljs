(ns fizzle.cards.registry
  "Card registry — single source of truth for the complete card pool.

   Every card namespace is required explicitly here. No build-time magic.
   Consumers access cards through engine/cards.cljs, not this namespace directly."
  (:require
    [fizzle.cards.artifacts.lions-eye-diamond :as lions-eye-diamond]
    [fizzle.cards.artifacts.lotus-petal :as lotus-petal]
    [fizzle.cards.black.cabal-ritual :as cabal-ritual]
    [fizzle.cards.black.dark-ritual :as dark-ritual]
    [fizzle.cards.black.ill-gotten-gains :as ill-gotten-gains]
    [fizzle.cards.blue.brain-freeze :as brain-freeze]
    [fizzle.cards.blue.careful-study :as careful-study]
    [fizzle.cards.blue.deep-analysis :as deep-analysis]
    [fizzle.cards.blue.flash-of-insight :as flash-of-insight]
    [fizzle.cards.blue.intuition :as intuition]
    [fizzle.cards.blue.mental-note :as mental-note]
    [fizzle.cards.blue.merchant-scroll :as merchant-scroll]
    [fizzle.cards.blue.opt :as opt]
    [fizzle.cards.lands.basic-lands :as basic-lands]
    [fizzle.cards.lands.cephalid-coliseum :as cephalid-coliseum]
    [fizzle.cards.lands.city-of-brass :as city-of-brass]
    [fizzle.cards.lands.city-of-traitors :as city-of-traitors]
    [fizzle.cards.lands.gemstone-mine :as gemstone-mine]
    [fizzle.cards.lands.pain-lands :as pain-lands]
    [fizzle.cards.lands.polluted-delta :as polluted-delta]
    [fizzle.cards.red.lightning-bolt :as lightning-bolt]
    [fizzle.cards.red.recoup :as recoup]
    [fizzle.cards.white.orims-chant :as orims-chant]
    [fizzle.cards.white.ray-of-revelation :as ray-of-revelation]
    [fizzle.cards.white.seal-of-cleansing :as seal-of-cleansing]))


(def all-cards
  "All card definitions available in the card pool."
  (vec
    (concat
      ;; Individual cards
      [dark-ritual/card
       cabal-ritual/card
       ill-gotten-gains/card
       brain-freeze/card
       careful-study/card
       deep-analysis/card
       flash-of-insight/card
       intuition/card
       mental-note/card
       merchant-scroll/card
       opt/card
       orims-chant/card
       ray-of-revelation/card
       seal-of-cleansing/card
       lightning-bolt/card
       recoup/card
       city-of-brass/card
       city-of-traitors/card
       gemstone-mine/card
       polluted-delta/card
       cephalid-coliseum/card
       lotus-petal/card
       lions-eye-diamond/card]
      ;; Cycle cards
      basic-lands/cards
      pain-lands/cards)))
