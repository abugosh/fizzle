---
name: Fizzle-4cpn Parallel Mana Ability Paths Investigation
description: Complete inventory of render, event, engine, selection, and data divergence points between card-defined and granted mana abilities
type: reference
---

# Parallel Mana Ability Paths — Complete Inventory (fizzle-4cpn)

Investigation Date: 2026-04-30

## Quick Summary

Two parallel paths exist for mana ability activation:
1. **Card abilities** (`:card/abilities` on card definition) — complex, with optional selection flow
2. **Granted abilities** (`:object/grants` on instance) — simple, direct execution, no selection/history

The paths **diverge** at render → event → selection layers, **converge** at cost payment and effect execution in engine.

---

## 1. RENDER LAYER DIVERGENCE

**File:** `/Users/abugosh/g/fizzle/src/main/fizzle/views/battlefield.cljs`

### Card Mana Abilities: `get-mana-ability-buttons` (lines 31–64)

- **Data source:** `:object/card :card/abilities` (card definition)
- **Filter:** `:ability/type :mana`
- **Returns:** Vector of button specs `{:ability-index :color :amount :sac?}`
- **Mana source:** Either `:ability/produces` (direct) OR `:ability/effects` with `:add-mana` (line 52–55)
- **Key insight:** Mana abilities can have `{:any N}` produces, which expand to one button per color (line 58–61)

### Granted Mana Abilities: `get-granted-mana-abilities` (lines 67–74)

- **Data source:** `:object/grants` filtered by `{:grant/type :ability :ability/type :mana}`
- **Returns:** Vector of grant maps
- **Key insight:** Grants carry the full ability in `:grant/data` field

### UI Rendering: `permanent-view` (lines 145–206)

- Calls both getters independently (lines 145, 147)
- Renders card-ability buttons in one `[:div]` (lines 186–190)
- Renders granted-ability buttons in separate `[:div]` (lines 202–206)
- **Separate sections intentional:** Grants are ephemeral; card abilities are immutable card data

### Button Dispatch: `mana-button` vs `granted-ability-button`

- **Card:** Dispatches `::activate-mana-ability` with `ability-index` (line 100)
- **Grant:** Dispatches `::activate-granted-mana-ability` with `grant-id` (line 112)

---

## 2. EVENT LAYER DIVERGENCE

**File:** `/Users/abugosh/g/fizzle/src/main/fizzle/events/abilities.cljs`

### Path A: Card Mana Abilities

| Aspect | Details |
|--------|---------|
| **Handler** | `::activate-mana-ability` (lines 28–46) |
| **Payload** | `[_ object-id mana-color player-id ability-index]` |
| **Delegate** | `mana-ability/activate-mana-ability-with-generic-mana` (line 33) |
| **Returns** | `{:db :pending-selection}` |
| **Selection** | If generic cost exists, `set-pending-selection` called (lines 44–45) |
| **History** | Entry created via `descriptions/build-pending-entry` (line 40–41) |
| **Key flow** | `event → mana_ability.cljs → selection (if generic) → engine → mana pool` |

### Path B: Granted Mana Abilities

| Aspect | Details |
|--------|---------|
| **Handler** | `::activate-granted-mana-ability` (lines 394–399) |
| **Payload** | `[_ object-id grant-id]` |
| **Delegate** | `activate-granted-mana-ability` (pure fn, lines 361–391) |
| **Returns** | Updated `:game/db` only |
| **Selection** | None (no selection path exists) |
| **History** | No entry created |
| **Key flow** | `event → pay costs → reduce effects → mana pool` (inline) |

### Event Handler Code

Card abilities (lines 28–46):
```clojure
(rf/reg-event-db
  ::activate-mana-ability
  (fn [db [_ object-id mana-color player-id ability-index]]
    (let [game-db (:game/db db)
          pid (or player-id (queries/get-human-player-id game-db))
          result (mana-ability/activate-mana-ability-with-generic-mana
                   game-db pid object-id mana-color ability-index)
          game-db-after (:db result)
          pending-sel (:pending-selection result)]
      (if pending-sel
        (sel-spec/set-pending-selection base pending-sel)
        base))))
```

