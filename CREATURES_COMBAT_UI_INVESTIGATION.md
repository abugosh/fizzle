# Creature Display and Combat UI Investigation

**Date:** 2026-03-07
**Scope:** LARGE — Full creature/combat system across views, engine, events, and data layers
**Focus Areas:** Rendering, combat phases, selection mechanics, card data, UX patterns

---

## 1. Current Creature Rendering

### Battlefield View (`/Users/abugosh/g/fizzle/src/main/fizzle/views/battlefield.cljs`)

**Component Structure:**
- `battlefield-view` — Main entry point, 6-row mirrored layout (opponent top 3 rows, player bottom 3 rows)
- Each row shows: Lands | Other | Creatures (opponent) + Creatures | Other | Lands (player)
- Uses subscriptions `::battlefield` and `::opponent-battlefield` from subs/game.cljs
- Rows grouped by type: creatures, other, lands (via `sorting/group-by-type`)

**Permanent Display (`permanent-view` function, lines 102–152):**

Current rendered information:
- **Card name** (line 124)
- **Tapped indicator** — "(tapped)" label + visual rotation (6deg) (lines 125, 122)
- **Counters** — Displayed as list if present (lines 126–131)
  - Format: `{counter-type}: {count}` (e.g., "charge: 3")
  - Multiple counters shown as separate spans
- **Mana abilities** — Buttons for producing mana (lines 132–136)
  - Disabled if tapped
  - Color-coded per mana type
- **Activated abilities** — Buttons for non-mana activated abilities (lines 137–147)
  - Disabled if tapped
  - Truncated label if description > 15 chars
- **Granted abilities** — Special "Sac: {color}" buttons for granted mana abilities (lines 148–152)
  - Shows granted mana abilities with amber background

**NOT Currently Rendered:**
- Power/Toughness values (P/T)
- Combat damage marked (`:object/damage-marked`)
- Summoning sickness indicator
- Attacking/blocking status
- Keywords (flying, reach, trample, shroud, etc.)
- Any visual combat math

### Selection Card View (`/Users/abugosh/g/fizzle/src/main/fizzle/views/selection/common.cljs`)

Used in combat selection modals. Displays:
- **Card name only** (line 72 in `selection-card-view`)
- **Border color** — Type-based (creature/land/etc.) or accent if selected
- **Background** — Color identity based
- **Selection state** — Thick accent border if selected

---

## 2. Combat System State

### Database Schema (`/Users/abugosh/g/fizzle/src/main/fizzle/db/schema.cljs`)

**Creature-specific fields on `:object/` entities (lines 46–52):**
```clojure
:object/power           {}  ; integer — base power (from card definition)
:object/toughness       {}  ; integer — base toughness (from card definition)
:object/damage-marked   {}  ; integer — damage taken this turn (default 0)
:object/summoning-sick  {}  ; boolean — entered battlefield this turn
:object/attacking       {}  ; boolean — declared as attacker this combat
:object/blocking        {}  ; ref — eid of attacker being blocked
:object/is-token        {}  ; boolean — token creature
```

**Card definition fields (`/Users/abugosh/g/fizzle/src/main/fizzle/cards/green/nimble_mongoose.cljs`):**
```clojure
:card/power 1              ; Base power value
:card/toughness 1          ; Base toughness value
:card/keywords #{:shroud}  ; Keyword mechanics (shroud, flying, reach, trample, haste, defender, etc.)
:card/static-abilities [...]  ; P/T modifiers (e.g., threshold +2/+2)
```

### Combat Phases (via `engine/combat.cljs`)

**Stack-based flow** — Combat uses unified stack system with `:stack-item/type`:

1. **`:declare-attackers`** — Stack item that triggers attacker selection
   - Description: "Declare Attackers"
   - Entry: `combat/begin-combat` pushes this to stack
   - Resolved by: `resolve-stack-item :declare-attackers` (engine/resolution.cljs:301)

