# Bot Decision Loop: Complete Technical Trace

**Document**: Technical reference for tracing bot actions through the director loop
**Files**: director.cljs, decisions.cljs, protocol.cljs, definitions.cljs, rules.cljs, mana_activation.cljs

---

## Scenario: Burn Bot T1 Main Phase

Initial state:
- Bot on T1, main1 phase
- Hand: 7 cards including ~3 Lightning Bolts, ~3 Mountains
- Battlefield: empty
- Stack: empty
- Priority: Bot

---

## Step 1: Director Loop Entry

**File**: `events/director.cljs:290-358`

```clojure
(defn run-to-decision [app-db opts]
  (let [human-pid game-state/human-player-id
        yield-all-requested? (boolean (:yield-all? opts))
        game-db (:game/db app-db)]
    (loop [app-db app-db
           yield-all? yield-all-init?
           yield-through-stack? yield-through-stack-init?
           human-yielded? human-yielded-init?
           steps 0]
      (cond
        (>= steps 300) → RETURN :safety-limit
        (:game/pending-selection app-db) → RETURN :pending-selection
        :else
        (let [game-db (:game/db app-db)]
          (cond
            (nil? game-db) → RETURN :await-human
            (:game/loss-condition ...) → RETURN :game-over
            :else
            (let [holder-pid (current-holder-player-id game-db)]
              (cond
                (bot-protocol/get-bot-archetype game-db holder-pid)
                → (step-bot-action app-db game-db holder-pid ...)
                (= holder-pid human-pid)
                → (step-human-action ...)
                :else
                → RETURN :await-human))))))))
```

**Iteration 1** (steps=0):
- `holder-pid = :opponent` (bot, from priority holder)
- `(bot-protocol/get-bot-archetype game-db :opponent) = :burn`
- Call `(step-bot-action app-db game-db :opponent ...)`

---

## Step 2: Bot Action Step

**File**: `events/director.cljs:176-214`

```clojure
(defn- step-bot-action [app-db game-db holder-pid yield-all? yield-through-stack?]
  (let [action (bot-act game-db holder-pid)
        atype (:action-type action)]
    (cond
      (= atype :play-land)
      {:continue (assoc app-db :game/db (:game-db action))
       :yield-all? yield-all? :yield-through-stack? yield-through-stack?}

      (and (= atype :cast-spell) (:pending-selection action))
      {:done {:app-db (assoc app-db :game/pending-selection ...)
              :reason :pending-selection}}

      (= atype :cast-spell)
      (let [passed-db (priority/yield-priority (:game-db action) ...)]
        {:continue (assoc app-db :game/db passed-db) ...})

      :else ; :pass
      (let [passed-db (priority/yield-priority game-db holder-eid)
            all-passed? (priority/both-passed? passed-db)]
        (if-not all-passed?
          {:continue (assoc app-db :game/db (priority/transfer-priority passed-db ...))}
          (let [reset-db (-> passed-db
                             priority/reset-passes
                             (priority/set-priority-holder active-eid))]
            (if-not (queries/stack-empty? reset-db)
              (step-resolve-stack app-db reset-db ...)
              (step-advance-phase app-db reset-db ...))))))))
```

**Call**: `(bot-act game-db :opponent)`

---

## Step 3: Bot Action Decision

**File**: `events/director.cljs:90-135`

```clojure
(defn bot-act [game-db player-id]
  (let [archetype (bot-protocol/get-bot-archetype game-db player-id)]
    ;; archetype = :burn
    
    (if-not archetype
      {:action-type :pass :game-db game-db}
      (let [game-state (queries/get-game-state game-db)
            current-phase (:game/phase game-state)  ;; :main1
            phase-action (bot-protocol/bot-phase-action archetype current-phase game-db player-id)]
        ;; phase-action = {:action :play-land}
        
        (let [land-id (when (= :play-land (:action phase-action))
                        (find-bot-land-to-play game-db player-id))]
          ;; land-id = <uuid of Mountain in hand>
          
          (if land-id
            ;; BRANCH A: Play land
            {:action-type :play-land
             :game-db (sba/check-and-execute-sbas (lands/play-land game-db player-id land-id))
             :object-id land-id}
            
            ;; BRANCH B: No land to play, try casting
            (let [action (bot-decisions/bot-decide-action game-db)]
              (if (not= :cast-spell (:action action))
                {:action-type :pass :game-db game-db}
                ;; BRANCH C: Cast spell
                (let [tap-seq (:tap-sequence action)
                      db-tapped (reduce (fn [d {:keys [object-id mana-color]}]
                                          (mana-activation/activate-mana-ability
                                            d player-id object-id mana-color))
                                        game-db tap-seq)
                      cast-result (casting/cast-spell-handler
                                    {:game/db db-tapped}
                                    {:player-id player-id
                                     :object-id (:object-id action)
                                     :target (:target action)})]
                  (cond
                    (:game/pending-selection cast-result)
                    {:action-type :cast-spell
                     :game-db db-tapped
                     :pending-selection (:game/pending-selection cast-result)}
                    
                    (identical? (:game/db cast-result) db-tapped)
                    {:action-type :pass :game-db game-db}
                    
                    :else
                    {:action-type :cast-spell
                     :game-db (sba/check-and-execute-sbas (:game/db cast-result))}))))))))
```