Granted abilities (lines 394–399):
```clojure
(rf/reg-event-db
  ::activate-granted-mana-ability
  (fn [db [_ object-id grant-id]]
    (let [game-db (:game/db db)
          pid (queries/get-human-player-id game-db)]
      (assoc db :game/db (activate-granted-mana-ability game-db pid object-id grant-id)))))
```

**Observation:** Granted path is synchronous, direct db update. Card path may be async (selection).

---

## 3. SELECTION LAYER DIVERGENCE

**File:** `/Users/abugosh/g/fizzle/src/main/fizzle/events/selection/mana_ability.cljs`

### Card Abilities with Generic Costs (Selection Path)

| Function | Lines | Role |
|----------|-------|------|
| `activate-mana-ability-with-generic-mana` | 176–199 | Routes: if generic cost → selection, else → engine direct. |
| `open-mana-allocation-for-mana-ability` | 75–128 | Opens `:mana-allocation` selection with `:selection/source-type :mana-ability`. |
| `confirm-mana-ability-mana-allocation` | 131–173 | Confirm executor. Deducts mana, calls `execute-mana-ability-production-and-effects`. |

**Context schema (lines 6–17):** When generic cost present, selection carries 4 namespaced keys:
```clojure
:mana-ability/object-id
:mana-ability/ability-index
:mana-ability/chosen-color
:mana-ability/generic-count
```

**Example:** Chromatic Sphere has `{:mana {:colorless 1}}` cost → triggers selection flow.

### Granted Abilities: No Selection Layer

**No entry point in selection layer.** Granted abilities bypass selection entirely.

**Why?** Current grants (rain-of-filth) have no generic costs. Architecture allows it (could be added later), but not currently exercised.

---

## 4. ENGINE LAYER

**File:** `/Users/abugosh/g/fizzle/src/main/fizzle/engine/mana_activation.cljs`

### Card Abilities: `activate-mana-ability` (6-arity overloaded)

| Arity | Lines | Usage |
|-------|-------|-------|
| **4** | 142–144 | `(activate-mana-ability db player-id object-id mana-color)` |
| **5** | 145–147 | `(activate-mana-ability db player-id object-id mana-color ability-index)` |
| **6** | 148–262 | Full implementation with allocation validation |

**Key guards (lines 148–262):**
1. Priority phase check (line 150)
2. Object/player/zone/controller checks (lines 152–159)
3. Find mana ability (with land-type override grant support, lines 161–216)
4. `can-activate?` check (line 218)
5. Allocation validation (line 226) — **if generic cost present**
6. Pay costs via `abilities/pay-all-costs` (line 252)
7. Execute production + effects via `execute-mana-ability-production-and-effects` (line 255)

**Allocation validation (lines 29–50):**
- Validates that caller-supplied allocation exactly covers generic cost portion
- All allocation keys must be known mana colors
- Sum must exactly equal generic cost
- Each color must be covered by player's mana pool

### Granted Abilities: No Engine Function

Granted abilities **do not call any engine function.** Execution is inline in `events/abilities.cljs:385–390`:

```clojure
(if-let [db-after-costs (abilities/pay-all-costs db object-id (:ability/cost ability))]
  (reduce (fn [db' effect]
            (effects/execute-effect db' player-id effect))
          db-after-costs
          (:ability/effects ability []))
  db)
```

**Two-step flow:**
1. Pay costs (line 385)
2. Reduce effects (lines 387–390)

### Convergence Points

Both paths call:
- `abilities/pay-all-costs` — cost payment engine
- `effects/execute-effect` — mana/draw/etc resolution
- Indirectly: `effects/execute-effect-impl :add-mana` — mana pool update

---

## 5. DATA MODEL DIVERGENCE

