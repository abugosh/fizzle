# Spec Validation Gaps Investigation — April 2026

## Executive Summary

Fizzle has spec validation at **4 chokepoints** covering cards, selections, stack items, and bot actions. However, **7 critical data boundaries** lack any validation despite representing substantial surface area for bugs:

1. **Game state initialization** (players, mana pools, grants)
2. **Mana pool operations** (color map keys/values)
3. **Trigger/ability data** (card trigger definitions flowing into Datascript)
4. **Grant maps** (temporary ability/cost/restriction data)
5. **History entry data** (pending-entry, deferred-entry maps)
6. **Continuation system** (effect chains via `:then` protocol)
7. **Object creation** (restoration during snapshot/fork, creature fields)

Each gap represents a **HIGH** bug risk because:
- Data crosses module boundaries without validation
- Consumers depend on specific keys being present/correct
- Typos/missing fields cause silent failures
- No compile-time verification (pure runtime data)

---

## Current Validation Coverage

### Existing Specs (4 chokepoints)

| Chokepoint | File | Validation Point | Coverage |
|-----------|------|------------------|----------|
| **Card Loading** | `engine/card_spec.cljs` | `events/init.cljs:107` | 40 defmethods covering all effect types, abilities, triggers |
| **Selection Creation** | `events/selection/spec.cljs` | `selection/core.cljs:426` | 31 defmethods per selection type (zone-pick, accumulator, reorder, etc.) |
| **Stack Item Creation** | `engine/stack_spec.cljs` | `engine/stack.cljs:create-stack-item` | 15+ defmethods per stack-item type (spell, trigger, combat marker) |
| **Bot Action** | `bots/action_spec.cljs` | `bots/decisions.cljs` | 3 defmethods (pass, cast-spell, play-land) |

All 4 use **dev-only validation** (dead-code eliminated in release via `goog.DEBUG`).

---

## Unvalidated Data Boundaries (HIGH RISK)

### 1. Game State Initialization — Player & Mana Pool Creation

**File:** `db/game_state.cljs`

**Data Creation Points:**

```clojure
; Line 30-32: empty-mana-pool constant
{:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}

; Line 51-57: create-player-tx
{:player/id :player-1
 :player/life 20
 :player/mana-pool {:white 0 :blue 0 ...}
 :player/storm-count 0
 :player/land-plays-left 1
 :player/max-hand-size 7}

; Line 74-80: create-game-entity-tx
{:game/id :game-1
 :game/turn 1
 :game/phase :main1
 :game/active-player player-eid
 :game/priority player-eid
 :game/human-player-id :player-1}
```

**Consumers:**
- `events/init.cljs:110-115` — passes to `create-complete-player`
- `sharing/restorer.cljs:40-52` — builds overrides from snapshot player maps
- Tests use these directly

**Data Dependencies:**
- `:player/mana-pool` MUST have all 6 color keys (used by `engine/mana.cljs` without defensive checks)
- `:player/id` must be a keyword (dereferenced in queries)
- `:game/turn` must be int or nil (compared numerically in many places)
- `:game/phase` must be one of 8 valid phases

**Why Risky:**
- Mana pool operations (`engine/mana.cljs:38`) do `merge-with +` expecting all keys present
- Missing a color key causes `(get pool :red 0)` to fail silently → mana calculation wrong
- Player creation has 0 callers outside `create-complete-player` — seems safe, but sharing/restorer builds variants without validation

**Module Boundary:** `db/` → `events/init`, `sharing/restorer`, `engine/mana`

**Risk Level:** **HIGH** — Mana calculations are core to game correctness

---

### 2. Mana Pool Operations — Unvalidated Maps

**File:** `engine/mana.cljs`

**Data Creation Points:**

```clojure
; Line 23: resolve-x-cost returns modified mana-cost map
(assoc :colorless (+ existing-colorless x-as-colorless))

; Line 38: add-mana — merges caller-supplied mana map
(merge-with + current-pool mana-to-add)

; Line 71: empty-pool returns static map
{:white 0 :blue 0 :black 0 :red 0 :green 0 :colorless 0}
```

**Consumers:**
- `engine/mana.cljs` internal: `can-pay?`, `pay-mana`, `pay-mana-with-allocation`
- `engine/effects.cljs` — calls `add-mana` with effect-defined mana
- Card definitions — `:effect/mana` in `engine/card_spec.cljs`

**Data Dependencies:**
- `:colorless` key required (not `:generic`, not `:any`)
- Only 6 valid color keys: `:white :blue :black :red :green :colorless`
- Values must be non-negative integers
- Map must not contain `:x` key after resolution