**Sequence T1 Main1**:
1. Get archetype `:burn`
2. Get phase-action for `:main1` → `{:action :play-land}`
3. Find land to play → finds Mountain
4. **RETURN** `{:action-type :play-land :game-db db' :object-id <uuid>}`

---

## Step 4: Update Director State

Back in `step-bot-action` (line 181):
```clojure
(= atype :play-land)
→ {:continue (assoc app-db :game/db (:game-db action))
             :yield-all? ... :yield-through-stack? ...}
```

**Return to director loop**:
```clojure
(recur (:continue step-result)
       (:yield-all? step-result)
       (:yield-through-stack? step-result)
       human-yielded?
       (inc steps))  ;; steps = 1
```

---

## Step 5: Second Iteration (After Land Play)

**Iteration 2** (steps=1):

Game state now:
- Battlefield: Mountain (tapped)
- Hand: ~2 Lightning Bolts, ~2 Mountains
- Phase: still main1
- Priority: still bot
- Stack: empty

**Loop body**:
- `holder-pid = :opponent` (bot still has priority)
- `(bot-protocol/get-bot-archetype game-db :opponent) = :burn`
- Call `(step-bot-action app-db game-db :opponent ...)`

**In bot-act**:
1. Phase-action for `:main1` → `{:action :play-land}`
2. Find land to play → no untapped Mountains left (just played the only one)
3. **land-id = nil**
4. Call `(bot-decisions/bot-decide-action game-db)`

---

## Step 6: Bot Casting Decision

**File**: `bots/decisions.cljs:96-132`

```clojure
(defn bot-decide-action [game-db]
  (let [holder-eid (priority/get-priority-holder-eid game-db)
        player-id (some (fn [pid] ...) [human-player-id opponent-player-id])
        archetype (when player-id (bot/get-bot-archetype game-db player-id))]
    ;; player-id = :opponent, archetype = :burn
    
    (if-not archetype
      {:action :pass}
      (let [decision (bot/bot-priority-decision archetype {:db game-db :player-id player-id})]
        ;; decision = bot/bot-priority-decision(:burn, {:db ..., :player-id :opponent})
        
        (if (= :pass decision)
          {:action :pass}
          ;; BRANCH: decision is action map {:action :cast-spell :object-id ... :target ...}
          (let [object-id (:object-id decision)
                target (:target decision)
                card (queries/get-card game-db object-id)
                mana-cost (or (:card/mana-cost card) {})  ;; {:red 1} for Lightning Bolt
                tap-seq (find-tap-sequence game-db player-id mana-cost)
                can-pay? (every? (fn [[color amount]] ...) mana-cost)]
            (if-not can-pay?
              {:action :pass}
              {:action :cast-spell
               :object-id object-id
               :target target
               :player-id player-id
               :tap-sequence tap-seq})))))))
```

### Substep: Bot Priority Decision

**File**: `bots/protocol.cljs:26-34`

```clojure
(defn bot-priority-decision [archetype context]
  (let [spec (definitions/get-spec archetype)]
    ;; spec = burn-spec
    (if (and spec (:db context) (:player-id context))
      (rules/match-priority-rule (:bot/priority-rules spec) context)
      :pass)))
```