### Card Mana Abilities

**Definition location:** Card files (e.g., `/Users/abugosh/g/fizzle/src/main/fizzle/cards/artifacts/lotus_petal.cljs`)

```clojure
:card/abilities [{:ability/type :mana
                  :ability/cost {:tap true
                                 :sacrifice-self true}
                  :ability/produces {:any 1}}]
```

**Characteristics:**
- Immutable card definition (shared by all instances)
- `:ability/produces` optional (alternative: `:ability/effects` with `:add-mana`)
- Cost includes `:tap` (rendered in UI; impacts can-activate? check)
- Created at deck definition time

### Granted Mana Abilities

**Definition location:** Created at spell resolution time via `:grant-mana-ability` effect

**Card example:** `/Users/abugosh/g/fizzle/src/main/fizzle/cards/black/rain_of_filth.cljs`

```clojure
:card/effects [{:effect/type :grant-mana-ability
                :effect/target :controlled-lands
                :effect/ability {:ability/type :mana
                                 :ability/cost {:sacrifice-self true}
                                 :ability/produces {:black 1}
                                 :ability/effects [{:effect/type :add-mana
                                                    :effect/mana {:black 1}}]}}]
```

**Grant data structure (per `engine/grants.cljs`):**

```clojure
{:grant/id        uuid
 :grant/type      :ability
 :grant/source    object-id
 :grant/expires   {:expires/turn N :expires/phase :cleanup}
 :grant/data      ability-map}
```

**Characteristics:**
- Ephemeral (expires EOT per `:grant/expires`)
- Per-instance (stored on `:object/grants`)
- Ability embedded in `:grant/data`
- Created at resolution time
- Multiple grants allowed per object

**Key difference:** Grants can express `:ability/effects` (e.g., rain-of-filth includes `:add-mana` effect in the grant data), while card abilities typically use `:ability/produces` (simpler).

---

## 6. QUERY LAYER DIVERGENCE

**File:** `/Users/abugosh/g/fizzle/src/main/fizzle/db/queries.cljs`

### Queries Used by Each Path

| Query | Lines | Card Path | Grant Path | Purpose |
|-------|-------|-----------|-----------|---------|
| `get-object` | 80–87 | ✓ | ✓ | Fetches object with `:object/card` and `:object/grants` |
| `get-grants` | 158–163 | — | ✓ | Specific getter for `:object/grants` (used only by render) |
| `get-mana-pool` | 31–39 | ✓ | ✓ | Fetch pool after production |
| `get-object-eid` | 21–28 | ✓ | ✓ | Find EID for mutations |

**Observation:** No specialized "card mana abilities" query — they're read directly via object card data. Only `get-grants` is path-specific (render layer).

---

## 7. HISTORY LAYER DIVERGENCE

| Aspect | Card Abilities | Granted Abilities |
|--------|---|---|
| **History entry** | Created via `descriptions/build-pending-entry` (lines 40–41) | None |
| **Deferred entry** | Used when targeting needed (lines 311–316) | Not applicable |
| **Undo/fork support** | ✓ Full support | ✗ Invisible to history |
| **Bot recording** | ✓ Via director dispatcher | ✗ Not recorded (shared gap with bot system) |

**Critical divergence:** Granted mana ability activations **do not create history entries**. This means:
- Undo/fork does not include them
- Replay excludes them
- Bot actions during director loop also have this gap (ADR memo: director_human_bot_asymmetry)

**Related:** See MEMORY.md entry on director human-bot asymmetry (2026-04-01).

---

## 8. RESTRICTION/GUARD DIVERGENCE

### Tap Check

| Path | Check | Location | Details |
|------|-------|----------|---------|
| **Card** | ✓ Enforced | `engine/mana_activation.cljs:218` via `can-activate?` | Checks summoning sickness, restrictions; includes tap state check |
| **Grant** | ✗ Skipped | `events/abilities.cljs:384` comment | "Unlike native mana abilities, granted abilities ignore tapped state" |