2. **`:declare-blockers`** — Stack item after attackers selected
   - Description: "Declare Blockers"
   - Created after attacker selection completes (events/selection/combat.cljs:39)
   - Resolved by: `resolve-stack-item :declare-blockers` (engine/resolution.cljs:314)

3. **`:combat-damage`** — Stack item for damage assignment
   - Description: "Combat Damage"
   - Created after attacker selection completes (events/selection/combat.cljs:35)
   - Resolved by: `resolve-stack-item :combat-damage` (engine/resolution.cljs:327)

**Combat functions in `engine/combat.cljs`:**

| Function | Inputs | Returns | Purpose |
|----------|--------|---------|---------|
| `has-creatures-on-battlefield?` | db | bool | Skip combat phase if no creatures exist |
| `get-eligible-attackers` | db, player-id | [obj-ids] | Get creatures that can attack (not sick, not tapped, not defender) |
| `tap-and-mark-attackers` | db, attacker-ids | db | Mark selected creatures as attacking + tapped |
| `get-attacking-creatures` | db | [obj-ids] | Get all currently attacking creatures |
| `get-eligible-blockers` | db, defender-id, attacker-id | [obj-ids] | Get creatures that can block (not tapped, not already blocking, flying/reach rules) |
| `mark-blockers` | db, blocker-ids, attacker-id | db | Link blockers to attacker via `:object/blocking` ref |
| `get-blockers-for-attacker` | db, attacker-id | [obj-ids] | Get all blockers assigned to an attacker |
| `mark-damage` | db, obj-id, amount | db | Add damage to creature's `:object/damage-marked` |
| `deal-damage-to-player` | db, player-id, amount | db | Reduce player life total |
| `assign-combat-damage-for-attacker` | db, attacker-id, defender-id | db | Handle one attacker's damage: unblocked → player, blocked → blockers (with trample) |
| `assign-blocker-damage-to-attacker` | db, blocker-id | db | Blocker deals damage back to attacker |
| `deal-combat-damage` | db, controller | db | Orchestrate all combat damage + clear attacking/blocking flags |
| `clear-combat-state` | db | db | Retract all `:object/attacking` and `:object/blocking` attributes |
| `begin-combat` | db, active-player-id | db | Push `:declare-attackers` stack item if creatures exist |

---

## 3. Selection System for Combat

### Combat Selection Types (`events/selection/combat.cljs`)

**Attacker Selection (`:select-attackers`)**
- **Builder:** `build-attacker-selection` (lines 9–21)
- **Type:** `:select-attackers`
- **Lifecycle:** `:finalized` (no chaining)
- **Validation:** `:at-most` — can select 0 or more attacking creatures
- **Executor:** `execute-confirmed-selection :select-attackers` (lines 24–42)
  - Removes declare-attackers stack item
  - If any attackers selected: marks them as attacking, creates `:declare-blockers` and `:combat-damage` stack items
  - Returns `{:db db}` (no chaining)

**Blocker Selection (`:assign-blockers`)**
- **Builder:** `build-blocker-selection` (lines 45–61)
- **Type:** `:assign-blockers`
- **Lifecycle:** `:chaining` — one selection per attacker
- **Validation:** `:at-most` — can select 0 or more blockers per attacker
- **Flow:**
  - Starts with first attacker from attacking creatures
  - Stores `current-attacker`, `remaining-attackers`, eligible blockers
  - Auto-confirms if no eligible blockers (line 61)
  - Executor: `execute-confirmed-selection :assign-blockers` (lines 64–70) marks blockers
  - Chaining: `build-chain-selection :assign-blockers` (lines 73–79) builds next selection for remaining attackers

**UI Display for Combat (via `views/modals.cljs` lines 99–104):**
```clojure
(defmethod render-selection-modal :select-attackers [s c]
  (object-target s c :battlefield "Attackers selected" "Select creatures to attack with"))

(defmethod render-selection-modal :assign-blockers [s c]
  (object-target s c :battlefield "Blockers assigned" "Select blockers (or confirm with none)"))
```

