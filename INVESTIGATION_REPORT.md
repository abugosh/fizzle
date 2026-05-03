# Fizzle Creatures & Combat Investigation Report

## Executive Summary

**Current State**: Fizzle has minimal creature/combat infrastructure. While the card type system recognizes `:creature`, no creature cards exist in the card pool, and there is no combat system implemented.

**Epic Status**: `fizzle-u07p` (Creatures and Combat) is OPEN, marked as P4, depends on bot system being mature.

---

## 1. CREATURE REFERENCES IN CODEBASE

### Where Creatures Are Mentioned (But Not Implemented)

1. **Card Type System** — Creatures recognized as valid type
   - File: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/card_spec.cljs`
   - Line 4-5: `:creature` in `valid-card-types` set

2. **Deck Parser** — Creature category support
   - File: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/deck_parser.cljs`
   - Type ordering includes creature, display label available

3. **Battlefield UI** — Creatures section exists but no creatures
   - File: `/Users/abugosh/g/fizzle/src/main/fizzle/views/battlefield.cljs`
   - Renders `:creatures` section from grouped battlefield objects
   - File: `/Users/abugosh/g/fizzle/src/main/fizzle/views/card_styles.cljs`
   - Border styling for creature type defined (green border color)

4. **Game Subscriptions** — Creatures grouped on battlefield
   - File: `/Users/abugosh/g/fizzle/src/main/fizzle/subs/game.cljs`
   - Groups battlefield objects: `{:creatures ... :other ... :lands ...}`
   - Applied to both player and opponent zones

5. **Setup View** — Creature category in deck import
   - File: `/Users/abugosh/g/fizzle/src/main/fizzle/views/setup.cljs`
   - Creatures in type order and labels

### Summary: Infrastructure Exists But Empty

The UI layer has creature sections and styling ready. The database schema does not have creature-specific fields (no power/toughness). The card pool has zero creatures.

---

## 2. CURRENT CARD SYSTEM

### Card Definition Structure

Core fields:
```clojure
{:card/id             :keyword-id
 :card/name           "Full Name"
 :card/cmc            2
 :card/mana-cost      {:blue 1 :white 2}
 :card/colors         #{:blue :white}
 :card/types          #{:instant :sorcery}
 :card/text           "Oracle text..."
 :card/targeting      [...]    ; OPTIONAL
 :card/effects        [...]    ; Main effects
 :card/abilities      [...]    ; OPTIONAL
 :card/triggers       [...]    ; OPTIONAL
 :card/etb-effects    [...]}   ; OPTIONAL
```

**NO fields for power/toughness** — these would need to be added

### Card Pool Status

- **Total**: 47 card definitions (22 individual + 3 cycle files)
- **NO Creatures**: Zero creature cards in registry
- **Card Types Present**: Instants, Sorceries, Artifacts, Lands, Enchantments

---

## 3. DATABASE SCHEMA (CREATURES)

### Current Object Entity

File: `/Users/abugosh/g/fizzle/src/main/fizzle/db/schema.cljs`

Card objects have:
```clojure
{:object/id          ; Unique ID
 :object/card        ; Ref to card definition
 :object/zone        ; Hand, stack, graveyard, battlefield, library, exile
 :object/owner       ; Player who owns
 :object/controller  ; Player who controls
 :object/tapped      ; Boolean
 :object/counters    ; {:charge 3 :loyalty 4}
 :object/position    ; Position in zone
 :object/is-copy     ; Boolean (storm copies)
 :object/grants      ; Vector of grants
 :object/x-value     ; X cost value
 :object/cast-mode   ; Flashback/mode info
 :object/chosen-mode ; Modal spell choice}
```

**MISSING**: No `:object/power`, `:object/toughness`, `:object/damage` fields

### Combat Phase Exists But Empty

File: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/rules.cljs` (lines 28-31):
```clojure
(def phases
  "MTG turn phases in order: untap → upkeep → draw → main1 → combat → main2 → end → cleanup"
  [:untap :upkeep :draw :main1 :combat :main2 :end :cleanup])
```

File: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/priority.cljs` (lines 5-8):
```clojure
(def priority-phases
  "Phases where players receive priority per MTG rules.
   Untap and cleanup do not grant priority."
  #{:upkeep :draw :main1 :combat :main2 :end})
```

**Current Use**: Combat phase is just a phase label; no combat logic exists.

---

## 4. EFFECT SYSTEM (DAMAGE HANDLING)

### Current Damage Effects

