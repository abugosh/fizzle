# Engine Core Patterns — Exact Signatures & Code

This document captures exact code patterns for the most-used engine subsystems, enabling task specification with confidence.

## 1. reduce-effects: Loop Structure and Signature

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects.cljs` (lines 150-175)

### Signature
```clojure
(defn reduce-effects
  "Execute effects sequentially, pausing when an interactive effect is encountered.

   Returns:
     {:db db'} - All effects executed successfully (no interactive effects)
     {:db db' :needs-selection effect :remaining-effects [...]} -
       Paused at an interactive effect. :db has all effects before
       the interactive one applied. :remaining-effects are the effects
       after the interactive one that still need execution.

   Arguments:
     db - Datascript database value
     player-id - The player who controls these effects
     effects - Sequence of effect maps
     object-id - (Optional) The object that is the source of these effects."
  ([db player-id effects]
   (reduce-effects db player-id effects nil))
  ([db player-id effects object-id]
   (loop [db db
          [effect & remaining] (seq effects)]
     (if-not effect
       {:db db}
       (let [result (execute-effect-checked db player-id effect object-id)]
         (if (:needs-selection result)
           (assoc result :remaining-effects (vec remaining))
           (recur (:db result) remaining)))))))
```

### How It Works
1. **Loop pattern**: Uses `loop/recur` with destructuring `[effect & remaining]` from `(seq effects)`
2. **Exit condition**: When `effect` is falsy (all effects consumed), returns `{:db db}`
3. **Per-effect execution**: Calls `execute-effect-checked` which returns either:
   - Plain `{:db db'}` → recurse with new db and remaining effects
   - Tagged `{:db db' :needs-selection effect}` → pause and return with `:remaining-effects` appended
4. **SBA hook**: Would occur AFTER all effects execute (after loop exits with `{:db db}`) and BEFORE the result is returned to the caller

---

## 2. Grant System: Creation & Expiration

**Files**:
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/grants.cljs` (grant system core)
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/grants.cljs` (grant creation via effects)

### Grant Data Structure

```clojure
{:grant/id        (random-uuid)  ; Unique identifier
 :grant/type      :pt-modifier   ; Keyword type (:alternate-cost, :ability, :keyword, :static-effect, :restriction, etc.)
 :grant/source    object-id      ; Object ID that created this grant
 :grant/expires   {:expires/turn N :expires/phase :cleanup}  ; Expiration point
 :grant/data      {...}}         ; Type-specific payload
```

### Example: PT-Modifier Grant (from test, line 76-80)

```clojure
{:grant/id (random-uuid)
 :grant/type :pt-modifier
 :grant/source obj-id
 :grant/data {:grant/power 3 :grant/toughness 3}}
```

### Example: Flashback Grant (from effects/grants.cljs, lines 26-46)

```clojure
(defmethod effects/execute-effect-impl :grant-flashback
  [db _player-id effect object-id]
  (let [target-id (:effect/target effect)]
    (if-not target-id
      db
      (if-let [target-obj (q/get-object db target-id)]
        (let [target-card (:object/card target-obj)
              target-mana-cost (:card/mana-cost target-card)
              game-state (q/get-game-state db)
              current-turn (or (:game/turn game-state) 1)
              grant {:grant/id (random-uuid)
                     :grant/type :alternate-cost
                     :grant/source object-id
                     :grant/expires {:expires/turn current-turn
                                     :expires/phase :cleanup}
                     :grant/data {:alternate/id :granted-flashback
                                  :alternate/zone :graveyard
                                  :alternate/mana-cost target-mana-cost
                                  :alternate/on-resolve :exile}}]
          (grants/add-grant db target-id grant))
        db))))
```

**Key pattern**: Grant creation happens inside an `execute-effect-impl` defmethod. The grant has:
- `:grant/type` to classify it
- `:grant/expires` with turn and phase (`:expires/turn N :expires/phase :cleanup` is common for end-of-turn)
- `:grant/data` map with type-specific payload (`:grant/power`, `:grant/toughness`, `:alternate/*`, etc.)

### Expiration Mechanism

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/grants.cljs` (lines 150-232)

```clojure
(def phase-order
  "MTG turn phases in order for comparison."
  [:untap :upkeep :draw :main1 :combat :main2 :end :cleanup])

(defn grant-expired?
  "Check if a grant has expired given current turn/phase.

   Expiration format:
   - {:expires/turn N :expires/phase :cleanup} - expires at turn N, phase
   - {:expires/permanent true} - never expires
   - nil / missing - never expires"
  [grant current-turn current-phase]
  (let [expires (:grant/expires grant)]
    (cond
      ;; No expiration data = never expires
      (nil? expires)
      false
      ;; Permanent grants never expire
      (:expires/permanent expires)
      false
      ;; Past the expiration turn
      (> current-turn (:expires/turn expires))
      true
      ;; Same turn, check phase
      (= current-turn (:expires/turn expires))
      (phase-reached? current-phase (:expires/phase expires))
      ;; Before expiration turn
      :else
      false)))

(defn expire-grants
  "Remove all expired grants from all objects AND players given current turn/phase.
   Returns updated db."
  [db current-turn current-phase]
  (-> db
      (expire-object-grants current-turn current-phase)
      (expire-player-grants current-turn current-phase)))
```

**Key pattern**:
- `:grant/expires` is a map with `:expires/turn` and `:expires/phase`
- `phase-reached?` uses `phase-order` index comparison
- Grants are removed by filtering: `filterv #(not (grant-expired? % current-turn current-phase))`
- Called at phase transition times (e.g., start of cleanup)

---

## 3. Creatures: Power/Toughness Computation

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/creatures.cljs` (lines 21-56)

### effective-power

```clojure
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
```

### effective-toughness

```clojure
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
```

**Key pattern**:
- Returns `nil` if not a creature
- Reads `:object/power` and `:object/toughness` from object (set when creature enters battlefield)
- Counters: `(- (get counters :+1/+1 0) (get counters :-1/-1 0))`
- Grants: Read via `grants/get-grants-by-type db object-id :pt-modifier`, sum `:grant/data :grant/power` and `:grant/data :grant/toughness`
- Static abilities: Via `static/get-self-pt-modifiers`, which is NOT shown here but likely queries trigger DB
- All summed: `(+ base counter-mod grant-mod static-mod)`

---

## 4. Token Creation Effect Executor

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/tokens.cljs` (lines 13-44)

```clojure
(defmethod effects/execute-effect-impl :create-token
  [db player-id effect _object-id]
  (let [token-def (:effect/token effect)
        player-eid (q/get-player-eid db player-id)
        obj-id (random-uuid)
        ;; Create a synthetic card entity for the token so that
        ;; creature?, has-keyword?, and card-based queries work.
        card-tempid -1
        card-tx (cond-> {:db/id card-tempid
                         :card/types (:token/types token-def)
                         :card/name (:token/name token-def)
                         :card/power (:token/power token-def)
                         :card/toughness (:token/toughness token-def)}
                  (:token/subtypes token-def)
                  (assoc :card/subtypes (:token/subtypes token-def))
                  (:token/colors token-def)
                  (assoc :card/colors (:token/colors token-def))
                  (:token/keywords token-def)
                  (assoc :card/keywords (:token/keywords token-def)))
        ;; Create the token object on the battlefield with creature fields
        obj-tx {:object/id obj-id
                :object/card card-tempid
                :object/zone :battlefield
                :object/owner player-eid
                :object/controller player-eid
                :object/tapped false
                :object/is-token true
                :object/power (:token/power token-def)
                :object/toughness (:token/toughness token-def)
                :object/summoning-sick true
                :object/damage-marked 0}]
    (d/db-with db [card-tx obj-tx])))
```

**Key pattern**:
- Defmethod signature: `[db player-id effect _object-id]`
- Reads effect data from `:effect/token effect`
- Creates TWO entities in one transaction: synthetic card (temp ID -1) + object
- Returns plain `db` (not tagged result)
- Object has creature fields already set: `:object/power`, `:object/toughness`, `:object/summoning-sick`, `:object/damage-marked`
- Object goes directly to `:object/zone :battlefield`

---

## 5. How Targeted Effects Read Their Target

**Pattern**: Via `:effect/target-ref` and `:stack-item/targets`

### Example: Crumble (destroy artifact)

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/green/crumble.cljs` (lines 33-36)

```clojure
:card/effects [{:effect/type :destroy
                :effect/target-ref :target-artifact}
               {:effect/type :gain-life-equal-to-cmc
                :effect/target-ref :target-artifact}]
```

### Target Resolution Flow

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/stack.cljs` (lines 78-105)

```clojure
(defn resolve-effect-target
  "Resolve symbolic targets in an effect to concrete entity/player IDs.

   Target resolution precedence:
   1. :effect/target-ref - look up in stored-targets map (cast-time targeting)
   2. :self - replace with source-id
   3. :controller - replace with controller
   4. :any-player - replace with stored player target
   5. anything else - pass through unchanged"
  [effect source-id controller stored-targets]
  (let [target-ref (:effect/target-ref effect)
        resolved-target (when target-ref (get stored-targets target-ref))]
    (cond
      resolved-target
      (assoc effect :effect/target resolved-target)

      (= :self (:effect/target effect))
      (assoc effect :effect/target source-id)

      (= :controller (:effect/target effect))
      (assoc effect :effect/target controller)

      (= :any-player (:effect/target effect))
      (if-let [p (:player stored-targets)]
        (assoc effect :effect/target p)
        effect)

      :else effect)))
```

### When It's Called

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/resolution.cljs` (lines 86-101)

```clojure
(defn- pre-resolve-targets
  "Pre-resolve all target references in an effects list.
   Uses stack/resolve-effect-target for standard resolution (:self, :controller,
   :any-player, :effect/target-ref), plus resolves :targeted-player via stored-targets.

   Arguments:
     effects-list   - Vector of effect maps
     source-id      - Object ID of the source spell/ability
     controller     - Player ID of the controller
     stored-targets - Map from :stack-item/targets (cast-time targeting data)"
  [effects-list source-id controller stored-targets]
  (mapv (fn [effect]
          (-> effect
              (resolve-targeted-player stored-targets)
              (stack/resolve-effect-target source-id controller stored-targets)))
        effects-list))
```

And in the :default resolution handler (lines 63-65):

```clojure
(let [resolved (stack/resolve-effect-target
                 effect source-id controller stored-targets)]
  (effects/execute-effect d controller resolved source-id))
```

### How Destroy Reads the Target

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/zones.cljs` (lines 99-106)

```clojure
(defmethod effects/execute-effect-impl :destroy
  [db _player-id effect _object-id]
  (let [target-id (:effect/target effect)]
    (if-not target-id
      db
      (if-let [_target-obj (q/get-object db target-id)]
        (zones/move-to-zone db target-id :graveyard)
        db))))
```

**Key pattern**:
1. Effect has `:effect/target-ref :target-artifact` in the card definition
2. Before execution, `pre-resolve-targets` calls `resolve-effect-target`, which converts `:effect/target-ref :target-artifact` to `:effect/target <concrete-object-id>` by looking up in `:stack-item/targets`
3. Effect executor reads `:effect/target effect` (now a concrete ID)
4. Executes the operation on that target

---

## 6. Zone Transitions: move-to-zone

**File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/zones.cljs` (lines 43-117)

### Signature

```clojure
(defn move-to-zone
  "Move a game object to a new zone. Pure function: (db, args) -> db

   Arguments:
     db - Datascript database value
     object-id - The :object/id of the object to move
     new-zone - The target zone keyword

   Returns:
     New db with object in new zone, or same db if already in that zone.

   Note: Caller must ensure object-id exists. If object doesn't exist,
   behavior is undefined (query fails). This is a programming error."
  [db object-id new-zone]
  (let [current-zone (:object/zone (q/get-object db object-id))]
    (if (= current-zone new-zone)
      db
      (let [obj-eid (q/get-object-eid db object-id)
            ;; Clean up stack-item when leaving the stack zone
            db (if (= current-zone :stack)
                 (if-let [si (stack/get-stack-item-by-object-ref db obj-eid)]
                   (stack/remove-stack-item db (:db/id si))
                   db)
                 db)
            ;; Retract Datascript trigger entities when leaving battlefield
            trigger-retract-txs
            (when (= current-zone :battlefield)
              (let [trigger-eids (d/q '[:find [?t ...]
                                        :in $ ?obj
                                        :where [?obj :object/triggers ?t]]
                                      db obj-eid)]
                (mapv (fn [teid] [:db.fn/retractEntity teid]) trigger-eids)))
            ;; Retract creature fields when leaving battlefield
            creature-leave-txs
            (when (= current-zone :battlefield)
              (let [obj (d/pull db [:object/power :object/toughness
                                    :object/summoning-sick :object/damage-marked
                                    :object/attacking :object/blocking] obj-eid)]
                (cond-> []
                  (some? (:object/power obj))
                  (conj [:db/retract obj-eid :object/power (:object/power obj)])
                  (some? (:object/toughness obj))
                  (conj [:db/retract obj-eid :object/toughness (:object/toughness obj)])
                  (some? (:object/summoning-sick obj))
                  (conj [:db/retract obj-eid :object/summoning-sick (:object/summoning-sick obj)])
                  (some? (:object/damage-marked obj))
                  (conj [:db/retract obj-eid :object/damage-marked (:object/damage-marked obj)])
                  (some? (:object/attacking obj))
                  (conj [:db/retract obj-eid :object/attacking (:object/attacking obj)])
                  (some? (:object/blocking obj))
                  (conj [:db/retract obj-eid :object/blocking (:object/blocking obj)]))))
            ;; Add creature fields when entering battlefield
            creature-enter-txs
            (when (= new-zone :battlefield)
              (let [card (d/pull db [{:object/card [:card/types :card/power :card/toughness]}] obj-eid)
                    card-data (:object/card card)
                    card-types (set (:card/types card-data))]
                (when (contains? card-types :creature)
                  [[:db/add obj-eid :object/power (:card/power card-data)]
                   [:db/add obj-eid :object/toughness (:card/toughness card-data)]
                   [:db/add obj-eid :object/summoning-sick true]
                   [:db/add obj-eid :object/damage-marked 0]])))
            ;; Reset tapped state when entering or leaving battlefield
            ;; Leaving: card loses memory of being tapped
            ;; Entering: permanents enter untapped per MTG rule 110.6
            base-txs (if (or (= current-zone :battlefield)
                             (= new-zone :battlefield))
                       [[:db/add obj-eid :object/zone new-zone]
                        [:db/add obj-eid :object/tapped false]]
                       [[:db/add obj-eid :object/zone new-zone]])
            txs (-> base-txs
                    (into trigger-retract-txs)
                    (into creature-leave-txs)
                    (into creature-enter-txs))]
        (d/db-with db txs)))))
```

**Key pattern**:
- Use `zones/move-to-zone db object-id :graveyard` to move card to graveyard
- Function builds a transaction vector with multiple txs:
  - Stack-item cleanup if leaving stack
  - Trigger retraction if leaving battlefield
  - Creature field retraction if leaving battlefield (power, toughness, summoning-sick, damage-marked, attacking, blocking)
  - Creature field initialization if entering battlefield
  - Zone update + tapped reset
- All applied atomically via `(d/db-with db txs)`

---

## 7. Creatures Test File

**File**: `/Users/abugosh/g/fizzle/src/test/fizzle/engine/creatures_test.cljs`

Test coverage:
- Base power/toughness (no modifiers)
- Non-creatures return nil
- +1/+1 counters add to P/T
- -1/-1 counters subtract from P/T
- PT-modifier grants add to P/T
- Multiple grants stack additively
- Self-static-ability modifiers (threshold)
- Static conditions not met
- Zone transitions set/clear creature fields
- Creature predicates (creature?, summoning-sick?, can-attack?, can-block?)
- Keyword system (has-keyword?, shroud targeting prevention)
- Defender, flying/reach blocking

**Key test pattern**:

```clojure
(defn- add-creature-to-battlefield
  "Add a creature card to battlefield with proper creature fields.
   Returns [db obj-id]."
  [db card-id owner]
  (let [[db obj-id] (th/add-card-to-zone db card-id :hand owner)
        ;; Move to battlefield through zone transition (will set creature fields)
        db (zones/move-to-zone db obj-id :battlefield)]
    [db obj-id]))
```

Tests use `add-creature-to-battlefield` helper rather than manually setting creature fields, reinforcing the pattern that creature fields are set/cleared by `move-to-zone`.

---

## Summary: What Gets SBA-Checked?

After `reduce-effects` completes (returns `{:db db}` with no more interactive effects), SBA would check:
1. Creatures with toughness ≤ 0 → move to graveyard
2. Objects with too much damage → move to graveyard
3. Trigger conditions met (e.g., creature death triggers) → create triggers
4. Game state win/loss conditions

This is where an SBA handler would hook in the resolution flow.
