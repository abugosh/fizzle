(ns fizzle.engine.static-abilities
  "Static ability framework using query-at-check-time pattern.

   Scans battlefield permanents for :card/static-abilities at the moment
   a rule needs evaluation. No grant lifecycle management needed.

   Currently supports :cost-modifier type for cards like Sphere of Resistance,
   Defense Grid, and Chill."
  (:require
    [fizzle.db.queries :as q]))


(defn- get-static-abilities-from-battlefield
  "Scan all battlefield permanents and return static abilities with source metadata.
   Returns vector of maps: {:static-ability ability :source/controller pid :source/object-id oid}"
  [db]
  (let [battlefield-objects (q/get-all-objects-in-zone db :battlefield)]
    (->> battlefield-objects
         (mapcat (fn [obj]
                   (let [card (:object/card obj)
                         abilities (:card/static-abilities card)
                         controller-eid (:object/controller obj)
                         controller-id (q/get-player-id db (:db/id controller-eid))]
                     (when (seq abilities)
                       (map (fn [ability]
                              {:static-ability ability
                               :source/controller controller-id
                               :source/object-id (:object/id obj)})
                            abilities)))))
         (vec))))


(defn get-cost-modifiers
  "Get all cost-modifier static abilities currently active on the battlefield.
   Returns vector of modifier maps with source metadata."
  [db]
  (->> (get-static-abilities-from-battlefield db)
       (filter #(= :cost-modifier (:static/type (:static-ability %))))
       (vec)))


(defn modifier-applies?
  "Check if a cost modifier applies to a given spell being cast.
   Evaluates :modifier/criteria (spell matching) and :modifier/condition (game state).

   Arguments:
     db - Datascript game database
     caster-id - Player casting the spell
     spell-card - Card data of the spell being cast
     modifier-entry - Map with :static-ability and source metadata"
  [db caster-id spell-card modifier-entry]
  (let [ability (:static-ability modifier-entry)
        criteria (:modifier/criteria ability)
        condition (:modifier/condition ability)]
    (and
      ;; Check criteria (spell matching)
      (if criteria
        (let [criteria-type (:criteria/type criteria)]
          (case criteria-type
            :spell-color (let [required-colors (:criteria/colors criteria)
                               spell-colors (set (:card/colors spell-card))]
                           (some spell-colors required-colors))
            ;; Default: no match for unknown criteria
            false))
        ;; No criteria = applies to all spells
        true)
      ;; Check condition (game state)
      (if condition
        (let [condition-type (:condition/type condition)]
          (case condition-type
            :not-casters-turn (let [active-player (q/get-active-player-id db)]
                                (not= caster-id active-player))
            ;; Default: no match for unknown condition
            false))
        ;; No condition = always applies
        true))))


(defn apply-cost-modifiers
  "Compute effective mana cost by applying all applicable cost modifiers.
   Sums all applicable :increase modifiers and adds to :colorless key of base-cost.

   Arguments:
     db - Datascript game database
     caster-id - Player casting the spell
     spell-card - Card data of the spell being cast
     base-cost - Base mana cost map (e.g. {:blue 2} or {:black 3})

   Returns modified cost map."
  [db caster-id spell-card base-cost]
  (let [modifiers (get-cost-modifiers db)
        applicable (filter #(modifier-applies? db caster-id spell-card %) modifiers)
        total-increase (reduce (fn [sum mod-entry]
                                 (let [ability (:static-ability mod-entry)]
                                   (if (= :increase (:modifier/direction ability))
                                     (+ sum (:modifier/amount ability))
                                     sum)))
                               0
                               applicable)]
    (if (pos? total-increase)
      (update base-cost :colorless (fnil + 0) total-increase)
      base-cost)))


(defn get-effective-mana-cost
  "Convenience function: get effective mana cost for a spell being cast.
   Looks up card from object-id, gets base cost from mode, applies modifiers.

   Arguments:
     db - Datascript game database
     player-id - Player casting the spell
     object-id - Object ID of the spell
     mode - Casting mode map

   Returns effective mana cost map."
  [db player-id object-id mode]
  (let [obj (q/get-object db object-id)
        spell-card (:object/card obj)
        base-cost (:mode/mana-cost mode)]
    (apply-cost-modifiers db player-id spell-card base-cost)))
