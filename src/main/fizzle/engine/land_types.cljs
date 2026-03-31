(ns fizzle.engine.land-types
  "Canonical land type definitions. Single source of truth for
   basic land subtypes and their mana production.

   Use this namespace everywhere land types are needed to avoid
   hardcoding land type lists in multiple files.")


(def basic-land-types
  "The 5 basic land types with their subtype keyword and mana color."
  {:plains   {:subtype :plains   :mana-color :white :produces {:white 1}}
   :island   {:subtype :island   :mana-color :blue  :produces {:blue 1}}
   :swamp    {:subtype :swamp    :mana-color :black :produces {:black 1}}
   :mountain {:subtype :mountain :mana-color :red   :produces {:red 1}}
   :forest   {:subtype :forest   :mana-color :green :produces {:green 1}}})


(def basic-land-type-keys
  "Ordered vector of basic land type keywords for selection UI."
  [:plains :island :swamp :mountain :forest])