Both use `object-target-modal` which:
- Shows zone name ("Battlefield")
- Lists cards via `selection-card-view` (only card names)
- Allows toggle-select (multi-select)
- Shows validity label based on selection count

---

## 4. Creature Card Data

### Card Definition Structure

Example: Nimble Mongoose (`cards/green/nimble_mongoose.cljs`)
```clojure
{:card/id :nimble-mongoose
 :card/name "Nimble Mongoose"
 :card/cmc 1
 :card/mana-cost {:green 1}
 :card/colors #{:green}
 :card/types #{:creature}
 :card/subtypes #{:mongoose}
 :card/power 1              ; ← P/T defined here
 :card/toughness 1
 :card/keywords #{:shroud}  ; ← Keywords here
 :card/text "Shroud\nThreshold — Nimble Mongoose gets +2/+2."
 :card/static-abilities [{:static/type :pt-modifier
                          :modifier/power 2
                          :modifier/toughness 2
                          :modifier/condition {:condition/type :threshold}
                          :modifier/applies-to :self}]}
```

### P/T Computation (`engine/creatures.cljs`)

**Effective Power & Toughness** — Never stored, always recomputed:

```clojure
(defn effective-power [db object-id]
  (when (creature? db object-id)
    (let [obj (q/get-object db object-id)
          base (or (:object/power obj) 0)
          counters (or (:object/counters obj) {})
          counter-mod (- (get counters :+1/+1 0) (get counters :-1/-1 0))
          pt-grants (grants/get-grants-by-type db object-id :pt-modifier)
          grant-mod (reduce #(+ %1 (or (get-in %2 [:grant/data :grant/power]) 0)) 0 pt-grants)
          obj-eid (q/get-object-eid db object-id)
          static-mods (static/get-self-pt-modifiers db obj-eid)
          static-mod (reduce #(+ %1 (:power %2)) 0 static-mods)]
      (+ base counter-mod grant-mod static-mod))))
```

**Factors in order:**
1. Base power from `:object/power`
2. Counter-based modifications (+1/+1, -1/-1)
3. Grant-based modifications (temporary P/T boosts)
4. Static ability modifications (e.g., threshold condition)

**Same for toughness** — identical structure, sums `:grant/toughness` and `(:toughness m)` instead.

### Keyword Support (`engine/creatures.cljs` lines 59–67)

**Keywords checked in predicates:**
```clojure
(defn has-keyword? [db object-id kw]
  ; Returns true if card has keyword OR granted keywords
  ; Checks both (:card/keywords card) and granted keyword grants
```

**Currently implemented keywords:**
- `:haste` — affects summoning-sick check (lines 75–76)
- `:flying` — affects blocker eligibility (lines 97–99)
- `:reach` — can block flying (line 99)
- `:defender` — can't attack (line 87)
- `:trample` — excess damage passes through to player (combat.cljs:156)
- `:shroud` — Cannot be targeted (implemented via grants in test files)

**Keywords in card definitions** (from schema line 28 and Nimble Mongoose):
```clojure
:card/keywords {:db/cardinality :db.cardinality/many}  ; #{:storm :threshold}
```

---

## 5. Views Structure

### Component Hierarchy

```
game-view (main)
├── phase-bar-section
│   └── phase-column (for each phase)
│       └── stop-dot (x2 per phase)
├── battlefield-view
│   ├── opponent-battlefield
│   │   └── permanent-row (×3 for lands, other, creatures)
│   │       └── permanent-view (×N for each object)
│   │           ├── mana-button (×N)
│   │           ├── ability-button (×N)
│   │           └── granted-ability-button (×N)
│   └── player-battlefield
│       └── permanent-row (×3)
│           └── permanent-view (×N)
│               └── [same buttons as opponent]
├── selection-modal (when active)
│   └── render-selection-modal (dispatched by selection type)
│       ├── :select-attackers → object-target-modal
│       ├── :assign-blockers → object-target-modal
│       └── [other selection types...]
│           └── selection-card-view (×N)
└── [other views: hand, graveyard, stack, etc.]
```

