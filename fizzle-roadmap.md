# Fizzle Roadmap

**Last Updated:** February 2026

See [fizzle-design.md](fizzle-design.md) for the design vision and technical architecture.

---

## Completed Work

### Phase 1: Core Engine

Goldfish a storm deck to completion.

- Datascript schema and initial state
- Card definitions (lands, rituals, LED, cantrips)
- Mana system (pool, payment, floating mana)
- Zone transitions (library, hand, battlefield, graveyard, stack, exile)
- Spell casting (costs, stack, resolution)
- Storm keyword implementation
- Minimal UI (hand, battlefield, mana pool, cast button)

**Milestone:** Cast Dark Ritual, Cabal Ritual, Brain Freeze with storm copies

### Phase 1.5: Full Game Loop

- Turn structure with phases and steps
- Untap/upkeep/draw automation
- End step cleanup and discard to hand size
- Land plays tracking

### Phase 2: Iggy Pop Complete

All Iggy Pop maindeck and sideboard cards implemented and playable.

- Intuition with pile selection UI
- Ill-Gotten Gains with card selection (both players)
- Cunning Wish with wishboard
- Threshold tracking
- Recoup with flashback granting
- Full game UI with graveyard, action log, stack display

**Milestone:** Complete a full IGG loop and win with Brain Freeze

### Phase 3: Fork/Replay

Branch decision trees and rewind.

- Event history tracking
- Fork creation and branch switching
- Rewind to any event
- Fork visualization in action log

**Milestone:** Fork at Intuition, try two different piles, compare results

### Phase 4: Hand Sculpting & Setup

Configure starting conditions.

- **Epic 1: Tailwind + UI Component Architecture** — Tailwind CSS v4, PostCSS pipeline, CSS Grid layout, zone components extracted from monolithic core.cljs, semantic color tokens
- **Epic 2: Setup Screen + Deck/Sideboard** — Setup as landing page, deck dropdown, side-by-side sideboard swap, goldfish clock config, named presets in localStorage
- **Epic 3: Deck Import** — Moxfield/MTGGoldfish text paste import, card validation against registry, edit/delete imported decks, localStorage persistence
- **Epic 4: Hand Sculpting + London Mulligan** — Must-contain card picker, opening hand screen, London mulligan with bottom-card selection

**Milestone:** Start game with LED + IGG + Ritual in hand

### Infrastructure Work

Completed alongside the phases above:

- **Unified Stack System** — Single stack-item entity type for spells, copies, triggers, and abilities
- **Data-Driven Ability System** — Activated abilities defined as EDN data, interpreted by engine
- **Test Quality Improvement** — Multiple rounds: engine coverage, card/event corner cases, tautological test removal
- **Selection System Unification** — Unified data model, toggle handlers, confirm multimethod, auto-confirm for single-select
- **Selection Decomposition** — Extracted domain modules: core, resolution, library, targeting, costs, zone_ops
- **Interactive Effects Predicate** — Single source of truth (`interactive-effect?`) for effect interactivity
- **Unified Target Storage** — Targets stored on stack-items instead of objects; storm copy inheritance
- **Manual Mana Allocation** — MTGO-style interactive mana payment for generic costs
- **Trigger Registry Migration** — Moved from atom into Datascript for immutability

---

## Share-Ready Milestone

**Vision:** A Premodern combo practice tool where you can learn Iggy Pop, practice beating Gaea's Blessing, share tactical puzzles with the community, and goldfish multiple combo decks.

**Three pillars:**
1. **The practice story** — "I need reps beating Gaea's Blessing" is a concrete, compelling pitch
2. **Tactics puzzles** — Shareable board-state puzzles that market themselves in the community
3. **Multi-deck support** — Broader appeal across Premodern combo players

### Step 1: UI Polish

Work down the open backlog to make the tool presentable for sharing.

**Completed (share-blocking):**
- ~~Win detection and lethal announcement~~
- ~~Consistent card sorting and grouping across zones and dialogs~~
- ~~Quick undo (pop last action without creating fork)~~
- ~~Always-visible graveyard and library card counts~~
- ~~Yield All (resolve entire stack in one action)~~
- ~~Enriched history entries with spell/land names and phase details~~

**Completed (quality of life):**
- ~~Consolidate controls: unified Play + Yield instead of Cast/Play Land/Resolve~~
- ~~Remove confirm step from selection dialogs (accept choice immediately)~~
- ~~Manual mana allocation for generic costs~~

**Remaining (quality of life):**
- Show top stack item inline near Yield button
- Flash of Insight: control order of cards placed on bottom of library
- Brain Freeze: allow targeting any player with storm copy splitting

