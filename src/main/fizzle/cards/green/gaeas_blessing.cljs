(ns fizzle.cards.green.gaeas-blessing
  "Gaea's Blessing — {1}{G} Sorcery, Weatherlight
   Oracle text (Scryfall-verified):
   'Target player shuffles up to three target cards from their graveyard
   into their library. Draw a card. When this card is put into your graveyard
   from your library, shuffle your graveyard into your library.'")


(def card
  {:card/id :gaeas-blessing
   :card/name "Gaea's Blessing"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :green 1}
   :card/colors #{:green}
   :card/types #{:sorcery}
   :card/text "Target player shuffles up to three target cards from their graveyard into their library. Draw a card. When this card is put into your graveyard from your library, shuffle your graveyard into your library."

   ;; Cast-time targeting: choose which player's graveyard to target ("target player")
   :card/targeting [{:target/id :player
                     :target/type :player
                     :target/options #{:any-player}
                     :target/required true}]

   ;; Three effects in order:
   :card/effects [;; 1. Target player shuffles up to 3 cards from their graveyard into library
                  {:effect/type :shuffle-from-graveyard-to-library
                   :effect/target :targeted-player
                   :effect/count 3
                   :effect/selection :player}

                  ;; 2. Draw a card (caster draws)
                  {:effect/type :draw
                   :effect/amount 1}]

   ;; Library trigger: when this card is put into graveyard FROM library
   :card/triggers [{:trigger/type :zone-change
                    :trigger/match {:event/from-zone :library
                                    :event/to-zone :graveyard
                                    :event/object-id :self}
                    :trigger/effects [{:effect/type :shuffle-from-graveyard-to-library
                                       :effect/target :self-controller
                                       :effect/count :all
                                       :effect/selection :auto}]
                    :trigger/description "When Gaea's Blessing is put into your graveyard from your library, shuffle your graveyard into your library."}]})
