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
  "Execute :ability/effects with :self -> object-id and :controller -> player-id
   resolution. :ability/effects is the single source of truth for mana production
   (via :add-mana effects) and other ability effects (draw, deal-damage, sacrifice, etc.).

   Pure: db -> db.

   chosen-color: keyword (e.g., :blue). For {:any 1} :add-mana effects, resolves
   to chosen-color. Currently implements single-color resolution only; future tasks
   may extend this helper to accept a sequence of colors for {:any N} (N > 1)
   by replacing the single keyword with a vector.

   Arguments:
     db          - Datascript database value
     player-id   - Activating player (controller of the ability)
     object-id   - UUID of the source permanent
     ability     - The ability map (must have :ability/effects)
     chosen-color - Keyword: the color chosen for {:any 1} resolution"
  [db player-id object-id ability chosen-color]
  (let [;; Execute ability effects (mana and other effects)
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
                                 db
                                 (:ability/effects ability []))]
    db-after-effects))


;; === Main activation function ===

;; === Ability resolution helpers ===

(defn resolve-ability
  "Resolve an ability map from an object using an ability-ref.

   ability-ref shapes:
     {:source :card :index N}        — resolves from :card/abilities at index N
     {:source :grant :grant-id uuid} — resolves from :object/grants by :grant/id

   Returns the ability map if found and it is a :mana type ability, else nil.
   Returns nil for: out-of-bounds index, grant not found, non-:mana ability type."
  [obj ability-ref]
  (when (and obj ability-ref)
    (let [source (:source ability-ref)]
      (cond
        (= :card source)
        (let [idx (:index ability-ref)
              candidate (nth (get-in obj [:object/card :card/abilities]) idx nil)]
          (when (= :mana (:ability/type candidate))
            candidate))

        (= :grant source)
        (let [grant-id (:grant-id ability-ref)
              grants (or (:object/grants obj) [])
              grant (first (filter #(= grant-id (:grant/id %)) grants))
              ability (:grant/data grant)]
          (when (= :mana (:ability/type ability))
            ability))

        :else nil))))


;; === Main activation function ===

(defn activate-mana-ability
  "Activate a mana ability on a permanent.
   Pure function: (db, player-id, object-id, mana-color[, ability-ref[, allocation]]) -> db

   Arguments:
     db          - Datascript database value
     player-id   - The player activating the ability
     object-id   - The permanent to activate
     mana-color  - The color of mana to produce (:white :blue :black :red :green :colorless)
     ability-ref - (optional) Map identifying which ability to activate:
                     {:source :card :index N}        — from :card/abilities at index N
                     {:source :grant :grant-id uuid} — from :object/grants by :grant/id
                   When nil, falls back to color-based lookup (bot path).
     allocation  - (optional) Map {:color amount} covering the GENERIC portion of the
                   ability's mana cost. Required when the ability has a generic (:colorless)
                   mana cost component. Ignored for abilities without generic cost.
                   Example: {:black 1} to spend 1 black covering a {1} generic cost.
                   The allocation is NOT via pay-cost :mana — it is deducted directly,
                   so the caller (or events layer) must have already verified the pool.

   Flow:
     1. Priority-phase guard
     2. Controller and zone guards
     3. Resolve ability from ability-ref (card or grant) or color-based fallback
     4. can-activate? check (applied to ALL paths including grants)
     5. Allocation validation (if generic cost present)
     6. Deduct generic allocation from pool (if valid)
     7. Pay non-generic costs via pay-all-costs (tap, sacrifice-self, remove-counter,
        plus any colored :mana portion)
     8. Execute execute-mana-ability-production-and-effects

   Note: :permanent-tapped trigger dispatch happens inside costs/pay-cost :tap, which is
   called by abilities/pay-all-costs in step 7. This is the tap chokepoint.

   Returns unchanged db if any step fails (fail closed — no silent wrong-state)."
  ([db player-id object-id mana-color]
   ;; Guard: delegate to 6-arity with no ability-ref and no allocation (bot path)
   (activate-mana-ability db player-id object-id mana-color nil nil))
  ([db player-id object-id mana-color ability-ref]
   ;; Guard: delegate to 6-arity with no allocation
   (activate-mana-ability db player-id object-id mana-color ability-ref nil))
  ([db player-id object-id mana-color ability-ref allocation]
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
         ;; Resolve ability based on ability-ref source
         (let [;; For :card source or nil ability-ref: apply :land-type-override grants
               ;; For :grant source: skip override (grant has its own effects)
               grant-source? (= :grant (:source ability-ref))
               card (:object/card obj)
               card-abilities (:card/abilities card)
               ;; Check for :land-type-override grants — applies when Vision Charm
               ;; or similar effects change what a land produces until EOT.
               ;; Skip override logic for grant-source activations.
               override-grants (when-not grant-source?
                                 (filterv #(= :land-type-override (:grant/type %))
                                          (or (:object/grants obj) [])))
               effective-abilities (if (seq override-grants)
                                     (let [override (first override-grants)
                                           new-produces (get-in override [:grant/data :new-produces])]
                                       (mapv (fn [ability]
                                               (if (= :mana (:ability/type ability))
                                                 (assoc ability :ability/effects [{:effect/type :add-mana
                                                                                   :effect/mana new-produces}])
                                                 ability))
                                             card-abilities))
                                     card-abilities)
               ;; Resolve the ability to activate
               mana-ability (cond
                              ;; Grant source: resolve from :object/grants directly
                              grant-source?
                              (let [grant-id (:grant-id ability-ref)
                                    grants (or (:object/grants obj) [])
                                    grant (first (filter #(= grant-id (:grant/id %)) grants))
                                    ability (:grant/data grant)]
                                (when (= :mana (:ability/type ability))
                                  ability))

                              ;; Card source with explicit index
                              (= :card (:source ability-ref))
                              (let [idx (:index ability-ref)
                                    candidate (nth effective-abilities idx nil)]
                                (when (= :mana (:ability/type candidate))
                                  candidate))

                              ;; No ability-ref: color-based lookup (bot path)
                              :else
                              (let [all-mana-abilities (filter #(= :mana (:ability/type %)) effective-abilities)
                                    ;; Find mana ability that produces the requested color
                                    ;; Reads :ability/effects exclusively — :ability/produces has been removed.
                                    ;; Handles multiple patterns via :add-mana effects:
                                    ;; 1. {:add-mana {:blue 1}} - direct color match
                                    ;; 2. {:add-mana {:any 1}} - any color (Gemstone Mine, Lotus Petal, Mox Diamond)
                                    ;; 3. {:add-mana {:any N}} - any color N mana (City of Brass, LED)
                                    ;; If mana-color is nil or no match found, fall back to first mana ability
                                    matching-ability (when mana-color
                                                       (first (filter
                                                                (fn [ability]
                                                                  (let [effects (:ability/effects ability)
                                                                        ;; Check if any :add-mana effect matches the requested color
                                                                        has-color-mana-effect? (some #(and (= :add-mana (:effect/type %))
                                                                                                           (contains? (:effect/mana %) mana-color))
                                                                                                     effects)
                                                                        ;; Check if any :add-mana effect produces any color
                                                                        has-any-mana-effect? (some #(and (= :add-mana (:effect/type %))
                                                                                                         (contains? (:effect/mana %) :any))
                                                                                                   effects)]
                                                                    (or has-color-mana-effect? has-any-mana-effect?)))
                                                                all-mana-abilities)))]
                                (or matching-ability (first all-mana-abilities))))]
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