### Sorting & Grouping

**Battlefield data flow:**
1. `subs/game.cljs:215–236` — Subscribe to battlefield, group by type
2. `engine/sorting.cljs:37–47` — `group-by-type` splits objects into `:creatures`, `:lands`, `:other`
3. `engine/sorting.cljs:4–12` — `sort-cards` sorts within groups by CMC → lands-first → name
4. `views/battlefield.cljs:233–248` — Render grouped/sorted objects

---

## 6. Current P/T Display Status

### EXPLICIT: What IS Shown
- **Counters** — Displayed as text list (e.g., "+1/+1: 3")
- **Card name** — Always shown
- **Tapped state** — Label + visual rotation
- **Mana abilities** — Buttons (if present)
- **Activated abilities** — Buttons (if present)

### MISSING: What IS NOT Shown
- **Base P/T values** — No "1/1" shown anywhere
- **Effective P/T** — No calculation displayed
- **Combat damage** — No "2 damage" indicator
- **Summoning sickness** — No visual indicator
- **Attacking/blocking status** — No "attacking" or "blocking" label during combat
- **Keywords** — No "Flying", "Reach", "Trample" badges
- **Combat math** — No "this deals 5 damage" or "needs 3 to die" calculations
- **Animation** — Creatures don't animate to attack/block zones

---

## 7. Key Architecture Insights

### Data Storage Pattern
- **Power/Toughness:** Stored on `:object/` as base, computed dynamically via `creatures/effective-*`
- **Combat state:** Stored as `:object/attacking` (boolean), `:object/blocking` (ref to attacker eid)
- **Damage:** Stored as `:object/damage-marked` (integer), cleared at end of turn

### Computation Pattern
- **P/T is always computed on-demand** — No materialized updated values
- **Effects create grants** — Temporary modifiers stored in `:object/grants` vector
- **SBAs handle death** — State-based actions check toughness vs damage (engine/state_based.cljs)

### UI Pattern
- **permanent-view is generic** — Works for any permanent type, optional `show-buttons?`
- **Combat uses selection system** — Chaining selections for multi-attacker scenarios
- **Selection modals are modal** — Block all other input until confirmed

### Combat Flow
1. **Begin combat** → Push `:declare-attackers` stack item
2. **Resolve `:declare-attackers`** → `resolve-stack-item` returns `:needs-attackers` signal
3. **Game event handler** → Checks bot/human, triggers selection if human
4. **Selection → Confirm** → Creates `:declare-blockers` + `:combat-damage` stack items
5. **Resolve `:declare-blockers`** → Returns `:needs-blockers` if attackers exist
6. **Blocker selection (chaining)** → One selection per attacker
7. **Resolve `:combat-damage`** → Assigns damage + clears flags

---

## 8. Gaps & Missing Infrastructure

### Critical Missing UX Elements
1. **P/T Display** — No "3/2" shown on creature cards at all
2. **Combat Indicators** — No visual hint that creature is attacking/blocking
3. **Damage Counter** — No "1" damage badge on creatures during combat
4. **Combat Math** — No preview of damage before confirming blockers
5. **Keyword Badges** — No "Flying", "Trample", etc. visual indicators

### Selection Modal Gaps
- Combat modals show **only card names** in `selection-card-view`
- No ability to see P/T in the blocker selection modal
- No damage preview during blocker selection
- Can't tell which creatures are flying vs ground-based

### View Component Gaps
- No `creature-card-view` component (separate from generic permanent-view)
- No P/T badge component
- No damage indicator component
- No keyword badge component
- No combat status indicator component

