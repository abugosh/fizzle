(ns fizzle.engine.mana-activation
  "Mana ability activation for Fizzle.

   Pure function: activate-mana-ability takes
     (db, player-id, object-id, mana-color[, ability-index[, allocation]]) -> db.
   Extracted from events/abilities.cljs to the engine layer so bots can call it
   without depending on the event layer.

   For mana abilities with generic (colorless) mana costs (e.g. Chromatic Sphere),
   the caller MUST supply an explicit `allocation` argument mapping color keywords
   to amounts. Without it the function fails closed (ADR-019 push-down invariants)."
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.abilities :as abilities]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.priority :as priority]
    [fizzle.engine.stack :as stack]))


;; === Known mana color keys ===

(def ^:private known-mana-colors
  #{:white :blue :black :red :green :colorless})


;; === Allocation validation ===

(defn- valid-allocation?
  "Return true if `allocation` is a valid covering of the generic-mana portion.

   Validation rules (all must pass):
   1. All keys in allocation must be known mana colors.
   2. Sum of (non-zero) allocation values must equal `generic` (exact match).
   3. For each [color amount] in allocation: pool[color] >= amount.

   Zero-value entries are ignored in the sum (treated as absent)."
  [allocation generic pool]
  (let [;; Strip zero-value entries — {:black 0 :red 1} => {:red 1}
        effective (into {} (filter (fn [[_ v]] (pos? v)) allocation))
        sum (reduce + 0 (vals effective))]
    (and
      ;; Guard: all allocation keys must be known mana colors
      (every? known-mana-colors (keys allocation))
      ;; Guard: sum must exactly equal the generic cost
      (= sum generic)
      ;; Guard: each color in effective allocation must be covered by pool
      (every? (fn [[color amount]]
                (>= (get pool color 0) amount))
              effective))))


;; === Production and effects helper ===

(defn execute-mana-ability-production-and-effects
  "Resolve :ability/produces with the caller-supplied chosen color, add the
   produced mana to the player's pool, then execute :ability/effects with
   :self -> object-id and :controller -> player-id resolution.

   Pure: db -> db.

   chosen-color: keyword (e.g., :blue). For {:any 1} produces, resolves to
   chosen-color. Currently implements single-color resolution only; future tasks
   may extend this helper to accept a sequence of colors for {:any N} (N > 1)
   by replacing the single keyword with a vector.

   Arguments:
     db          - Datascript database value
     player-id   - Activating player (controller of the ability)
     object-id   - UUID of the source permanent
     ability     - The ability map (must have :ability/produces and/or :ability/effects)
     chosen-color - Keyword: the color chosen for {:any 1} resolution"
  [db player-id object-id ability chosen-color]
  (let [;; Step 1: Handle :ability/produces (direct mana production)
        ;; Resolve {:any N} to chosen color
        produces (:ability/produces ability)
        db-after-produces (if produces
                            (let [resolved-mana (if-let [any-count (:any produces)]
                                                  {chosen-color any-count}
                                                  produces)]
                              (effects/execute-effect db player-id
                                                      {:effect/type :add-mana
                                                       :effect/mana resolved-mana}))
                            db)
        ;; Step 2: Execute ability effects (mana and other effects)
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
                                                               (assoc resolved-effect :effect/mana {chosen-color any-count})
                                                               resolved-effect))
                                                           resolved-effect)]
                                     (effects/execute-effect db' player-id resolved-effect)))
                                 db-after-produces
                                 (:ability/effects ability []))]
    db-after-effects))


;; === Main activation function ===

