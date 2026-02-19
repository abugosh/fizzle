(ns fizzle.bots.definitions
  "Bot spec definitions and registry.

   Bot specs are EDN data describing bot behavior:
   deck composition, phase actions, and priority rules.
   Pure data — no re-frame dependency.")


(def goldfish-spec
  {:bot/name "Goldfish"
   :bot/deck [{:card/id :plains :count 12}
              {:card/id :island :count 12}
              {:card/id :swamp :count 12}
              {:card/id :mountain :count 12}
              {:card/id :forest :count 12}]
   :bot/phase-actions {:main1 :play-land}
   :bot/priority-rules []})


(def burn-spec
  {:bot/name "Burn"
   :bot/deck [{:card/id :mountain :count 20}
              {:card/id :lightning-bolt :count 40}]
   :bot/phase-actions {:main1 :play-land}
   :bot/priority-rules
   [{:rule/mode :auto
     :rule/conditions [{:check :zone-contains :zone :hand :player :self :card-id :lightning-bolt}
                       {:check :has-untapped-source :color :red}
                       {:check :stack-empty}]
     :rule/action {:action :cast-spell :card-id :lightning-bolt :target :opponent}}]})


(def ^:private registry
  {:goldfish goldfish-spec
   :burn burn-spec})


(defn get-spec
  "Look up a bot spec by archetype keyword.
   Returns nil for unknown archetypes."
  [archetype]
  (get registry archetype))


(defn get-deck
  "Get the deck list for a bot archetype.
   Returns nil for unknown archetypes."
  [archetype]
  (:bot/deck (get-spec archetype)))


(defn list-archetypes
  "Return available archetype keywords."
  []
  (keys registry))
