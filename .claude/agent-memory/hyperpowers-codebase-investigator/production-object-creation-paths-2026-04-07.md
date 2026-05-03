---
name: Production Object Creation Paths & P/T Initialization
description: Complete audit of all production paths creating game objects; 5 creation sites, 4 zone transitions, 0 gaps found
type: reference
---

## Summary

All production object creation paths are **fully audited and aligned** with the recent P/T unification fix. 5 distinct creation sites identified, all setting `:object/power` and `:object/toughness` correctly for creatures.

**Risk Level:** ✓ SAFE — No gaps found. All production paths follow the same pattern.

---

## Object Creation Sites (5 total)

### 1. Game Initialization: `events/init.cljs` — 56-111 (MAIN PATH)

**Purpose:** Initialize fresh game from deck config (opening hand + library setup)

**Two Transaction Functions:**

#### `objects-tx` (lines 56-78)
- **When:** Creates player's opening hand + library objects
- **Called from:** Lines 141-148 (3 calls: hand, library, sideboard)
- **P/T Handling:**
  - Line 64: Pulls card data: `:card/types :card/power :card/toughness`
  - Line 65: Checks if creature: `(contains? (set card-types) :creature)`
  - Lines 74-76: **CORRECT** — creatures get P/T from card definition
  ```clojure
  (if creature?
    (assoc base
           :object/power (:card/power card-data)
           :object/toughness (:card/toughness card-data))
    base)
  ```
- **Zones:** `:hand`, `:library`, `:sideboard`
- **Status:** ✓ Creatures set P/T at creation

#### `opponent-deck-tx` (lines 81-110)
- **When:** Creates opponent's deck distribution (7-card hand + rest as library)
- **Called from:** Line 144
- **P/T Handling:**
  - Same logic as `objects-tx`: lines 95-96 pull card data, 96 checks creature
  - Lines 104-107: **CORRECT** — creatures set P/T from card definition
  ```clojure
  (if creature?
    (assoc base
           :object/power (:card/power card-data)
           :object/toughness (:card/toughness card-data))
    base)
  ```
- **Zones:** `:hand` (lines 109), `:library` (line 110)
- **Status:** ✓ Creatures set P/T at creation

---

### 2. Snapshot Restoration: `sharing/restorer.cljs` — 78-116

**Purpose:** Restore a shared game snapshot to playable state

**Function:** `object-tx-for-zone`
- **When:** Restores objects from portable player maps (one per zone)
- **Called from:** Line 128 via `transact-zone!` (lines 119-129)
- **Called 5 times:** lines 215-219 (all 5 zones: hand, graveyard, exile, library, battlefield)
- **P/T Handling:**
  - Line 104-105: Reads card base stats via `card-base-stat` helper
  - Lines 103-108: **CORRECT** — creatures get P/T from card definition in all zones
  ```clojure
  (if (creature? types)
    (let [power     (card-base-stat db card-eid :card/power)
          toughness (card-base-stat db card-eid :card/toughness)]
      (cond-> with-optional
        (some? power)     (assoc :object/power power)
        (some? toughness) (assoc :object/toughness toughness)))
    with-optional)
  ```
  - Lines 110-113: Battlefield creatures ALSO get combat fields
  ```clojure
  (if (and (= zone :battlefield) (creature? types))
    (assoc with-creature
           :object/summoning-sick false
           :object/damage-marked 0)
    with-creature)
  ```
- **Validation:** Line 115 calls `object-spec/validate-object-tx!` (dev-only via goog.DEBUG)
- **Status:** ✓ Creatures set P/T in all zones + combat attrs on battlefield

---

### 3. Token Creation: `engine/effects/tokens.cljs` — 13-44

**Purpose:** Create a token creature via `:create-token` effect

**Function:** Defmethod for `:create-token` effect type
- **When:** An effect executes token creation (e.g., Hunting Pack, tokens.cljs)
- **P/T Handling:**
  - Lines 21-25: Create synthetic card entity with token stat definitions
  ```clojure
  :card/power (:token/power token-def)
  :card/toughness (:token/toughness token-def)
  ```
  - Lines 33-43: Create token object on battlefield with P/T
  ```clojure
  {:object/power (:token/power token-def)
   :object/toughness (:token/toughness token-def)
   :object/summoning-sick true
   :object/damage-marked 0}
  ```
