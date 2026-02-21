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

Core Iggy Pop maindeck and sideboard cards implemented and playable.

- Intuition with pile selection UI
- Ill-Gotten Gains with card selection (both players)
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

### Phase 5: Priority System & Bots

Real MTG priority passing and opponent automation.

- **Priority System** — Yield/yield-all replaces "next phase" button, hold priority for LED timing, phase stops for untap/cleanup, goldfish auto-passes
- **Bot Protocol + Goldfish Bot** — IBot multimethod protocol, opponent turn cycle with active-player switching, goldfish bot plays land per turn and passes priority
- **Heuristic Burn Bot** — 20 Mountains + 40 Lightning Bolts, bot casts through full engine path (cast-spell → stack → resolve → deal-damage), auto-taps lands for mana
- **No-Priority Phase Enforcement** — Untap and cleanup phases block player actions, enforced at the event handler level

**Milestone:** Bot opponent casts Lightning Bolt at your face during its main phase, you lose to burn pressure if you don't combo fast enough

### Infrastructure Work

Completed alongside the phases above:

- **Unified Stack System** — Single stack-item entity type for spells, copies, triggers, and abilities
- **Data-Driven Ability System** — Activated abilities defined as EDN data, interpreted by engine
- **Test Quality Improvement** — Multiple rounds: engine coverage, card/event corner cases, tautological test removal, precise assertion enforcement
- **Selection System Unification** — Unified data model, toggle handlers, confirm multimethod, auto-confirm for single-select
- **Selection Decomposition** — Extracted domain modules: core, storm, library, targeting, costs, zone_ops (~1700 lines across 6 files)
- **Tagged Effect Returns** — Interactive effects return `{:db :needs-selection}` instead of bare db; `reduce-effects` pauses at interactive effects with `:remaining-effects`
- **Unified Target Storage** — Targets stored on stack-items instead of objects; storm copy inheritance
- **Manual Mana Allocation** — MTGO-style interactive mana payment for generic costs
- **Trigger Registry Migration** — Moved from atom into Datascript for immutability
- **Unified Stack Resolution** — Single multimethod dispatch with target threading, eliminating duplicate resolve paths
- **Core Architecture Hardening** — SBA multimethod, tagged effect returns, unified targeting/cost/condition multimethods, cljs.spec card validation
- **Test Infrastructure & Strategy** — Shared test helpers, card testing strategy documentation, mandatory test categories per card type

---

## Share-Ready Milestone

**Vision:** A Premodern combo practice tool where you can goldfish multiple combo decks, test against specific hate cards and disruption scenarios, and share tactical puzzles with the community.

**Three pillars:**
1. **The practice story** — Efficient combo reps with fork/replay, hand sculpting, and configurable opponents
2. **Test against cards** — Configure specific hate pieces and disruption the bot plays, so you practice beating the cards that matter in your matchups
3. **Multi-deck support** — Broad Premodern combo card coverage for the community

### Step 1: UX Polish

Make the tool presentable for sharing.

**Completed:**
- Win detection and lethal announcement
- Consistent card sorting and grouping across zones and dialogs
- Quick undo (pop last action without creating fork)
- Always-visible graveyard and library card counts
- Yield All (resolve entire stack in one action)
- Enriched history entries with spell/land names and phase details
- Consolidated controls: unified Play + Yield instead of Cast/Play Land/Resolve
- Remove confirm step from selection dialogs (accept choice immediately)
- Manual mana allocation for generic costs
- Show top stack item inline near Yield button
- Flash of Insight: control order of cards placed on bottom of library
- Brain Freeze: storm copy target splitting
- Battlefield UX redesign: 6-row mirrored layout, opponent zone, phase bar with life totals
- Visual card styling: type border colors and MTG color identity background tints across all views
- Auto-resolve toggle (play defaults to play + yield)

**Remaining:**
- Keyboard shortcuts for core actions (Play, Yield, Undo)
- Layout refinements (stack placement, selection view improvements)
- Minor sort fixes (lands before 0-cost spells in hand)

### Step 2: Bot Scenarios

Configure specific cards and disruption the opponent plays. The bot system is not about simulating a real opponent deck — it's about putting specific hate cards and pressure in front of you so you practice beating the cards that matter.

**Completed:**
- Priority system with yield/yield-all and phase stops
- Bot protocol (IBot multimethod) with goldfish and burn archetypes
- Bot turn cycle with active-player switching
- Burn bot: casts through full engine path, auto-taps lands
- Setup screen: opponent archetype selector (Goldfish/Burn) with clock turns config

