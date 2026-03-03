(ns fizzle.cards.registry
  "Card registry — single source of truth for the complete card pool.

   Every card namespace is required explicitly here. No build-time magic.
   Consumers access cards through engine/cards.cljs, not this namespace directly."
  (:require
    [fizzle.cards.artifacts.defense-grid :as defense-grid]
    [fizzle.cards.artifacts.lions-eye-diamond :as lions-eye-diamond]
    [fizzle.cards.artifacts.lotus-petal :as lotus-petal]
    [fizzle.cards.artifacts.medallions :as medallions]
    [fizzle.cards.artifacts.sphere-of-resistance :as sphere-of-resistance]
    [fizzle.cards.artifacts.tormods-crypt :as tormods-crypt]
    [fizzle.cards.artifacts.urzas-bauble :as urzas-bauble]
    [fizzle.cards.black.cabal-ritual :as cabal-ritual]
    [fizzle.cards.black.dark-ritual :as dark-ritual]
    [fizzle.cards.black.duress :as duress]
    [fizzle.cards.black.ill-gotten-gains :as ill-gotten-gains]
    [fizzle.cards.black.necrologia :as necrologia]
    [fizzle.cards.black.rain-of-filth :as rain-of-filth]
    [fizzle.cards.blue.accumulated-knowledge :as accumulated-knowledge]
    [fizzle.cards.blue.annul :as annul]
    [fizzle.cards.blue.blue-elemental-blast :as blue-elemental-blast]
    [fizzle.cards.blue.brain-freeze :as brain-freeze]
    [fizzle.cards.blue.careful-study :as careful-study]
    [fizzle.cards.blue.chain-of-vapor :as chain-of-vapor]
    [fizzle.cards.blue.chill :as chill]
    [fizzle.cards.blue.counterspell :as counterspell]
    [fizzle.cards.blue.cunning-wish :as cunning-wish]
    [fizzle.cards.blue.daze :as daze]
    [fizzle.cards.blue.deep-analysis :as deep-analysis]
    [fizzle.cards.blue.flash-of-insight :as flash-of-insight]
    [fizzle.cards.blue.foil :as foil]
    [fizzle.cards.blue.hydroblast :as hydroblast]
    [fizzle.cards.blue.impulse :as impulse]
    [fizzle.cards.blue.intuition :as intuition]
    [fizzle.cards.blue.mana-leak :as mana-leak]
    [fizzle.cards.blue.mental-note :as mental-note]
    [fizzle.cards.blue.merchant-scroll :as merchant-scroll]
    [fizzle.cards.blue.opt :as opt]
    [fizzle.cards.blue.portent :as portent]
    [fizzle.cards.blue.sleight-of-hand :as sleight-of-hand]
    [fizzle.cards.blue.stifle :as stifle]
    [fizzle.cards.blue.words-of-wisdom :as words-of-wisdom]
    [fizzle.cards.green.crumble :as crumble]
    [fizzle.cards.lands.basic-lands :as basic-lands]
    [fizzle.cards.lands.cephalid-coliseum :as cephalid-coliseum]
    [fizzle.cards.lands.city-of-brass :as city-of-brass]
    [fizzle.cards.lands.city-of-traitors :as city-of-traitors]
    [fizzle.cards.lands.fetch-lands :as fetch-lands]
    [fizzle.cards.lands.gemstone-mine :as gemstone-mine]
    [fizzle.cards.lands.pain-lands :as pain-lands]
    [fizzle.cards.multicolor.diabolic-vision :as diabolic-vision]
    [fizzle.cards.red.burning-wish :as burning-wish]
    [fizzle.cards.red.lightning-bolt :as lightning-bolt]
    [fizzle.cards.red.pyroblast :as pyroblast]
    [fizzle.cards.red.recoup :as recoup]
    [fizzle.cards.red.red-elemental-blast :as red-elemental-blast]
    [fizzle.cards.white.abeyance :as abeyance]
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
       duress/card
       ill-gotten-gains/card
       necrologia/card
       rain-of-filth/card
       accumulated-knowledge/card
       annul/card
       blue-elemental-blast/card
       hydroblast/card
       chill/card
       brain-freeze/card
       chain-of-vapor/card
       counterspell/card
       cunning-wish/card
       daze/card
       foil/card
       mana-leak/card
       careful-study/card
       deep-analysis/card
       flash-of-insight/card
       impulse/card
       intuition/card
       mental-note/card
       merchant-scroll/card
       opt/card
       portent/card
       sleight-of-hand/card
       stifle/card
       words-of-wisdom/card
       abeyance/card
       orims-chant/card
       ray-of-revelation/card
       seal-of-cleansing/card
       burning-wish/card
       lightning-bolt/card
       recoup/card
       pyroblast/card
       red-elemental-blast/card
       city-of-brass/card
       city-of-traitors/card
       gemstone-mine/card
       cephalid-coliseum/card
       lotus-petal/card
       lions-eye-diamond/card
       defense-grid/card
       sphere-of-resistance/card
       tormods-crypt/card
       urzas-bauble/card
       crumble/card
       diabolic-vision/card]
      ;; Cycle cards
      basic-lands/cards
      pain-lands/cards
      fetch-lands/cards
      medallions/cards)))