**Why Risky:**
- Cards define `:effect/mana` as raw EDN — no check that keys are valid
- If a card typo uses `:generic` instead of `:colorless`, mana math silently breaks
- `merge-with +` on incomplete pool maps leaves missing colors nil, later `(get pool :red 0)` silently treats as 0 instead of error
- No runtime guard — bug invisible until affecting gameplay

**Module Boundary:** `engine/mana` ← effect definitions, card data

**Risk Level:** **HIGH** — Mana calculation is deterministic game logic; silent divergence is critical

---

### 3. Trigger/Ability Data — Card Triggers Flowing Into Datascript

**File:** `engine/trigger_db.cljs`

**Data Creation Points:**

```clojure
; Line 91-129: create-triggers-for-card-tx
; Maps :trigger/type to :trigger/event-type, applies default filter
[{:trigger/type :becomes-tapped
  :trigger/event-type :permanent-tapped
  :trigger/source object-eid
  :trigger/controller player-eid
  :trigger/filter {:event/object-id :self}
  :trigger/uses-stack? true
  :trigger/effects [...]}]

; Line 132-149: create-game-rule-trigger-tx
[{:trigger/always-active? true
  :trigger/uses-stack? false
  :trigger/event-type :draw-step
  :trigger/effects [...]}]
```

**Consumers:**
- `engine/trigger_dispatch.cljs` — matches triggers to events
- `engine/trigger_db.cljs:get-triggers-for-event` — filters by event-type
- `triggers.cljs` — `resolve-trigger` multimethod dispatches on `:trigger/type`

**Data Dependencies:**
- `:trigger/type` MUST be mapped to valid `:trigger/event-type` via `trigger-type->event-type`
- `:trigger/filter` must be a map of `{key value}` pairs (checked dynamically in `matches-filter?`)
- `:trigger/effects` must be vector of valid effect maps (no validation — passed to `reduce-effects`)
- `:trigger/uses-stack?` must be boolean (default true)

**Why Risky:**
- Card definitions define `:card/triggers` as raw EDN vectors — no shape validation
- `trigger-type->event-type` is a multimethod with :default — typos in `:trigger/type` silently use identity mapping
- If a card defines trigger with unknown `:trigger/type`, it's treated as its own event type (likely never matches)
- Example: `:becomes-tapped` → `:permanent-tapped` mapping is not enforced — a typo `:becomes_tapped` silently becomes invalid

**Module Boundary:** Cards (`cards/*/`) → `engine/trigger_db` → Datascript DB

**Risk Level:** **MEDIUM-HIGH** — Triggers are optional card features (not every card uses them), but activation-style triggers are load-bearing (Tourach, City of Brass)

---

### 4. Grant Maps — Temporary Abilities/Costs/Restrictions

**File:** `engine/grants.cljs` + `engine/effects/grants.cljs`

**Data Creation Points:**

```clojure
; Line 42-50: grant-flashback
{:grant/id (random-uuid)
 :grant/type :alternate-cost
 :grant/source object-id
 :grant/expires {:expires/turn turn :expires/phase :cleanup}
 :grant/data {:alternate/id :granted-flashback
              :alternate/zone :graveyard
              :alternate/mana-cost mana-cost
              :alternate/on-resolve :exile}}

; Line 67-77: grant-delayed-draw
{:grant/id (random-uuid)
 :grant/type :delayed-effect
 :grant/source object-id
 :grant/expires {:expires/turn next-turn :expires/phase :upkeep}
 :grant/data {:delayed/phase :upkeep
              :delayed/description "..."
              :delayed/effect {...}}}

; Line 90-95: add-restriction
{:grant/id (random-uuid)
 :grant/type :restriction
 :grant/source source-object-id
 :grant/expires {:expires/turn current-turn :expires/phase :cleanup}
 :grant/data {:restriction/type :cannot-cast-spells}}

; Line 111-116: grant-mana-ability
{:grant/id (random-uuid)
 :grant/type :ability
 :grant/source object-id
 :grant/expires {:expires/turn current-turn :expires/phase :cleanup}
 :grant/data ability-data}
```

**Consumers:**
- `engine/grants.cljs` — `add-grant`, `add-player-grant` (store in object/player mutable vector)
- Domain modules read `:grant/type` and `:grant/data` to apply effects
- `:grant/expires` checked in `grant-expired?` (line 167-195)

**Data Dependencies:**
- `:grant/type` MUST be one of: `:alternate-cost`, `:ability`, `:delayed-effect`, `:restriction` (no enum spec)
- `:grant/expires` must have `:expires/turn` (int) and `:expires/phase` (keyword) OR `:expires/permanent true`
- `:grant/source` must be a valid object ID (UUID)
- `:grant/data` shape depends on `:grant/type` — no per-type validation

