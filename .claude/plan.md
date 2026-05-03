# Creatures & Combat Investigation Summary

**Investigation Date**: 2026-03-04
**Status**: Complete — findings in INVESTIGATION_REPORT.md

## Quick Facts

**Epic**: `fizzle-u07p` (Creatures and Combat) — OPEN, P4
**Dependencies**: ✓ fizzle-r9bn (Scripted Bot) COMPLETE
**Card Pool**: 0/50 creatures defined
**Infrastructure**: 40% UI ready, 0% logic implemented

## Key Files to Know

### Creature UI (Ready)
- `/Users/abugosh/g/fizzle/src/main/fizzle/views/battlefield.cljs` — Battlefield 6-row layout with creature rows
- `/Users/abugosh/g/fizzle/src/main/fizzle/subs/game.cljs` — Subscription that groups objects: `{:creatures ... :other ... :lands ...}`
- `/Users/abugosh/g/fizzle/src/main/fizzle/views/card_styles.cljs` — Green border color for creature type
- `/Users/abugosh/g/fizzle/src/main/fizzle/views/setup.cljs` — Creature category in deck import view

### Schema (Needs Work)
- `/Users/abugosh/g/fizzle/src/main/fizzle/db/schema.cljs` — Object entity (missing :object/power, :object/toughness, :object/damage)
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/card_spec.cljs` — Valid card types (has :creature)

### Combat (Needs Everything)
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/rules.cljs` — Combat phase label only (lines 28-31)
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/priority.cljs` — Priority phases definition (lines 5-8)

### Damage (Exists, Needs Extension)
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects/life.cljs` — :deal-damage effect (lines 35-45) but player-only
- `/Users/abugosh/g/fizzle/src/main/fizzle/engine/effects.cljs` — Effect multimethod dispatch

### Bots (Needs Extension)
- `/Users/abugosh/g/fizzle/src/main/fizzle/bots/definitions.cljs` — Bot specs (goldfish, burn)
- `/Users/abugosh/g/fizzle/src/main/fizzle/bots/protocol.cljs` — Bot phase actions multimethod

### Card Structure (Reference)
- `/Users/abugosh/g/fizzle/src/main/fizzle/cards/registry.cljs` — All 47 cards (0 creatures)
- `/Users/abugosh/g/fizzle/src/main/fizzle/cards/artifacts/lions_eye_diamond.cljs` — Example card structure

## Implementation Path

1. **Schema First** — Add creature fields to /db/schema.cljs
2. **Example Cards** — Define 2-3 simple creatures (e.g., 1/1 vanilla, 2/2 vanilla, 2/1 haste)
3. **Damage Effect** — Extend effects/life.cljs with :apply-damage-to-creature
4. **Combat Phase** — Implement ::declare-attackers event in events/game.cljs
5. **Bot Actions** — Add :declare-attackers to bot protocol
6. **Testing** — Creature cast → attack → damage → death cycle

## Design Constraints (Confirmed)

- Simplified combat (no priority passes, no priority during combat)
- Player-directed blocking (opponent's creatures always attack, player chooses blocks)
- No keyword abilities initially (first strike, lifelink, infect, etc. deferred)
- Trigger system ready for combat triggers (not needed initially)