File: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/life.cljs`

Three damage-related effects exist:

1. **`:deal-damage`** (lines 35-45)
   - Targets a player, reduces their life
   - Used by Lightning Bolt
   - No creature damage assignment

2. **`:lose-life`** (lines 9-19)
   - Player loses life

3. **`:gain-life`** (lines 22-32)
   - Player gains life

**NO creature damage assignment effect** — `:deal-damage` targets only players

---

## 5. BOT SYSTEM

### Current Bots

File: `/Users/abugosh/g/fizzle/src/main/fizzle/bots/definitions.cljs`

1. **Goldfish Bot** — Plays land per turn, passes
2. **Burn Bot** — 20 Mountains + 40 Lightning Bolts, casts bolts at face

### Bot Protocol

File: `/Users/abugosh/g/fizzle/src/main/fizzle/bots/protocol.cljs`

Bot actions:
- `:play-land` — Play a land in main phases
- `:cast-spell` — Cast a card
- `:pass` — End phase/turn

**NO creature combat actions** — no `:attack` or `:declare-attackers` actions

---

## 6. EPIC & ROADMAP STATUS

### fizzle-u07p: Creatures and Combat

**Status**: OPEN (P4)
**Dependencies**: ✓ fizzle-r9bn (Scripted Bot) COMPLETE

**Vision**:
- Creatures can attack and block
- Combat phases become meaningful
- Bots can play creatures and attack each turn
- Damage dealing, creature death, combat math

**Design Notes**:
- "Fuzziest scope in the tranche — will be fully refined when we get here"
- "For practice tool purposes, may be significantly simplified vs full MTG rules"
- "Player-directed blocking decisions possible"

### Roadmap Vision (fizzle-roadmap.md)

Under "Future Work" → "Creatures and Combat":
- Creatures with power/toughness
- Declare attackers, combat damage
- Blocking (simplified — opponent's creatures always attack, player decides blocks)
- Creature-based triggers

---

## 7. ARCHITECTURE ASSESSMENT

### Required Infrastructure Additions

#### 1. Database Schema Changes
- Add `:object/power` and `:object/toughness` fields
- Add `:object/damage` field to track damage taken
- Add `:object/attacking?` to mark attackers
- Add `:object/blocked-by` and `:object/blocks` for block tracking
- Game state: `:game/declared-attackers`, `:game/declared-blockers`

#### 2. Card Definition Changes
- Add `:card/power` and `:card/toughness` to card structure
- Consider ability registration for "attack triggers", "combat damage triggers"

#### 3. Effect System Additions
- New effect type: `:apply-damage-to-creature` (damage to specific creature)
- New effect type: `:creature-death` or `:destroy-from-damage`

#### 4. Rules Engine Additions
- Combat phase handler: declare attackers, declare blockers
- Damage assignment logic
- Creature death state-based action
- Combat damage trigger execution
- Reset creature damage/tapped state at turn transitions

#### 5. Bot System Additions
- New bot action: `:declare-attackers` (select creatures, declare targets)
- New bot rule condition: `:can-attack`

#### 6. Event System Additions
- New re-frame events: `::declare-attackers`, `::declare-blockers`
- Combat phase automation or player-driven UI

#### 7. View/UI Additions
- Attacker/blocker UI indicators
- Attack/block decision flows
- Damage counters visible on creatures
- Combat math display (e.g., "2/3 with 1 damage")

---

## 8. DESIGN DECISIONS ALREADY IMPLICIT

### Simplification Approach

From epic and design doc:
- "Simplified vs full MTG rules" — no priority passes during combat
- "Player-directed blocking" — Player chooses how opponent blocks
- Creatures "always attack" unless restricted

### What's NOT Mentioned

- No double strike, lifelink, infect, first strike initially
- No trample math beyond "unblocked creatures"
- No creature types/race-based effects yet
- No combat-triggered abilities yet (but triggers system is ready)
- No creature tokens initially

---

## 9. VERIFICATION: DESIGN VS REALITY

✓ Card system supports creatures (Just no cards defined yet)
✓ Database can track creatures (Schema additions needed)
✓ Effect system can apply damage (Currently player-only; needs creature targeting)
✓ Combat phase exists (Label only; no logic)
✓ Bot system ready for creature actions (Protocol extensible)
✗ No power/toughness tracking (Must add to schema)
✗ No damage assignment (Must implement)
✗ No creature death logic (Must add SBA)
✗ No blocking UI (Must build)

---

## 10. NEXT STEPS (RECOMMENDATIONS)

1. **Schema Design** — Define creature stats fields, damage tracking, combat state
2. **Combat Phase Breakdown** — Design event handlers for declare attackers/blockers
3. **Card Examples** — Create 3-5 simple creature cards to test infrastructure
4. **Damage Assignment** — Implement creature damage effect + death SBA
5. **Bot Extension** — Add creature declaration actions to bot system
6. **Integration Testing** — Test creature attack → damage → death cycle

---

## 11. SUMMARY TABLE

| Aspect | Status | Details |
|--------|--------|---------|
| **Creature Type Support** | ✓ Exists | But no creatures in pool |
| **Power/Toughness Fields** | ✗ Missing | Need schema additions |
| **Combat Phase** | ✓ Exists | Phase label only, no logic |
| **Damage to Creatures** | ✗ Missing | Need effect type + assignment |
| **Creature Death** | ✗ Missing | Need SBA + removal logic |
| **Blocking UI** | ✗ Missing | Need event handlers + views |
| **Bot Creature Actions** | ✗ Missing | Protocol extensible, needs implementation |
| **Creature Triggers** | ✓ Possible | Trigger system ready |
| **Card Pool** | 0/50 creatures | No creature cards defined |
