(ns fizzle.engine.sorting)


(defn sort-cards
  "Sort game objects by CMC ascending, then card name alphabetically.
   Returns a sorted vector. Handles nil CMC (defaults to 0) and nil name (defaults to empty string)."
  [objects]
  (vec (sort-by (juxt #(get-in % [:object/card :card/cmc] 0)
                      #(get-in % [:object/card :card/name] ""))
                objects)))


(defn group-by-land
  "Split game objects into {:lands [...] :non-lands [...]}.
   An object is a land if its :card/types set contains :land."
  [objects]
  (let [{lands true non-lands false}
        (group-by #(contains? (get-in % [:object/card :card/types]) :land) objects)]
    {:lands (vec (or lands []))
     :non-lands (vec (or non-lands []))}))