**Why Risky:**
- `execute-effect-impl :grant-flashback` hard-codes grant structure (line 42-50)
- If effect creator typos `:grant/type` or `:grant/expires` key, grant is stored silently broken
- Example: typo `:expires/phase :cleanup` → `:expires/phase :cleanup_step` silently expires at wrong time
- Grants stored as vectors in object/player — update is wholesale replacement, no schema validation

**Module Boundary:** `engine/effects/grants` (creators) → `engine/grants` (storage) → consumers (abilities, restrictions, cycling)

**Risk Level:** **MEDIUM** — Grants are secondary features (flashback, delayed draw); primary bugs less likely but subtle when they occur

---

### 5. History Entry Data — Pending & Deferred Entries

**File:** `history/descriptions.cljs` + consumers in `events/`

**Data Creation Points:**

```clojure
; Line 18-26: build-pending-entry
{:description description
 :snapshot game-db
 :event-type event-type
 :turn turn
 :principal principal}

; Various creation sites in events/:
; events/casting.cljs:266-291
{:type :cast-and-yield           ; optional, type of deferred entry
 :object-id casting-spell-id}    ; optional, for fallback description

; events/priority_flow.cljs:137-141
{:type :cast-and-yield
 :object-id object-id}
```

**Consumers:**
- `history/interceptor.cljs:16-20` — processes `:history/pending-entry` after event
- `events/selection/core.cljs:313-326` — `process-deferred-entry` converts to pending-entry
- `history/core.cljs:make-entry` — converts pending-entry to history entry
- History UI — reads `:entry/description`, `:entry/event-type`, `:entry/principal`

**Data Dependencies:**
- `:description` must be string (or nil, which produces "nil" in UI)
- `:snapshot` must be valid Datascript DB value (used to reconstruct game state)
- `:event-type` must match a valid re-frame event keyword
- `:principal` must be player-id keyword or nil
- `:turn` must be int or 0 (compared in history navigation)
- Deferred entry `:type` must be keyword (`:cast-and-yield`, etc.) — not enum validated
- Deferred entry `:object-id` must be valid UUID or nil

**Why Risky:**
- Descriptions created inline in event handlers — no reuse pattern
- If handler sets `:history/pending-entry` with wrong shape (missing `:description`), history breaks
- Deferred entry `:type` determines how description is generated — typo `:cast_and_yield` silently produces wrong description
- Snapshot can be large DB value — no size/shape validation before storing

**Module Boundary:** `events/*/` (creators) → `history/interceptor` → history core → UI

**Risk Level:** **MEDIUM** — History bugs are visible in UI but don't break game state; fork/undo correctness depends on snapshot validity

---

### 6. Continuation System — Effect Chains via `:then` Protocol

**File:** `events/priority_flow.cljs` + `events/selection/core.cljs`

**Data Creation Points:**

```clojure
; Line 137: priority_flow.cljs — create continuation
{:then :resolve-one-and-stop
 :effect effect
 :remaining-effects remaining-effects}

; Line 266: casting.cljs — deferred entry for continuation
{:type :cast-and-yield
 :object-id casting-spell-id}

; events/selection/core.cljs returns:
{:db db'
 :then :resolve-one-and-stop}

; or for chaining:
{:db db'
 :then :continue-selection-chain}
```

**Consumers:**
- `events/selection/core.cljs:66-95` — `apply-continuation` dispatches on `:then` keyword
- `events/priority_flow.cljs:70-160` — reads returned `:then` to decide next action
- Implicit continuation protocol: `:then` values are keywords, must match handler registration

**Data Dependencies:**
- `:then` MUST be registered keyword (no multimethod guard, relies on caller knowing valid values)
- If `:then` is unregistered, continuation is silently dropped
- `:effect`, `:remaining-effects` must match what continuation handler expects

**Why Risky:**
- No enum spec for valid `:then` keywords
- If handler typos `:then :resolve-one-and-stop` → `:then :resolve_one_and_stop`, continuation silently lost
- Continuation protocol is implicit (not encoded in data), relies on convention
- Multiple handlers create `:then` continuations — no single place to validate

**Module Boundary:** `events/*/` (creators) → `events/selection/core` (dispatcher)

**Risk Level:** **MEDIUM** — Bugs would manifest as silent continuation drops (game appears hung), but no silent state corruption

---

### 7. Object Creation During Restoration & Zone Entry

**File:** `sharing/restorer.cljs` + `events/init.cljs`

**Data Creation Points:**