**Rationale:** Granted abilities are intended to be activatable even when tapped (e.g., tapped land can still sacrifice for mana via rain-of-filth grant).

**Test confirmation:** `rain_of_filth_test.cljs:107–124` (tapped-lands-get-grant-test).

### can-activate? Check

| Path | Check | Location | Result if False |
|------|-------|----------|---|
| **Card** | ✓ Called | `engine/mana_activation.cljs:218` | Activation fails silently (db unchanged) |
| **Grant** | ✗ Not called | Not in `events/abilities.cljs:361–391` | **Potential footgun:** Restrictions are not checked for grants |

**Risk:** If a player restriction (e.g., "cannot activate abilities") is added without auditing grants, grants would still be activatable.

---

## 9. DIVERGENCE ASSESSMENT

| Divergence | Type | Rationale | Risk | Notes |
|---|---|---|---|---|
| **Separate render functions** | Essential | Grants ephemeral; need per-instance. | Low | Correct design. |
| **Separate event handlers** | Essential | Different UI patterns (ability-index vs grant-id). | Low | Correct design. |
| **No selection for grants** | Accidental? | No current grants have generic costs. | Medium | Could be unified; architecture doesn't prevent it. |
| **No history for grants** | Design gap | Blocks undo/fork; shared with bot system. | **High** | Systemic issue affecting reproducibility. |
| **Skipped tap check for grants** | Essential | By design: tapped lands can sac for mana. | Low | Intentional; test-confirmed. |
| **Skipped can-activate? for grants** | Potential bug | Card abilities check; grants don't. | **Medium** | Footgun: restrictions not audited for grants. |

---

## 10. CONVERGENCE POINTS

Both paths merge at:

1. **Cost payment:** `abilities/pay-all-costs` (line 385 for grants; line 252 for cards)
2. **Effect execution:** `effects/execute-effect` (line 388 for grants; indirectly for cards)
3. **Mana addition:** Both resolve `:add-mana` effects to mana pool
4. **Priority check:** Both verify `in-priority-phase?`
5. **Zone/controller checks:** Both verify battlefield + controller match

---

## 11. ENTRY POINTS FOR FUTURE WORK

If fizzle-4cpn aims to unify these paths:

1. **Selection for grants:** Could enable more complex granted abilities (generic costs).
   - Add selection entry point in `mana_ability.cljs`
   - Modify `::activate-granted-mana-ability` to route through selection if needed

2. **History for grants:** Enable undo/fork support.
   - Create history entry in `::activate-granted-mana-ability` event handler
   - Use existing `descriptions/describe-activate-mana-ability` or new grant variant

3. **Restriction check for grants:** Close footgun.
   - Call `can-activate?` in `activate-granted-mana-ability` function
   - Document why tap check is skipped (intentional)

4. **can-activate? extension:** Add method for grants.
   - Currently only checks card ability constraints
   - Could add grant-specific constraints (e.g., "grant expires next turn")

---

## Files Involved

- **Render:** `views/battlefield.cljs` (lines 31–206)
- **Events:** `events/abilities.cljs` (lines 28–399)
- **Selection:** `events/selection/mana_ability.cljs` (lines 75–199)
- **Engine:** `engine/mana_activation.cljs` (lines 107–262)
- **Grants:** `engine/grants.cljs`, `engine/effects/grants.cljs`
- **Tests:** `cards/black/rain_of_filth_test.cljs`, `events/abilities_test.cljs`
- **Data:** Card files (e.g., `cards/artifacts/lotus_petal.cljs`)

---

## Related ADRs & Memory

- **ADR-020:** Selection architecture (pending-selection synchronization)
- **ADR-019:** Push-down invariants (allocation validation)
- **ADR-022:** Domain modules for selection (mana_ability separation)
- **Memory:** director_human_bot_asymmetry (2026-04-01) — Bot actions also skip history