### Event/Signal Gaps
- Combat selections don't pass context about which attacker is being blocked
- No visual indication of which attackers are unblocked
- Selection modal doesn't show attack/block relationships

---

## 9. Design Opportunities

### Minimal Changes (High Impact)
1. **Add P/T to permanent-view** — Display "3/2" under card name (1 line)
2. **Add damage counter to permanent-view** — Show "1" damage badge if `:object/damage-marked > 0` (1 line)
3. **Add summoning sickness indicator** — Gray out or badge creatures with `:object/summoning-sick true` (3 lines)
4. **Add combat status badges** — Show "⚔" if attacking, "🛡" if blocking (3 lines)

### Medium Changes (Better UX)
1. **Add keyword indicators** — Small badges for flying, reach, trample, etc. (10-15 lines)
2. **Enhanced blocker selection modal** — Show P/T + damage state for each creature (20-30 lines)
3. **Combat math preview** — Show "3 damage" on attacker when blockers assigned (15-20 lines)

### Larger Changes (Full Integration)
1. **Combat zone layout** — Separate attack formation from resting creatures (30-40 lines of refactoring)
2. **Animation layer** — Creatures move to attack zone, deal damage indicators (50+ lines)
3. **Keyword system UI** — Comprehensive badge/icon system for all keywords (100+ lines)

---

## 10. Summary Table

| Aspect | Current State | Data Available | Missing |
|--------|---------------|-----------------|---------|
| **P/T Display** | None | `:object/power`, `:object/toughness`, computed via `effective-power`/`effective-toughness` | Display component, view rendering |
| **Damage Tracking** | Stored in `:object/damage-marked` | Field exists, used in combat.cljs | Display component, visual indicator |
| **Combat Status** | Stored in `:object/attacking`, `:object/blocking` | Fields exist, queried for blockers | Display label, visual state |
| **Summoning Sickness** | Stored in `:object/summoning-sick` | Field exists, checked in `can-attack?` | Display indicator |
| **Keywords** | Stored on card, queryable via `has-keyword?` | `:card/keywords`, grant system | Visual badges, comprehensive coverage |
| **Selection Modal** | Only shows card names | All object data available | P/T display, damage preview, context hints |
| **Attack Formation** | All on one row | Could filter by `:object/attacking` | Separate visual zone, animation |

---

## Files Consulted

**Views:**
- `/Users/abugosh/g/fizzle/src/main/fizzle/views/battlefield.cljs` — Main permanent rendering
- `/Users/abugosh/g/fizzle/src/main/fizzle/views/modals.cljs` — Combat modal dispatch
- `/Users/abugosh/g/fizzle/src/main/fizzle/views/selection/custom.cljs` — object-target-modal
- `/Users/abugosh/g/fizzle/src/main/fizzle/views/selection/common.cljs` — selection-card-view
- `/Users/abugosh/g/fizzle/src/main/fizzle/views/card_styles.cljs` — Styling helpers

**Engine:**
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/combat.cljs` — Combat logic
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/creatures.cljs` — P/T computation, keywords
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/resolution.cljs` — Combat stack item resolution
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/sorting.cljs` — Grouping/sorting

**Events:**
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/combat.cljs` — Combat selections
- `/Users/abugosh/g/fizzle/src/main/fizzle/events/game.cljs` — Combat phase trigger

**Data:**
- `/Users/abugosh/g/fizzle/src/main/fizzle/db/schema.cljs` — Creature fields
- `/Users/abugosh/g/fizzle/src/main/fizzle/subs/game.cljs` — Battlefield subscription
- `/Users/abugosh/g/fizzle/src/main/fizzle/cards/green/nimble_mongoose.cljs` — Creature example

**Tests:**
- `/Users/abugosh/g/fizzle/src/test/fizzle/engine/combat_test.cljs` — Combat system tests
