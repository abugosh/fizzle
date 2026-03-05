(ns fizzle.engine.creatures
  "Pure functions for creature stat computation and predicates.

   effective-power / effective-toughness compute dynamically:
   base + counters + grants + self-static-abilities.
   Never stores modified P/T — always recomputed at query time."
  (:require
    [fizzle.db.queries :as q]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.static-abilities :as static]))


(defn creature?
  "Check if object's card has :creature in :card/types."
  [db object-id]
  (let [obj (q/get-object db object-id)
        card (:object/card obj)]
    (boolean (some #{:creature} (:card/types card)))))


(defn effective-power
  "Compute effective power: base + counters + grants + self-static-abilities.
   Returns nil for non-creatures."
  [db object-id]
  (when (creature? db object-id)
    (let [obj (q/get-object db object-id)
          base (or (:object/power obj) 0)
          counters (or (:object/counters obj) {})
          counter-mod (- (get counters :+1/+1 0) (get counters :-1/-1 0))
          pt-grants (grants/get-grants-by-type db object-id :pt-modifier)
          grant-mod (reduce (fn [sum g]
                              (+ sum (or (get-in g [:grant/data :grant/power]) 0)))
                            0 pt-grants)
          obj-eid (q/get-object-eid db object-id)
          static-mods (static/get-self-pt-modifiers db obj-eid)
          static-mod (reduce (fn [sum m] (+ sum (:power m))) 0 static-mods)]
      (+ base counter-mod grant-mod static-mod))))


(defn effective-toughness
  "Compute effective toughness: base + counters + grants + self-static-abilities.
   Returns nil for non-creatures."
  [db object-id]
  (when (creature? db object-id)
    (let [obj (q/get-object db object-id)
          base (or (:object/toughness obj) 0)
          counters (or (:object/counters obj) {})
          counter-mod (- (get counters :+1/+1 0) (get counters :-1/-1 0))
          pt-grants (grants/get-grants-by-type db object-id :pt-modifier)
          grant-mod (reduce (fn [sum g]
                              (+ sum (or (get-in g [:grant/data :grant/toughness]) 0)))
                            0 pt-grants)
          obj-eid (q/get-object-eid db object-id)
          static-mods (static/get-self-pt-modifiers db obj-eid)
          static-mod (reduce (fn [sum m] (+ sum (:toughness m))) 0 static-mods)]
      (+ base counter-mod grant-mod static-mod))))


(defn has-keyword?
  "Check if object has keyword from card definition OR granted keywords."
  [db object-id kw]
  (let [obj (q/get-object db object-id)
        card-keywords (set (or (:card/keywords (:object/card obj)) #{}))
        keyword-grants (grants/get-grants-by-type db object-id :keyword)
        granted-keywords (set (map #(get-in % [:grant/data :grant/keyword]) keyword-grants))]
    (boolean (or (contains? card-keywords kw)
                 (contains? granted-keywords kw)))))


(defn summoning-sick?
  "True if creature with :object/summoning-sick and no haste."
  [db object-id]
  (if (creature? db object-id)
    (let [obj (q/get-object db object-id)]
      (boolean (and (:object/summoning-sick obj)
                    (not (has-keyword? db object-id :haste)))))
    false))


(defn can-attack?
  "Check if a creature can attack: creature, not sick, not tapped, not defender."
  [db object-id]
  (and (creature? db object-id)
       (not (summoning-sick? db object-id))
       (let [obj (q/get-object db object-id)]
         (not (:object/tapped obj)))
       (not (has-keyword? db object-id :defender))))


(defn can-block?
  "Check if a creature can block an attacker: creature, not tapped, flying/reach."
  [db object-id attacker-id]
  (and (creature? db object-id)
       (let [obj (q/get-object db object-id)]
         (not (:object/tapped obj)))
       ;; Flying check: if attacker has flying, blocker must have flying or reach
       (if (has-keyword? db attacker-id :flying)
         (or (has-keyword? db object-id :flying)
             (has-keyword? db object-id :reach))
         true)))
