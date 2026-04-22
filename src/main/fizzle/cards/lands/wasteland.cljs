;; Wasteland - Land
;; Oracle: {T}: Add {C}.
;;         {T}, Sacrifice this land: Destroy target nonbasic land.
;; Scryfall verified: 2026-04-21
(ns fizzle.cards.lands.wasteland
  "Wasteland card definition.

   Wasteland: Land
   {T}: Add {C}.
   {T}, Sacrifice this land: Destroy target nonbasic land.")


(def card
  {:card/id :wasteland
   :card/name "Wasteland"
   :card/cmc 0
   :card/mana-cost {}
   :card/colors #{}
   :card/types #{:land}
   :card/text "{T}: Add {C}.\n{T}, Sacrifice this land: Destroy target nonbasic land."

   :card/abilities
   [;; Ability 0: {T}: Add {C}.
    {:ability/type :mana
     :ability/cost {:tap true}
     :ability/produces {:colorless 1}}

    ;; Ability 1: {T}, Sacrifice this land: Destroy target nonbasic land.
    {:ability/type :activated
     :ability/cost {:tap true
                    :sacrifice-self true}
     :ability/description "Destroy target nonbasic land"
     :ability/targeting [{:target/id :target-land
                          :target/type :object
                          :target/zone :battlefield
                          :target/controller :any
                          :target/criteria {:match/types #{:land}
                                            :match/not-supertypes #{:basic}}
                          :target/required true}]
     :ability/effects [{:effect/type :destroy
                        :effect/target-ref :target-land}]}]})