**Remaining:**
- **Bot configuration UX** — Specify cards in the opponent's deck, configure starting hand, set behavior rules. The engine supports robust rule-based bot behavior; the open design question is how to expose this to the player without dumping raw EDN. This needs its own brainstorm session.
- **Hate piece scenarios** — Bot starts with specific permanents (Seal of Cleansing, Chalice of the Void, Sphere of Resistance). Player must use real cards to interact (Chain of Vapor, Hurkyl's Recall).
- **Counterspell scenarios** — Bot holds up countermagic, player practices forcing through disruption.
- **Creature clock scenarios** — Bot plays fast creatures (e.g., Phyrexian Dreadnought) as a clock. Requires creatures and combat (see Future Work).
- **Preset scenarios** — Named out-of-the-box configurations: "Goldfish", "Burn Clock", "Hate Pieces", "Dreadnought Clock"

**Milestone:** Configure opponent to start with Seal of Cleansing on the battlefield. Practice comboing through it with Chain of Vapor.

### Step 3: Card Expansion (~100-200 cards)

Broaden from Iggy Pop to support the broader Premodern combo metagame. Many cards share existing engine infrastructure (rituals, cantrips, tutors). Focus on engine capabilities that unlock whole categories of cards.

**Engine capabilities needed:**
- Token creation (Hunting Pack, Empty the Warrens)
- Bounce effects (Chain of Vapor, Hurkyl's Recall)
- Counterspells (Counterspell, Force of Will — alternate costs)
- Reanimation (Reanimate, Exhume, Animate Dead — enchantment-based)
- Untap effects (Snap, Turnabout, Cloud of Faeries)
- Mana doubling (High Tide)
- Enchantment-based continuous effects (Aluren, Necropotence)
- Token creatures with stats and combat
- Delayed triggers (Urza's Bauble — draw next upkeep)
- Name-a-card effects (Cabal Therapy, Meddling Mage)
- Skip turn (Meditate)

**Card categories (rough priority):**
- Format staples that appear across many decks (Force of Will, Brainstorm, Ponder, Duress)
- Combo engines (Mind's Desire, High Tide, Aluren, Worldgorger Dragon)
- Interaction and answers (counterspells, bounce, discard, hate pieces)
- Missing Iggy Pop cards (Cunning Wish, Cabal Therapy, Urza's Bauble, etc.)

**Milestone:** Goldfish a Desire Storm deck to completion. Import a Reanimator list and practice the combo.

### Step 4: Tactics System

Save, load, and share puzzles — the community engagement hook.

- Puzzle data structure (game state snapshot with metadata)
- Save current game position as named puzzle with tags
- Puzzle browser UI with filtering
- EDN export/import for sharing puzzles
- Success tracking per puzzle

**Milestone:** Create puzzle, close browser, reload, solve puzzle. Share puzzle EDN with another player.

---

## Future Work

### Creatures and Combat

Add creature support and a combat system. Opens up creature-based win conditions (Hunting Pack tokens, Xantid Swarm) and creature clock bot scenarios (Phyrexian Dreadnought).

- Creatures with power/toughness
- Declare attackers, combat damage
- Blocking (simplified — opponent's creatures always attack, player decides blocks)
- Creature-based triggers (attack triggers, combat damage triggers)

### Additional Polish

- Hypergeometric calculator panel
- Timed mode with chess-clock style game timer
- Scryfall card image integration
- Mobile/tablet responsive layout
- Session statistics (win rate, avg kill turn, fizzle tracking)

### Cloud Sync

- Supabase integration (see design doc Appendix C)
- User authentication
- Public puzzle library with search/filter
- Community features (upvoting, daily puzzle, collections)

---

## Open Backlog (bd issues)

Tracked in beads. Run `bd ready` for current available work.

**UX Polish:**

| ID | Priority | Description |
|----|----------|-------------|
| fizzle-txiw | P2 | Keyboard shortcuts for core actions (Play, Yield, Undo) |
| fizzle-t5ux | P3 | Rearrange game layout: stack between battlefields, phase bar above controls |
| fizzle-ny51 | P3 | Mana-cost piled layout for card selection views |
| fizzle-ofsq | P3 | Sort lands before 0-cost spells in hand display |

**Features:**

| ID | Priority | Description |
|----|----------|-------------|
| fizzle-ze66 | P3 | Hypergeometric Calculator Panel (epic) |
| fizzle-n99x | P4 | Timed mode with chess-clock |
| fizzle-u07p | P4 | Creatures and Combat (epic) |

**Cleanup:**

| ID | Priority | Description |
|----|----------|-------------|
| fizzle-jweu | P3 | Remove clock-turns concept from codebase |
| fizzle-lr5i | P4 | Extract shared get-object-eid query helper |

---

*Last updated: February 2026*
