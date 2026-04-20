(ns fizzle.cards.artifacts.mox-diamond
  "Mox Diamond card definition.

   Mox Diamond: 0 - Artifact
   If Mox Diamond would enter the battlefield, you may discard a land card.
   If you don't, sacrifice Mox Diamond.
   {T}: Add one mana of any color.")


(def card
  {:card/id :mox-diamond
   :card/name "Mox Diamond"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:artifact}
   :card/text "If Mox Diamond would enter the battlefield, you may discard a land card. If you don't, sacrifice Mox Diamond. {T}: Add one mana of any color."
   :card/replacement-effects
   [{:replacement/event :zone-change
     :replacement/match {:match/object :self
                         :match/to     :battlefield}
     :replacement/choices
     [{:choice/action :proceed
       :choice/label  "Discard a land"
       :choice/cost   {:effect/type     :discard-specific
                       :effect/criteria {:match/types #{:land}}
                       :effect/count    1
                       :effect/from     :hand}}
      {:choice/action      :redirect
       :choice/label       "Sacrifice (go to graveyard)"
       :choice/redirect-to :graveyard}]}]
   :card/abilities
   [{:ability/type     :mana
     :ability/cost     {:tap true}
     :ability/produces {:any 1}}]})
