(ns fizzle.engine.sorting)


(defn sort-cards
  "Sort game objects by CMC ascending, lands before non-lands within same CMC,
   then card name alphabetically.
   Returns a sorted vector. Handles nil CMC (defaults to 0) and nil name (defaults to empty string)."
  [objects]
  (vec (sort-by (juxt #(get-in % [:object/card :card/cmc] 0)
                      #(if (contains? (set (get-in % [:object/card :card/types])) :land) 0 1)
                      #(get-in % [:object/card :card/name] ""))
                objects)))


(defn group-by-land
  "Split game objects into {:lands [...] :non-lands [...]}.
   An object is a land if its :card/types set contains :land."
  [objects]
  (let [{lands true non-lands false}
        (group-by #(contains? (set (get-in % [:object/card :card/types])) :land) objects)]
    {:lands (vec (or lands []))
     :non-lands (vec (or non-lands []))}))


(defn- classify-permanent-type
  "Classify a permanent into :creatures, :lands, or :other.
   Priority: creature > land > other.
   Handles both sets and vectors from Datascript."
  [obj]
  (let [types (set (or (get-in obj [:object/card :card/types]) []))]
    (cond
      (contains? types :creature) :creatures
      (contains? types :land) :lands
      :else :other)))


(defn group-by-type
  "Split game objects into {:creatures [...] :other [...] :lands [...]}.
   Classification priority: creature > land > other.
   - Objects with :creature in :card/types → :creatures
   - Objects with :land in :card/types (but not :creature) → :lands
   - All others (including nil types) → :other"
  [objects]
  (let [grouped (group-by classify-permanent-type objects)]
    {:creatures (vec (or (get grouped :creatures) []))
     :other (vec (or (get grouped :other) []))
     :lands (vec (or (get grouped :lands) []))}))