(defn activate-mana-ability
  "Activate a mana ability on a permanent.
   Pure function: (db, player-id, object-id, mana-color[, ability-index[, allocation]]) -> db

   Arguments:
     db           - Datascript database value
     player-id    - The player activating the ability
     object-id    - The permanent to activate
     mana-color   - The color of mana to produce (:white :blue :black :red :green :colorless)
     ability-index - (optional) Index into :card/abilities of the mana ability to
                     activate. Required when a permanent has multiple mana abilities
                     producing the same color (e.g. Crystal Vein). When omitted,
                     falls back to color-based lookup.
     allocation   - (optional) Map {:color amount} covering the GENERIC portion of the
                    ability's mana cost. Required when the ability has a generic (:colorless)
                    mana cost component. Ignored for abilities without generic cost.
                    Example: {:black 1} to spend 1 black covering a {1} generic cost.
                    The allocation is NOT via pay-cost :mana — it is deducted directly,
                    so the caller (or events layer) must have already verified the pool.

   Flow:
     1. Priority-phase guard
     2. Controller and zone guards
     3. Find mana ability from card data (with override grant support)
     4. can-activate? check
     5. Allocation validation (if generic cost present)
     6. Deduct generic allocation from pool (if valid)
     7. Pay non-generic costs via pay-all-costs (tap, sacrifice-self, remove-counter,
        plus any colored :mana portion)
     8. Execute execute-mana-ability-production-and-effects

   Note: :permanent-tapped trigger dispatch happens inside costs/pay-cost :tap, which is
   called by abilities/pay-all-costs in step 7. This is the tap chokepoint.

   Returns unchanged db if any step fails (fail closed — no silent wrong-state)."
  ([db player-id object-id mana-color]
   ;; Guard: delegate to 6-arity with no ability-index and no allocation
   (activate-mana-ability db player-id object-id mana-color nil nil))
  ([db player-id object-id mana-color ability-index]
   ;; Guard: delegate to 6-arity with no allocation
   (activate-mana-ability db player-id object-id mana-color ability-index nil))
  ([db player-id object-id mana-color ability-index allocation]
   ;; Guard: must be in a priority phase
   (if-not (priority/in-priority-phase? (:game/phase (q/get-game-state db)))
     db
     (let [obj (q/get-object db object-id)
           player-eid (q/get-player-eid db player-id)]
       ;; Guard: object must exist, player must exist, object must be on battlefield,
       ;; and the activating player must be the controller
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
               ;; When ability-index is provided, resolve it against :card/abilities
               ;; and verify the entry is a mana ability. Otherwise fall back to
               ;; color-based lookup over the mana-ability-only subset.
               indexed-ability (when ability-index
                                 (let [candidate (nth effective-abilities ability-index nil)]
                                   (when (= :mana (:ability/type candidate))
                                     candidate)))
               all-mana-abilities (filter #(= :mana (:ability/type %)) effective-abilities)
               ;; Find mana ability that produces the requested color
               ;; Handles multiple patterns:
               ;; 1. :ability/produces {:blue 1} - direct match
               ;; 2. :ability/produces {:any 1} - any color (Gemstone Mine, Lotus Petal)
               ;; 3. :ability/effects with :add-mana {:any N} - City of Brass, LED
               ;; If mana-color is nil or no match found, fall back to first mana ability
               matching-ability (when (and (nil? indexed-ability) mana-color)
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
               ;; Priority: explicit index > color match > first mana ability.
               ;; If ability-index was provided but didn't resolve to a mana
               ;; ability, leave mana-ability nil so the call becomes a no-op
               ;; rather than silently firing the wrong ability.
               mana-ability (cond
                              indexed-ability indexed-ability
                              ability-index nil
                              :else (or matching-ability (first all-mana-abilities)))]
           (if (and mana-ability
                    (abilities/can-activate? db object-id mana-ability))
             ;; Execute ability — allocation-aware path
             (let [cost (:ability/cost mana-ability)
                   mana-cost (:mana cost)
                   generic (get mana-cost :colorless 0)
                   pool (or (q/get-mana-pool db player-id) {})]
               ;; Guard: if ability has a generic mana cost, allocation must be valid
               (if (and (pos? generic)
                        (not (valid-allocation? (or allocation {}) generic pool)))
                 ;; Guard: fail closed — no silent wrong-state (ADR-019)
                 db
                 ;; All checks pass — pay costs and produce mana
                 (let [;; Step 1: Deduct generic allocation from pool directly
                       ;; (NOT via pay-cost :mana — allocation is the authoritative spend)
                       db-after-generic (if (pos? generic)
                                          (let [effective-alloc (into {}
                                                                      (filter (fn [[_ v]] (pos? v))
                                                                              allocation))
                                                new-pool (merge-with - pool effective-alloc)]
                                            (d/db-with
                                              db
                                              [[:db/add player-eid :player/mana-pool new-pool]]))
                                          db)
                       ;; Step 2: Pay non-generic costs via pay-all-costs:
                       ;; - Remove colorless from the mana cost (already handled above)
                       ;; - Keep any colored mana portion (pay-cost :mana handles it)
                       ;; - Keep tap, sacrifice-self, remove-counter
                       reduced-cost (if (pos? generic)
                                      (update cost :mana dissoc :colorless)
                                      cost)
                       ;; Remove empty :mana key to avoid empty-map pass to pay-cost :mana
                       reduced-cost (if (empty? (:mana reduced-cost))
                                      (dissoc reduced-cost :mana)
                                      reduced-cost)
                       db-after-costs (abilities/pay-all-costs db-after-generic object-id reduced-cost)]
                   (if db-after-costs
                     ;; Step 3: Produce mana and run effects
                     (execute-mana-ability-production-and-effects
                       db-after-costs player-id object-id mana-ability mana-color)
                     ;; Guard: pay-all-costs returned nil (cost payment failed)
                     db))))
             ;; Guard: mana-ability not found or can-activate? false
             db))
         ;; Guard: object/player/zone/controller check failed
         db)))))