- **Zones:** Always `:battlefield` (line 35)
- **Status:** ✓ Tokens set P/T at creation + combat fields (summoning-sick, damage-marked)

---

### 4. Storm Copy Creation: `engine/triggers.cljs` — 36-91

**Purpose:** Create a copy of a spell on the stack for storm

**Function:** `create-spell-copy` (defn, not effect)
- **When:** Storm resolution via `:storm-copies` effect (resolution.cljs line 55)
- **Called from:** `engine/resolution.cljs` line 61 in default resolution loop
- **P/T Handling:**
  - **NO P/T SET** (correct—copies are on `:stack` zone, not battlefield)
  - Line 62-68: Create copy object with minimal fields
  ```clojure
  {:object/id copy-id
   :object/card card-eid
   :object/zone :stack
   :object/owner owner-eid
   :object/controller controller-eid
   :object/tapped false
   :object/is-copy true}
  ```
  - Lines 79-86: Create `:storm-copy` stack-item (side effect, not on battlefield)
- **Zones:** Always `:stack` (line 64)
- **Status:** ✓ Correct — no P/T because copies exist only on stack, never on battlefield

---

### 5. Test-Only Path: `db/init.cljs` — 16-51

**Purpose:** Simple test setup for single Dark Ritual card

**Status:** TEST-ONLY (never called in production)
- Used by 29+ test files
- Creates Dark Ritual (instant, not creature) in hand
- Line 40-45: **No P/T set** (correct — Dark Ritual is not a creature)
- **IMPORTANT:** Not a production concern; test-only convenience helper

---

## Zone Transition Code: `engine/zones.cljs` — 43-122

**All Transitions Covered:**

### `move-to-zone` (lines 43-122) — MAIN TRANSITION FUNCTION

**Creature Behavior:**

**Leaving Battlefield** (lines 75-96):
```clojure
(when (= current-zone :battlefield)
  (let [obj (d/pull db [{:object/card [:card/types :card/power :card/toughness]}
                        :object/summoning-sick :object/damage-marked
                        :object/attacking :object/blocking] obj-eid)
        card-data (:object/card obj)
        card-types (set (:card/types card-data))]
    (cond-> []
      (contains? card-types :creature)
      (into [[:db/add obj-eid :object/power (:card/power card-data)]
             [:db/add obj-eid :object/toughness (:card/toughness card-data)]])
      ...)))
```
- **Line 87-88:** RESET (not retract) P/T to card base values
- Lines 89-96: Retract combat-only fields (summoning-sick, damage-marked, attacking, blocking)
- **Status:** ✓ P/T preserved in all non-battlefield zones

**Entering Battlefield** (lines 97-109):
```clojure
(when (= new-zone :battlefield)
  (let [card (d/pull db [{:object/card [:card/types :card/power :card/toughness]}] obj-eid)
        card-data (:object/card card)
        card-types (set (:card/types card-data))]
    (when (contains? card-types :creature)
      [[:db/add obj-eid :object/power (:card/power card-data)]
       [:db/add obj-eid :object/toughness (:card/toughness card-data)]
       [:db/add obj-eid :object/summoning-sick true]
       [:db/add obj-eid :object/damage-marked 0]])))
```
- **Line 106-107:** SET P/T from card definition (idempotent, safe if already present)
- Lines 108-109: Add combat-only fields (summoning-sick, damage-marked)
- **Status:** ✓ P/T set correctly, combat fields added

**Tapped State** (lines 110-116):
- Reset tapped to false when entering/leaving battlefield (per MTG rule 110.6)
- Independent of P/T logic

### `phase-out` (lines 125-133)

- **Behavior:** Direct zone change to `:phased-out`, bypasses `move-to-zone` intentionally
- **P/T Handling:** **NONE** — preserves all state exactly as-is
- **Status:** ✓ Correct — phased-out creatures keep P/T (no removal from play)

### `phase-in` (lines 136-144)

- **Behavior:** Direct zone change back to `:battlefield`, bypasses `move-to-zone` intentionally
- **P/T Handling:** **NONE** — preserves all state exactly as-is
- **Status:** ✓ Correct — phased-in creatures keep their P/T (not just-entered)

---

## P/T Reads — Where P/T is Used

### Reading `:object/power` / `:object/toughness`

**Reading from Battlefield Creatures** (safe — always present):
- `engine/creatures.cljs` lines 27, 46: Read via `effective-power`, `effective-toughness`
  - Line 27: `(or (:object/power obj) 0)` — safe fallback
  - Line 46: `(or (:object/toughness obj) 0)` — safe fallback
