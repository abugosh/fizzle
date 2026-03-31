(ns fizzle.engine.mana-activation
  "Mana ability activation for Fizzle.

   Pure function: activate-mana-ability takes (db, player-id, object-id, mana-color) -> db.
   Extracted from events/abilities.cljs to the engine layer so bots can call it
   without depending on the event layer."
  (:require
    [fizzle.db.queries :as q]
    [fizzle.engine.abilities :as abilities]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.events :as game-events]
    [fizzle.engine.priority :as priority]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.trigger-dispatch :as dispatch]))


(defn activate-mana-ability
  "Activate a mana ability on a land.
   Pure function: (db, player-id, object-id, mana-color) -> db

   Arguments:
     db - Datascript database value
     player-id - The player activating the ability
     object-id - The land to tap
     mana-color - The color of mana to produce (:white :blue :black :red :green)

   Flow:
     1. Find mana ability from card data
     2. Check can-activate? via abilities module
     3. Pay costs via abilities module (includes tap, remove counters)
     4. Add chosen mana to pool
     5. Fire matching triggers (e.g., City of Brass :becomes-tapped)
     6. Check state-based actions (e.g., Gemstone Mine sacrifice)

   Returns unchanged db if any step fails."
  [db player-id object-id mana-color]
  (if-not (priority/in-priority-phase? (:game/phase (q/get-game-state db)))
    db
    (let [obj (q/get-object db object-id)
          player-eid (q/get-player-eid db player-id)]
      ;; Basic validation
      (if (and obj
               player-eid
               (= (:object/zone obj) :battlefield)
               (= (:db/id (:object/controller obj)) player-eid))
        ;; Find mana ability from card data (with override grant support)
        (let [card (:object/card obj)
              card-abilities (:card/abilities card)
              ;; Check for :land-type-override grants — applies when Vision Charm
              ;; or similar effects change what a land produces until EOT.
              ;; If present, replace each mana ability's :ability/produces with
              ;; the grant's :new-produces value.
              override-grants (filterv #(= :land-type-override (:grant/type %))
                                       (or (:object/grants obj) []))
              effective-abilities (if (seq override-grants)
                                    (let [override (first override-grants)
                                          new-produces (get-in override [:grant/data :new-produces])]
                                      (mapv (fn [ability]
                                              (if (= :mana (:ability/type ability))
                                                (assoc ability :ability/produces new-produces)
                                                ability))
                                            card-abilities))
                                    card-abilities)
              all-mana-abilities (filter #(= :mana (:ability/type %)) effective-abilities)
              ;; Find mana ability that produces the requested color
              ;; Handles multiple patterns:
              ;; 1. :ability/produces {:blue 1} - direct match
              ;; 2. :ability/produces {:any 1} - any color (Gemstone Mine, Lotus Petal)
              ;; 3. :ability/effects with :add-mana {:any N} - City of Brass, LED
              ;; If mana-color is nil or no match found, fall back to first mana ability
              matching-ability (when mana-color
                                 (first (filter
                                          (fn [ability]
                                            (let [produces (:ability/produces ability)
                                                  effects (:ability/effects ability)
                                                  ;; Check if any effect adds {:any N} mana
                                                  has-any-mana-effect? (some #(and (= :add-mana (:effect/type %))
                                                                                   (contains? (:effect/mana %) :any))
                                                                             effects)]
                                              (or
                                                ;; Direct produces match
                                                (and produces (contains? produces mana-color))
                                                ;; Produces any color
                                                (and produces (contains? produces :any))
                                                ;; Effect adds any color
                                                has-any-mana-effect?)))
                                          all-mana-abilities)))
              ;; Fall back to first mana ability if no match or nil color
              mana-ability (or matching-ability (first all-mana-abilities))]
          (if (and mana-ability
                   (abilities/can-activate? db object-id mana-ability))
            ;; Execute ability
            (let [;; Step 1: Pay costs (tap, remove counters, etc.)
                  db-after-costs (abilities/pay-all-costs db object-id (:ability/cost mana-ability))]
              (if db-after-costs
                (let [;; Step 2a: Handle :ability/produces (direct mana production)
                      ;; Resolve {:any N} to chosen color
                      produces (:ability/produces mana-ability)
                      db-after-produces (if produces
                                          (let [resolved-mana (if-let [any-count (:any produces)]
                                                                {mana-color any-count}
                                                                produces)]
                                            (effects/execute-effect db-after-costs player-id
                                                                    {:effect/type :add-mana
                                                                     :effect/mana resolved-mana}))
                                          db-after-costs)
                      ;; Step 2b: Execute ability effects (mana and other effects)
                      ;; Resolve :self targets to object-id before execution
                      ;; Resolve :controller targets to player-id before execution
                      ;; Resolve {:any N} mana effects to chosen color
                      db-after-effects (reduce (fn [db' effect]
                                                 (let [;; Resolve symbolic targets
                                                       resolved-effect (stack/resolve-effect-target effect object-id player-id nil)
                                                       ;; Resolve {:any N} mana to chosen color
                                                       resolved-effect (if (= :add-mana (:effect/type resolved-effect))
                                                                         (let [mana (:effect/mana resolved-effect)]
                                                                           (if-let [any-count (:any mana)]
                                                                             (assoc resolved-effect :effect/mana {mana-color any-count})
                                                                             resolved-effect))
                                                                         resolved-effect)]
                                                   (effects/execute-effect db' player-id resolved-effect)))
                                               db-after-produces
                                               (:ability/effects mana-ability []))
                      ;; Step 3: Dispatch permanent-tapped event to trigger registered triggers
                      ;; (replaces old fire-matching-triggers scanning approach)
                      db-after-triggers (dispatch/dispatch-event db-after-effects
                                                                 (game-events/permanent-tapped-event object-id player-id))]
                  db-after-triggers)
                db))
            db))
        db))))