**Burn spec** (from `bots/definitions.cljs:20-31`):
```clojure
{:bot/priority-rules
 [{:rule/mode :auto
   :rule/conditions [{:check :zone-contains :zone :hand :player :self :card-id :lightning-bolt}
                     {:check :has-untapped-source :color :red}
                     {:check :stack-empty}]
   :rule/action {:action :cast-spell :card-id :lightning-bolt :target :opponent}}]}
```

### Substep: Rule Matching

**File**: `bots/rules.cljs:146-157`

```clojure
(defn match-priority-rule [rules context]
  (if (seq rules)
    (or (some (fn [rule]
                (when (and (not= :interactive (:rule/mode rule))
                           (evaluate-conditions (:rule/conditions rule) context))
                  (resolve-action (:rule/action rule) context)))
              rules)
        :pass)
    :pass))
```

**Rule 1**: `:zone-contains :hand :card-id :lightning-bolt`
- **File**: `bots/rules.cljs:33-40`
- Check hand for Lightning Bolt card
- Return: true (at least one bolt in hand)

**Rule 2**: `:has-untapped-source :color :red`
- **File**: `bots/rules.cljs:63-76`
- Check battlefield for untapped land producing red
- Return: true (Mountain on battlefield, untapped)

**Rule 3**: `:stack-empty`
- **File**: `bots/rules.cljs:58-60`
- Check if stack is empty
- Return: true (stack is empty)

**All conditions true** → Resolve action

### Substep: Action Resolution

**File**: `bots/rules.cljs:119-143`

```clojure
(defn resolve-action [action-template context]
  (let [db (:db context)
        player-id (:player-id context)
        card-id (:card-id action-template)
        hand (queries/get-objects-in-zone db player-id :hand)
        obj (when card-id
              (first (filter #(= card-id (get-in % [:object/card :card/id])) hand)))]
    (if (and card-id (nil? obj))
      :pass
      (let [target-ref (:target action-template)
            resolved-target (case target-ref
                              :opponent (queries/get-other-player-id db player-id)
                              :self player-id
                              target-ref)]
        (if (and (= :opponent target-ref) (nil? resolved-target))
          :pass
          (cond-> {:action (:action action-template)}
            obj (assoc :object-id (:object/id obj))
            resolved-target (assoc :target resolved-target)))))))
```

**Template**: `{:action :cast-spell :card-id :lightning-bolt :target :opponent}`
- Find Lightning Bolt in hand → obj = <object with :card/id :lightning-bolt>
- Resolve :opponent to human player-id
- **Return**: `{:action :cast-spell :object-id <uuid> :target <human-pid>}`

---

## Step 7: Mana Allocation

Back in `bot-decide-action` (line 107):
```clojure
(let [decision (bot/bot-priority-decision archetype {:db game-db :player-id player-id})]
  ;; decision = {:action :cast-spell :object-id <bolt> :target <human>}
  (let [object-id (:object-id decision)
        target (:target decision)
        card (queries/get-card game-db object-id)
        mana-cost (or (:card/mana-cost card) {})  ;; {:red 1}
        tap-seq (find-tap-sequence game-db player-id mana-cost)]
```

### find-tap-sequence

**File**: `bots/decisions.cljs:39-93`

```clojure
(defn find-tap-sequence [game-db player-id mana-cost]
  ;; mana-cost = {:red 1}
  (let [battlefield (queries/get-objects-in-zone game-db player-id :battlefield)
        ;; battlefield = [<Mountain object>]
        allocated-ids (volatile! #{})]
    
    ;; PHASE 1: Colored mana
    (let [colored-entries (filter (fn [[color _]] (color-keys color)) mana-cost)
          ;; colored-entries = [[:red 1]]
          colored-taps (reduce
                         (fn [taps [color amount]]
                           ;; color = :red, amount = 1
                           (let [lands (find-lands
                                         (fn [obj]
                                           (some (fn [ability]
                                                   (and (= :mana (:ability/type ability))
                                                        (get (:ability/produces ability) color)))
                                                 (get-in obj [:object/card :card/abilities])))
                                         amount)]
                             ;; lands = [<Mountain>]
                             (doseq [obj lands]
                               (vswap! allocated-ids conj (:object/id obj)))
                             ;; allocated-ids = #{<mountain-uuid>}
                             (into taps (map (fn [obj]
                                               {:object-id (:object/id obj)
                                                :mana-color color})
                                             lands))))
                         [] colored-entries)]
          ;; colored-taps = [{:object-id <mountain-uuid> :mana-color :red}]
    
    ;; PHASE 2: Generic mana
    (if (pos? generic-amount)  ;; generic-amount = 0
      ...
      colored-taps)  ;; Return colored-taps (no generic to pay)
    ;; RETURN: [{:object-id <mountain-uuid> :mana-color :red}]
```