```clojure
; sharing/restorer.cljs:77-111: object-tx-for-zone
{:object/id (random-uuid)
 :object/card card-eid
 :object/zone zone
 :object/owner owner-eid
 :object/controller owner-eid
 :object/tapped (boolean (:object/tapped obj-map))
 :object/position (if (= zone :library) position 0)
 ;; Optional creature fields
 :object/power power
 :object/toughness toughness
 :object/summoning-sick false
 :object/damage-marked 0
 ;; Optional fields
 :object/counters counters
 :object/grants grants}

; events/init.cljs:60-69: objects-tx
{:object/id uuid
 :object/card (get-card-eid db card-id)
 :object/zone zone
 :object/owner owner-eid
 :object/controller owner-eid
 :object/tapped false
 :object/position (if (= zone :library) i 0)}

; engines/zones.cljs:96-104: creature field initialization on ETB
{:object/power (:card/power card-data)
 :object/toughness (:card/toughness card-data)
 :object/summoning-sick true
 :object/damage-marked 0}
```

**Consumers:**
- Datascript transactor — validates against schema
- `engine/zones.cljs:move-to-zone` — expects power/toughness for creatures
- Combat system — reads power/toughness
- Creature queries

**Data Dependencies:**
- `:object/card` must be valid card entity ref (integer EID)
- `:object/zone` must be valid zone keyword (8 valid zones)
- `:object/owner` / `:object/controller` must be player EIDs
- `:object/position` must be int ≥ 0
- For creatures entering battlefield: `:object/power` and `:object/toughness` MUST be int (from card definition)
- `:object/tapped` must be boolean
- Creature field keys may be missing (retracted when leaving battlefield)

**Why Risky:**
- `object-tx-for-zone` reads from portable snapshot object map — shape not validated
- If snapshot contains creature with malformed power/toughness, restoration silently corrupts creature
- Zone entry code assumes if card has `:creature` type, it has power/toughness — divergence here breaks combat
- Position calculation for library objects uses index — if snapshot has dupe positions, unpredictable order

**Module Boundary:** `sharing/restorer` (creators) → Datascript (storage) → engine (consumers)

**Risk Level:** **HIGH** — Object creation is load-bearing; creature stats bugs directly affect combat correctness

---

## Scoring Summary

| Gap | Module Boundary | Bug Surface | Risk |
|-----|-----------------|-------------|------|
| Game state init | `db/game_state` → `events/init`, `engine/mana` | Mana pools (6 keys) | **HIGH** |
| Mana operations | `engine/mana` ← cards | Pool structure, color keys | **HIGH** |
| Trigger data | cards → `engine/trigger_db` | Type mapping, filter shape | **MEDIUM-HIGH** |
| Grants | `engine/effects/grants` → `engine/grants` | Grant type, expiration | **MEDIUM** |
| History entries | `events/*` → `history/interceptor` | Description, snapshot validity | **MEDIUM** |
| Continuations | `events/*` → `events/selection` | `:then` keyword enum | **MEDIUM** |
| Object creation | `sharing/restorer` + `events/init` | Zone, creature stats | **HIGH** |

---

## Actionable Next Steps

### Phase 1 (Critical — HIGH risk gaps)
1. Create `engine/mana_spec.cljs` — validate mana pool structure (6 colors, no extra keys)
2. Create `db/player_spec.cljs` — validate player creation (ID, mana pool, life, turn-based grants)
3. Create `engine/object_spec.cljs` — validate object creation (zone, card-ref, creature fields)
4. Update `engine/trigger_db.cljs` — add `:trigger/event-type` validation against enum

### Phase 2 (Important — MEDIUM risk gaps)
5. Create `engine/grant_spec.cljs` — validate grant structure per `:grant/type`
6. Create `history/entry_spec.cljs` — validate pending-entry, deferred-entry maps
7. Create `events/continuation_spec.cljs` — enumerate valid `:then` keywords

### Implementation Notes
- Follow existing pattern: cljs.spec, dev-only validation via `goog.DEBUG`, multimethod per type
- Specs describe EXISTING shapes — no new requirements
- Validation points: where data is created, before storage/crossing boundary
- Each spec file should have <200 lines if possible (break into per-type files if needed)

---

## References

**Existing specs:**
- `engine/card_spec.cljs` — 373 lines, 40 defmethods, multimethod pattern
- `events/selection/spec.cljs` — 450+ lines, 31 defmethods
- `engine/stack_spec.cljs` — 200+ lines, 15 defmethods
- `bots/action_spec.cljs` — 80 lines, 3 defmethods

**Memory records:**
- `spec_duplication_analysis_2026_04_04.md` — audit of 5 spec files, 154 s/defs, 92 defmethods
- `cljs_spec_adoption_investigation.md` — gaps analysis, T1-T4 friction tiers