**Remaining (nice to have):**
- Keyboard shortcuts for core actions
- Session statistics (win rate, avg kill turn, fizzle tracking)
- Auto-resolve toggle
- Visual card styling (color and type differentiation)

### Step 2: Tactics System

Save, load, and share puzzles — the viral community hook.

- Puzzle data structure (game state snapshot with metadata)
- Save current game position as named puzzle with tags
- Puzzle browser UI with filtering
- EDN export/import for sharing puzzles
- Success tracking per puzzle
- PWA setup for offline support

**Milestone:** Create puzzle, close browser, reload, solve puzzle. Share puzzle EDN with another player.

### Step 3: Card Expansion (~100 cards)

Broaden from Iggy Pop to 4-5 Premodern combo decks. Many cards share existing infrastructure (rituals, cantrips, tutors already implemented).

**Target decks:**
- **Desire Storm** — Mind's Desire, Snap, Turnabout, High Tide, Cloud of Faeries
- **Trix** — Illusions of Grandeur, Donate, Necropotence, Force of Will
- **Devourer** — Phyrexian Devourer, Triskelion, Buried Alive, Worldgorger Dragon
- **Reanimator** — Entomb, Reanimate, Exhume, Animate Dead, reanimation targets
- **Aluren** — Aluren, Cavern Harpy, Wirewood Savage, Raven Familiar

Each deck requires its own decklist definition and import-ready text format. New engine capabilities may be needed (e.g., enchantment-based effects for Aluren, replacement effects for Necropotence).

### Step 4: Hate Pieces

Static opponent permanents that affect gameplay — the core "practice against disruption" story.

- Gaea's Blessing (mill trigger — the Brain Freeze interaction, see design doc Appendix D)
- Chalice of the Void, Sphere of Resistance, Null Rod, Trinisphere, Ensnaring Bridge
- Setup screen allows selecting hate piece(s) for opponent's starting battlefield
- Player must use real cards to interact (Chain of Vapor, Hurkyl's Recall, Tormod's Crypt)
- No opponent AI — goldfish with static permanents only

**Milestone:** Practice winning through Gaea's Blessing with Tormod's Crypt timing.

---

## Future Work

### Full Bot System

Active opponents with decision-making AI. Builds on the hate pieces foundation.

- Bot protocol (IBot: get-archetype, get-clock, should-act?, choose-action, get-deck)
- Burn bot (20 Mountain + 40 Lightning Bolt, configurable aggression)
- Control bot (Counterspells with configurable counter-targets and priority threshold)
- Discard bot (Duress effects with configurable priority card list)
- Manual override mode (approve/reject/redirect bot decisions)
- Bot action UI integration

**Milestone:** Win through 2 Duress effects from discard bot.

See design doc Section 7 for full bot system specification.

### Additional Polish

- Hypergeometric calculator panel (grid slot already reserved)
- Timed mode with chess-clock style game timer
- Mobile/tablet responsive layout
- Scryfall card image integration

### Cloud Sync

- Supabase integration (see design doc Appendix C for analysis)
- User authentication
- Public puzzle library with search/filter
- Puzzle upload/download
- Community features (upvoting, daily puzzle, collections)

---

## Open Backlog (bd issues)

Tracked in beads. Run `bd ready` for current available work.

**Features:**

| ID | Priority | Description |
|----|----------|-------------|
| fizzle-jesa | P2 | Design battlefield UX: layout, grouping, and visual hierarchy |
| fizzle-txiw | P2 | Keyboard shortcuts for core actions (Play, Yield, Undo) |
| fizzle-h538 | P2 | Show top stack item inline near Yield button |
| fizzle-3cjd | P2 | Flash of Insight: control order of cards on bottom |
| fizzle-zyrf | P2 | Brain Freeze: storm copy target splitting |
| fizzle-ngg7 | P3 | Auto-resolve toggle |
| fizzle-pt23 | P3 | Session statistics: win rate, avg kill turn |
| fizzle-dbkv | P3 | Visual card styling: color and type differentiation |
| fizzle-ze66 | P3 | Hypergeometric Calculator Panel (epic) |
| fizzle-n99x | P4 | Timed mode with chess-clock |
| fizzle-6o7g | P4 | Goldfish with Hate Pieces (epic) |

**Refactoring:**

| ID | Priority | Description |
|----|----------|-------------|
| fizzle-gvs7 | P3 | Refactor opponent turn: model as real turn with active-player switching |
| fizzle-qrrf | P3 | Eliminate duplicate resolve path for stack items |
| fizzle-72xq | P3 | Extract selection-valid? subscription to eliminate view validation duplication |
| fizzle-lr5i | P4 | Extract shared get-object-eid query helper |

---

*Last updated: February 2026*