**Return**: `{:object-id <mountain-uuid> :mana-color :red}`

### Mana Affability Check

Back in `bot-decide-action` (line 119):
```clojure
(let [can-pay? (every?
                 (fn [[color amount]]
                   (if (= :generic color)
                     (let [colored-need (reduce + 0 (vals (dissoc mana-cost :generic)))]
                       (>= (- (count tap-seq) colored-need) amount))
                     (<= amount (count (filter #(= color (:mana-color %)) tap-seq)))))
                 mana-cost)]
  ;; For mana-cost = {:red 1}:
  ;; Check: (:red 1) → count taps matching :red ≥ 1
  ;; tap-seq = [{:object-id ... :mana-color :red}]
  ;; count = 1 → 1 >= 1 → true
  ;; can-pay? = true
  (if-not can-pay?
    {:action :pass}
    {:action :cast-spell
     :object-id <bolt-uuid>
     :target <human-pid>
     :player-id :opponent
     :tap-sequence [{:object-id <mountain-uuid> :mana-color :red}]}))
```

---

## Step 8: Mana Activation (Inline Tapping)

Back in `bot-act` (line 110):
```clojure
(let [tap-seq (:tap-sequence action)
      ;; tap-seq = [{:object-id <mountain-uuid> :mana-color :red}]
      db-tapped (reduce (fn [d {:keys [object-id mana-color]}]
                          (mana-activation/activate-mana-ability
                            d player-id object-id mana-color))
                        game-db tap-seq)]
```

### activate-mana-ability

**File**: `engine/mana_activation.cljs:17-131`

```clojure
(defn activate-mana-ability [db player-id object-id mana-color]
  ;; db, :opponent, <mountain-uuid>, :red
  (if-not (priority/in-priority-phase? (:game/phase (q/get-game-state db)))
    db
    (let [obj (q/get-object db object-id)
          player-eid (q/get-player-eid db player-id)]
      (if (and obj player-eid
               (= (:object/zone obj) :battlefield)
               (= (:db/id (:object/controller obj)) player-eid))
        ;; Mountain is on battlefield, controlled by :opponent
        (let [card (:object/card obj)
              card-abilities (:card/abilities card)
              ;; Mountain has: [{:ability/type :mana :ability/produces {:red 1} :ability/cost [...]}]
              override-grants (filterv #(= :land-type-override (:grant/type %)) (:object/grants obj))
              effective-abilities (if (seq override-grants) ... card-abilities)
              all-mana-abilities (filter #(= :mana (:ability/type %)) effective-abilities)
              ;; all-mana-abilities = [{:ability/type :mana :ability/produces {:red 1} ...}]
              matching-ability (when mana-color
                                 (first (filter
                                          (fn [ability]
                                            (let [produces (:ability/produces ability)]
                                              (or (and produces (contains? produces mana-color))
                                                  (and produces (contains? produces :any)))))
                                          all-mana-abilities)))
              ;; matching-ability = Mountain's mana ability
              mana-ability (or matching-ability (first all-mana-abilities))]
          (if (and mana-ability
                   (abilities/can-activate? db object-id mana-ability))
            ;; Mountain mana ability can be activated
            (let [db-after-costs (abilities/pay-all-costs db object-id (:ability/cost mana-ability))]
              ;; Taps Mountain, etc.
              (if db-after-costs
                (let [produces (:ability/produces mana-ability)  ;; {:red 1}
                      db-after-produces (if produces
                                          (let [resolved-mana (if-let [any-count (:any produces)]
                                                                {mana-color any-count}
                                                                produces)]
                                            (effects/execute-effect db-after-costs player-id
                                                                    {:effect/type :add-mana
                                                                     :effect/mana resolved-mana}))
                                          db-after-costs)
                      ;; Add {:red 1} to mana pool
                      db-after-effects (reduce ... db-after-produces ...)
                      db-after-triggers (dispatch/dispatch-event db-after-effects ...)]
                  db-after-triggers)
                db))
            db))
        db))))
```