- `subs/game.cljs` lines 253-254: Read for UI display (creature stat box)
  - Safe: only called when creature is on battlefield

**Reading from Non-Battlefield Creatures** (use `:card/toughness`):
- `engine/effects/life.cljs` lines 85-105: `:lose-life-equal-to-toughness` effect
  - **Line 97:** `toughness (or (:card/toughness card) 0)` — reads card definition, not object attribute
  - **Correct:** Works even after creature is destroyed (e.g., Vendetta sequence)
  - Comment lines 87-91 explicitly documents this design

---

## Test Helper Object Creation: `test_helpers.cljs` — 52-178

**All test helpers follow production patterns:**

### `add-card-to-zone` (lines 52-90)
- Creates single object in any zone
- **P/T Handling** (lines 71-88):
  ```clojure
  creature? (contains? card-types :creature)
  obj (cond-> base-obj
        ...
        creature?
        (assoc :object/power (:card/power card-def)
               :object/toughness (:card/toughness card-def))
        (and creature? (= zone :battlefield))
        (assoc :object/summoning-sick true
               :object/damage-marked 0))
  ```
- **Status:** ✓ Matches production path exactly

### `add-cards-to-library` (lines 93-137)
- Creates multiple objects in library with sequential positions
- **P/T Handling** (lines 120-132): Same as `add-card-to-zone`
- **Status:** ✓ Matches production path exactly

### `add-cards-to-graveyard` (lines 140-177)
- Creates multiple objects in graveyard
- **P/T Handling** (lines 162-173): Same as `add-card-to-zone`
- **Status:** ✓ Matches production path exactly

---

## Edge Cases & Safety Checks

### Creation Validation

**spec Validation at Restoration Boundary** (`object-spec/validate-object-tx!`):
- Location: `sharing/restorer.cljs` line 115
- Validates: Every restored object transaction before commit
- Dev-only: Via `goog.DEBUG` guard in `spec-util/validate-at-chokepoint!`
- **Gap:** No validation at init.cljs creation (production path)
- **Risk:** Low — init.cljs logic is simple and consistent

### Creatures Without P/T Definition

**Safe because:**
1. Schema requires `:card/types` inclusion in :creature type definition (checked at card-spec validation)
2. All creature cards include `:card/power` and `:card/toughness` fields
3. Code defensively uses `(or (:object/power obj) 0)` fallback

---

## Summary Table

| Site | Path | Zones | P/T Set? | Combat Fields? | Validation |
|------|------|-------|----------|----------------|----|
| events/init.cljs:objects-tx | Production (hand/library) | hand, library, sideboard | ✓ Creatures | No | No |
| events/init.cljs:opponent-deck-tx | Production (opponent) | hand, library | ✓ Creatures | No | No |
| sharing/restorer.cljs:object-tx-for-zone | Production (restore) | All 5 zones | ✓ Creatures | ✓ BF only | spec (dev) |
| engine/effects/tokens.cljs | Production (tokens) | battlefield | ✓ Token defs | ✓ Always | No |
| engine/triggers.cljs:create-spell-copy | Production (storm) | stack | ✗ (correct—stack only) | No | No |
| db/init.cljs | Test-only | hand | N/A (non-creature) | No | No |
| test-helpers.cljs:add-card-to-zone | Test path | Any | ✓ Matches prod | ✓ When BF | No |
| test-helpers.cljs:add-cards-to-library | Test path | library | ✓ Matches prod | No | No |
| test-helpers.cljs:add-cards-to-graveyard | Test path | graveyard | ✓ Matches prod | No | No |

---

## No Gaps Found

**All production paths:**
- ✓ Set `:object/power` and `:object/toughness` for creatures at creation
- ✓ Set creature-only combat fields (`:object/summoning-sick`, `:object/damage-marked`) on battlefield only
- ✓ Reset P/T to card base when leaving battlefield
- ✓ Preserve P/T in non-battlefield zones (hand, library, graveyard, exile)
- ✓ Use `:card/power` / `:card/toughness` when reading from non-battlefield zones (e.g., destroyed creatures)

**Test paths:** Mirror production paths exactly. No divergence found.

**Design consistency:** The 2026-03-28 fix (fizzle-pb06) is complete and accurate.