**Return**: db with Mountain tapped, 1 red mana in pool

**Back in bot-act** (line 115):
```clojure
(let [db-tapped (reduce ...) ;; db with Mountain tapped, red mana in pool
      cast-result (casting/cast-spell-handler
                    {:game/db db-tapped}
                    {:player-id :opponent
                     :object-id <bolt-uuid>
                     :target <human-pid>})]
```

---

## Step 9: Cast Spell

**File**: `events/casting.cljs` (not shown here, but outcome is:)

Result:
```clojure
{:game/db db-cast  ;; Bolt on stack, mana pool emptied, spell copied to stack
 ...}
```

Back in `bot-act` (line 120):
```clojure
(cond
  (:game/pending-selection cast-result) → return selection
  (identical? (:game/db cast-result) db-tapped) → return pass (cast failed)
  :else
  {:action-type :cast-spell
   :game-db (sba/check-and-execute-sbas (:game/db cast-result))})
```

**Return**: `{:action-type :cast-spell :game-db db-sba-checked}`

---

## Step 10: Handle Cast in Director

Back in `step-bot-action` (line 191):
```clojure
(= atype :cast-spell)
(let [passed-db (priority/yield-priority
                  (:game-db action)
                  (priority/get-priority-holder-eid (:game-db action)))]
  {:continue (assoc app-db :game/db passed-db)
   :yield-all? yield-all? :yield-through-stack? yield-through-stack?})
```

**Priority yielded to opponent** (human)

**Return to director loop**:
```clojure
(recur (:continue step-result)
       (:yield-all? step-result)
       ...
       (inc steps))  ;; steps = 2
```

---

## Step 11: Third Iteration (Opponent's Turn)

**Iteration 3** (steps=2):

Game state:
- Battlefield: Mountain (tapped)
- Hand: ~1-2 Lightning Bolts left
- Stack: Lightning Bolt (bot cast, awaiting resolution)
- Priority: human

**Loop body**:
- `holder-pid = :human` (from priority holder)
- Call `(step-human-action app-db game-db :human ...)`

**Human step**:
- Check if human should auto-pass
- If not: RETURN `:await-human` (waiting for input)
- If yes: opponent passes → both passed? → resolve stack item

---

## Summary: Full Trace for One Bot Cast

| Step | Function | Location | Input | Output | DB Change |
|------|----------|----------|-------|--------|-----------|
| 1 | `run-to-decision` | director:290 | app-db | - | - |
| 2 | `step-bot-action` | director:176 | app-db, game-db | action | - |
| 3 | `bot-act` | director:90 | game-db, :opponent | {:action-type :cast-spell :game-db ...} | - |
| 4a | (phase action) | director:98-100 | :main1 | land-id ✓ | Land played |
| 4b | (play land) | director:105 | land-id | {:action-type :play-land ...} | 1 Mountain tapped |
| 5 | `bot-decide-action` | decisions:96 | game-db | {:action :cast-spell ...} | - |
| 6 | `bot-priority-decision` | protocol:26 | :burn, context | decision map | - |
| 7 | `match-priority-rule` | rules:146 | burn rules | action map | - |
| 8 | `find-tap-sequence` | decisions:39 | game-db, {:red 1} | [{:object-id :mana-color :red}] | - |
| 9 | `activate-mana-ability` | mana-act:17 | game-db, mountain | db-tapped | Mountain tapped, red mana added |
| 10 | `cast-spell-handler` | casting | {:game/db db-tapped} | {:game/db db-cast} | Bolt on stack |
| 11 | `check-and-execute-sbas` | sba | db-cast | db-sba | SBAs executed |
| 12 | `yield-priority` | priority | db-sba | db-yielded | Priority moved to human |
| 13 | (recur) | director:353 | (loop again) | - | Continue director loop |

---

## Key Takeaways

1. **Director is the sole orchestrator** — all bot decisions driven from director loop
2. **bot-act is pure and testable** — no side effects, returns action + new game-db
3. **Mana allocation happens synchronously** — tap sequence built, then lands tapped inline before casting
4. **No race conditions** — all state mutations visible within current loop iteration
5. **Safety limit is 300 steps** — much higher than old 20 actions, appropriate for sync architecture
6. **Priority and pass handling is clean** — director manages all pass/yield logic
