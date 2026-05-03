# Mana Ability Parallel Paths (fizzle-4cpn) — 2026-04-30

Complete inventory: [fizzle-4cpn-mana-ability-parallel-paths.md](fizzle-4cpn-mana-ability-parallel-paths.md)
- **Two paths:** Card abilities (`:card/abilities`) vs Granted abilities (`:object/grants`); diverge at render→event→selection, converge at engine
- **Essential divergences:** Separate renders/events (grants ephemeral), skipped tap check (by design)
- **Accidental divergences:** No selection for grants (none currently need it), **no history** (blocks undo/fork, shared with bot gap), **no can-activate? check** (footgun)
- **Key chokepoints:** render (battlefield.cljs:31,67), events (abilities.cljs:28,394), selection (mana_ability.cljs:75), engine (mana_activation.cljs:107)

---

# Engine Integration Test Gaps (2026-04-08)

4 P2 testing tasks audited: [engine_integration_test_gaps_2026_04_08.md](engine_integration_test_gaps_2026_04_08.md)
- **40 engine test files**, 4 critical gaps: fizzle-6p9p (spell→SBA), fizzle-orq7 (triggers), fizzle-t2j3 (opponent SBAs), fizzle-rcaw (effects→SBA chain)
- **6/8 stack-item types untested**, 4 trigger types missing, human player SBAs missing, cascading SBAs missing
- **Key chokepoints**: `resolution.cljs:29` (resolve-stack-item), `state_based.cljs:66` (check-and-execute-sbas), `events/resolution.cljs:64` (resolve-one-item)

---

# Selection Builder Audit — 2 Missing Lifecycle Keys (2026-04-04)

Complete audit: [selection_builder_audit_2026_04_04.md](selection_builder_audit_2026_04_04.md)
- **29 builders checked** across 8 files (core, storm, library, costs, zone_ops, targeting, combat, land_types, untap)
- **✗ 2 HIGH-risk failures**: `build-order-bottom-selection` (lib:243) and `build-order-top-selection` (lib:266) missing `:selection/lifecycle`
- **✓ 23 fully compliant**, ⚠ 4 conditionally compliant (lifecycle optional per spec)
- **5 metadata keys**: `:selection/pattern` (safe, not load-bearing)

---

# Spec Validation Gaps — 7 Unvalidated Data Boundaries (2026-04-04)

Comprehensive audit: [spec_validation_gaps.md](spec_validation_gaps.md)
- **3 HIGH-risk gaps**: Mana pools (color key divergence), player init, object restoration
- **4 MEDIUM-risk gaps**: Triggers (type mapping), grants, history entries, continuations
- **Exact locations**: File paths, line numbers, data shapes for all 7 boundaries
- **Implementation pattern**: Follow card_spec.cljs style — multimethods per type, dev-only validation

---

# cljs.spec Duplication & test.check Readiness (2026-04-04)

Complete audit of 5 spec files across 2115 lines: [spec_duplication_analysis_2026_04_04.md](spec_duplication_analysis_2026_04_04.md)
- **154 total s/def predicates** across 5 isolated files (card, stack, selection, action specs + util)
- **92 defmethods** in 4 multimethods (card-spec 42, stack-spec 16, selection-spec 31, action-spec 3)
- **No cross-file duplication** — each phase owns its namespace (no shared requires)
- **3 consolidation opportunities**: player-id base spec, UUID object-id, collection-flexible pattern
- **test.check MISSING** — not in package.json; current approach uses 84 manual minimal-valid examples covering 100% of types
- **4 production chokepoints**: card loading, selection creation, stack-item creation, bot action decision

---

# Phase 3 Spec Adoption: Stack Items and Bot Actions (2026-04-03)

Complete field catalogs and validation patterns for stack-item and bot-action specs: [phase_3_spec_investigation.md](phase_3_spec_investigation.md)
- **10 stack-item types**: spell, storm-copy, activated-ability, permanent-entered, storm, declare-attackers, declare-blockers, combat-damage, delayed-trigger, state-check-trigger (+ 6 card-trigger types: permanent-tapped, creature-attacked, land-entered, etb, triggered-ability, phase-entered)
- **3 bot actions**: pass, cast-spell, play-land
- **Chokepoints**: `engine/stack.cljs:create-stack-item`, `bots/decisions.cljs:bot-decide-action`
- **Patterns**: 16 defmethods (stack-items), 3 defmethods (actions); minimal-valid maps; dev-only validation via goog.DEBUG

---

# Selection System Multi-Spec Validation (2026-04-03)

Phase 2 design reference with complete type catalog, builder shapes, hierarchy, validation chokepoint: [selection_multi_spec_investigation_2026_04_03.md](selection_multi_spec_investigation_2026_04_03.md)
- **All 21 types**: Zone-pick (7), Accumulator (3), Reorder (4), Library ops (3), Targeting (3), Pre-cast costs (4), Modal (1), Pay-X-life (1)
- **Chokepoint**: `selection/core.cljs:426` in `confirm-selection-impl` — single validation dispatch point
- **Hierarchy**: 4 patterns (zone-pick, accumulator, reorder, builder-declared-chain) + 2 cost-chain patterns
- **Base keys**: `:selection/type :player-id :selected :spell-id :remaining-effects :validation :auto-confirm? :lifecycle`
- **Phase 1 reference**: `engine/card_spec.cljs` multimethod pattern — 29 defmethods for effects, parallel structure expected for selections

---

# cljs.spec Adoption — Data Contract Boundaries (2026-04-02)

Complete audit of data shapes, validation gaps, and spec coverage: [cljs_spec_adoption_investigation.md](cljs_spec_adoption_investigation.md)
- **Existing**: `engine/card_spec.cljs` (373 lines) already uses cljs.spec — complete card/effect/ability/trigger specs
- **Gaps**: No specs for selections (21 types cataloged ✅), stack items (6+ types), bot actions, re-frame events
- **Friction tiers**: T1 polymorphic effects ✅, T2 selection maps (Phase 2 design ready), T3 stack items (partial), T4 bot actions (unvalidated)
- **Entry point**: Card validation at `events/init.cljs:107`; selection validation at `selection/core.cljs:426`
- **Roadmap**: Card specs done ✅; selection specs (Phase 2) → stack items → bot system → re-frame

---

# Selection System Data Flows & Module Interactions (2026-04-02)

5 entry points traced with implicit ordering: [selection_system_data_flows.md](selection_system_data_flows.md)
- **Entry points**: Cast-spell, confirm-selection, stack-resolution, ability-activation, toggle-selection
- **Implicit dependencies**: 17+ execute-confirmed-selection defmethods, multimethod hierarchy, shared app-db keys
- **Ordering invariants**: Confirm-selection-impl enforces immutable 8-step sequence; deferred-entry ONLY processed when chain complete
- **Continuation protocol**: Implicit chaining via `:then` field (risk: no max-depth guard); 2 active continuations
- **Complection**: 3 separate modal concepts, backward-signaling deferred-entry, lifecycle declared by builders not executors

---

# Director System: Human vs Bot Path Asymmetry (2026-04-01)

Critical structural investigation: [director_human_bot_asymmetry.md](director_human_bot_asymmetry.md)
- **Entry points**: 6 human (all re-frame events with pending-entry), 1 bot (director only)
- **Convergence**: Both paths call same engine functions (cast-spell-handler, play-land, activate-mana-ability)
- **Divergence**: Human sets :history/pending-entry (recorded); bot skips entirely (invisible to history system)
- **Duplicate code**: find-bot-land-to-play in both director.cljs:82 and bots/interceptor.cljs:163 (interceptor copy is dead)
- **History gap**: Bot actions during director loop bypass history recording—undo/fork with bot actions breaks consistency
- **Architecture question**: Is director a game orchestrator (should record history) or bot dispatcher (current behavior)?

---

# Bot Subsystem Architecture (2026-04-01)

Complete module-level investigation: [bot_subsystem_architecture.md](bot_subsystem_architecture.md)
- **Structure**: 4 files, 520 LOC; protocol.cljs (clean API), definitions.cljs (pure data), rules.cljs (condition multimethod), interceptor.cljs (dead)
- **Two paths**: Director path active (inline bot-act calls), Interceptor path dead (::bot-decide never dispatched by db_effect)
- **Fan-in**: bots.protocol used by 6 modules; bots.interceptor fan-out 6 but unreachable
- **Dead code**: bots/interceptor.cljs lines 252-261 (::bot-decide, ::bot-action-complete); tests still pass (burn_integration_test.cljs, interceptor_test.cljs)
- **Tight coupling**: opponent deck initialization (4 call sites), bot specs without schema validation, volatile! in find-tap-sequence
- **Critical**: Action-pending guard (line 192) protects against race condition that no longer occurs; bot/action-pending?, bot/action-count keys unused in production

---

# Share/Snapshot System (2026-04-01)

Complete flow documented: [share_snapshot_system_investigation.md](share_snapshot_system_investigation.md)
- **Encode**: game-db → portable map → binary → base64url → URL #s=...
- **Restore**: decode → fresh Datascript db + empty history (no undo/fork possible)
- **Director**: Pure sync game loop orchestrator replaces dispatch-later architecture (epic fizzle-bcz9)
- **Why no actions after restore**: Priority/stops + can-cast?/can-play-land? checks restrict UI; empty history blocks undo

---

# Modal spell selection testing gap (c6e1003)

Test helpers bypass production validation pipeline; allows subtle `contains?` vector-vs-set bugs to ship.
See: [modal_selection_testing_gap.md](modal_selection_testing_gap.md) + TESTING_GAP_INVESTIGATION.md (project root)

---

# Bot System Quality Issues (2026-03-27)

**Critical Issues**: Action-count safety limit (forced pass after 20 actions), multi-color mana cost broken, incomplete turn cycle (combat never implemented)
**Dead Code**: `bot-choose-attackers` function never called, `:bot/attack-strategy` unused, no `:declare-attackers` handler
**Design Gaps**: Phase action one-per-phase limits sequencing, complex priority auto-pass heuristic, condition eval only from hand
Full report: [bot_system_findings.md](bot_system_findings.md) | Detailed investigation: BOT_SYSTEM_INVESTIGATION.md (project root)

---

# Creatures & Combat System Status (2026-03-10 UPDATE)

**CURRENT STATE**: Minimal infrastructure, epic fizzle-u07p OPEN (P4)
- **UI Infrastructure**: Creature sections exist (battlefield.cljs, subs/game.cljs, card_styles.cljs) but empty
- **Card Pool**: 0/50 creatures defined; TWO CREATURES NOW EXIST: nimble_mongoose.cljs, goblin_welder.cljs
  - Both have `:card/power` and `:card/toughness` defined at card level
  - Goblin Welder has activated ability with multi-zone targeting (welder-swap effect)
  - Both marked `:card/types #{:creature}`
- **Combat Phase**: Label exists in rules.cljs, priority.cljs — no logic implementation
- **Database**: Schema has `:object/power`, `:object/toughness`, `:object/damage-marked` fields (lines 47-50)
  - Also has `:object/attacking`, `:object/blocking`, `:object/summoning-sick`, `:object/is-token`
- **Damage Effects**: `:deal-damage` exists in effects/life.cljs but targets players only, not creatures
- **Bot System**: No `:declare-attackers` action; goldfish/burn bots only support `:play-land` and `:cast-spell`
- **Static Abilities**: Nimble Mongoose uses `:card/static-abilities` with `:static/type :pt-modifier` (threshold-based)

**CARD PATTERN CORRECTIONS** (2026-03-10):
- `:card/power` and `:card/toughness` are defined at card level (not object level)
- `:card/static-abilities` array for passive effects (pt-modifier visible in schema as :apply-pt-modifier effect type)
- `:card/modes` array for modal spells (Blue Elemental Blast pattern)
- `:card/alternate-costs` array for flashback/alternate casting
- Multi-targeting uses `:target/same-controller-as` for controller matching (Goblin Welder pattern)

Full investigation: see INVESTIGATION_REPORT.md in project root

---

# Selection System Architecture

## Data Flow Structure (events/selection/ ↔ engine/)

**Declared**: Game Orchestration → Interpretation Core (DAG)
**Reality**: Cycle with pause points + protocol-based composition

Key flows:
- **Cast-spell**: Player UI → game.cljs cast-spell-handler → cost selection → targeting selection → mana allocation → rules/cast-spell → stack-item
- **Resolution**: resolve-one-item → engine/resolution → effects/reduce-effects → if :needs-selection → orchestration builder → toggle+confirm → execute-confirmed-selection → resume remaining-effects
- **Targeting**: Cast-time (pre-stack via select) vs resolution-time (via :any-player effect dispatch)
- **Modal**: spell-mode-selection (app-db field, not game/pending-selection) → casting-mode → X-cost/targeting checks → cast
- **Multi-selection**: Chaining via :pending-selection return from execute-confirmed-selection; remaining-effects cloned into each selection state
- **Storm**: Special :needs-storm-split signal pauses resolution before copy creation; allocation selection creates copies with individual targets, consumes original stack-item

Selection types: cast-time-targeting, player-target, tutor, scry, peek-and-select, graveyard-return, discard, hand-reveal-discard, discard-specific-cost, x-mana-cost, mana-allocation, exile-cards-cost, return-land-cost, pile-choice, order-bottom, peek-and-reorder, chain-bounce, chain-bounce-target, unless-pay, storm-split (19 types, domain modules register defmethods)

## Key Files
- `events/game.cljs` — Main cast flow, resolve-one-item entry, cost/targeting cascade
- `events/selection/core.cljs` — Mechanism (multimethods, confirm/toggle, cleanup)
- `events/selection/{targeting,library,zone_ops,costs,storm}.cljs` — Domain-specific builders & handlers
- `engine/resolution.cljs` — Stack-item resolution, effect extraction, target pre-resolution
- `engine/effects.cljs` — reduce-effects loop, effect dispatch, interactive detection
- `engine/stack.cljs` — resolve-effect-target (symbolic → concrete)

Detailed reference: `SELECTION_SYSTEM_FLOWS.md`

# Card Implementation Patterns

## Card Structure
- **Location**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/` organized by color (black/, blue/, white/, red/, green/, multicolor/, lands/, artifacts/)
- **Format**: Single card per file exports `card` def (or `cards` vector for cycles like basic_lands)
- **Registry**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/registry.cljs` explicitly requires all card namespaces and exports `all-cards` vector
  - Contains 47 card definitions: 22 individual cards + 3 cycle files (basic_lands 5, pain_lands 10, fetch_lands 10)

## Core Card Definition Fields
```
{:card/id             :keyword-id
 :card/name           "Full Name"
 :card/cmc            2
 :card/mana-cost      {:blue 1 :white 2}      ; nil keys omitted
 :card/colors         #{:blue :white}
 :card/types          #{:instant :sorcery}    ; Types are keywords
 :card/text           "Oracle text..."
 :card/targeting      [...]    ; OPTIONAL: for cast-time targeting
 :card/effects        [...]    ; Card's main effects
 :card/abilities      [...]    ; OPTIONAL: Activated abilities
 :card/triggers       [...]    ; OPTIONAL: Triggered abilities
 :card/etb-effects    [...]}   ; OPTIONAL: Enter-battlefield triggers
```

## Targeting System
- **Location**: `card/targeting` is a VECTOR of targeting requirements
- **Structure**:
```
{:target/id              :target-artifact  ; Unique ID within card
 :target/type           :object            ; :object or :player
 :target/zone           :battlefield       ; For objects: :battlefield, :graveyard, :stack, etc.
 :target/controller     :any               ; :any or :controller
 :target/criteria       {:match/types #{:artifact}}  ; Type-based filtering
 :target/required       true}              ; Must be satisfied to cast
```
- Available criteria keys: `:match/types`, `:match/not-types`, `:match/colors`
- Targeting applies at cast time; targets resolved at resolution

## Effect Types (19 Implemented)
- `:add-mana` — Add mana to player's pool
- `:mill` — Mill cards from library to graveyard
- `:lose-life` / `:gain-life` / `:deal-damage` — Life changes
- `:add-counters` — Add counters to permanent
- `:draw` — Draw cards (interactive if :any-player)
- `:discard` / `:discard-hand` — Discard cards
- `:destroy` — Destroy target permanent
- `:sacrifice` — Sacrifice target permanent
- `:exile-self` / `:exile-zone` — Exile effects
- `:bounce` — Return to hand
- `:return-from-graveyard` — Return from graveyard (interactive)
- `:tutor` — Search library (interactive)
- `:scry` — Look and rearrange library (interactive)
- `:peek-and-select` — Peek and choose (interactive)
- `:grant-flashback` — Grant flashback alternate cost
- `:add-restriction` — Restrict player actions (e.g., cannot-cast-instants-sorceries)
- `:discard-from-revealed-hand` — Reveal and choose discard
- `:gain-life-equal-to-cmc` — Gain life = target's CMC
- `:chain-bounce` — Chain of Vapor's chain mechanic

## Effect Structure
```
{:effect/type           :destroy
 :effect/target         :target-artifact  ; Or :self, :opponent, :controller, :any-player
 :effect/target-ref     :target-artifact  ; For cast-time targeting (resolved at resolution)
 :effect/amount         1                 ; Count/amount as needed
 :effect/condition      {...}             ; OPTIONAL: conditional execution
 :effect/mana           {:blue 2}
 :effect/counters       {:charge 3}
 :effect/criteria       {}
 :effect/selection      :player}          ; For interactive effects
```

## Test File Structure
- **Location**: `/Users/abugosh/g/fizzle/src/test/fizzle/cards/<color>/<card>_test.cljs`
- **Mandatory test categories**:
  - **A**: Card definition verification (all fields with exact values)
  - **B**: Cast-resolve happy path (full cast/resolve cycle)
  - **C**: Cannot-cast guards (mana, targets, zone restrictions)
  - **D**: Storm count (verify +1 on cast)
  - Optional: **E-I** for selection, targeting, edge cases, flashback, triggers
- **Min tests**: Simple spell 5, targeted 8, selection 8, land+ability 8, flashback 12
- **Pattern**: Use `rules/cast-spell` → `game/resolve-one-item`, not manual copies
- **Helpers**: `th/create-test-db`, `th/add-card-to-zone`, `th/add-opponent`, `th/add-cards-to-library`

## Resolution System
- **File**: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/resolution.cljs`
- **Entry**: `resolve-stack-item` multimethod dispatches on `:stack-item/type`
- **Flow**: Spell resolution reads targets from `:stack-item/targets`, pre-resolves target refs, executes via `effects/reduce-effects`
- **Interactive effects** return `{:db db :needs-selection effect :remaining-effects [...]}`
- **Target illegality**: Spell fizzles (returns `{:db db :fizzled? true}`), still moves off stack

## Existing Instant Spell Examples

### Crumble (Green instant, destroy artifact)
- **File**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/green/crumble.cljs`
- **Effects**: `:destroy` + `:gain-life-equal-to-cmc`
- **Targeting**: Artifacts only (`{:match/types #{:artifact}}`)
- **Key pattern**: Target references resolved from `:effect/target-ref` at resolution time

### Chain of Vapor (Blue instant, bounce with chain)
- **File**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/chain_of_vapor.cljs`
- **Effects**: `:bounce` + `:chain-bounce` (interactive chain mechanic)
- **Targeting**: Nonland permanents
- **Key pattern**: Custom `:chain-bounce` effect handles interactive controller choice

### Abeyance (White instant, restrict player)
- **File**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/white/abeyance.cljs`
- **Effects**: Two `:add-restriction` + `:draw`
- **Targeting**: Player (`:target/type :player`, `:target/options #{:any-player}`)
- **Key pattern**: Restrictions use grants system, expires at cleanup

### Opt (Blue instant, scry then draw)
- **File**: `/Users/abugosh/g/fizzle/src/main/fizzle/cards/blue/opt.cljs`
- **Effects**: `:scry` (interactive) + `:draw`
- **Key pattern**: Interactive scry pauses, remaining draw executes after confirm

## NO Counter/Stack Targeting Yet
- No `:effect/type :counter` exists in effects.cljs
- No example cards target spells on stack
- Design doc mentions "practice forcing through countermagic" as future scenario goal
- Comments in bots/abilities.cljs note "potential Stifle response" but not implemented

## Important Conventions
- All game state in immutable Datascript db (never mutable atoms for game state)
- `:self` targets resolved by caller to object-id before calling execute-effect
- `:effect/target-ref` used for cast-time targeting; resolved at resolution time
- Target resolution happens once in resolution layer via `stack/resolve-effect-target`
- Interactive effects always return tagged `{:db db :needs-selection effect}`
- Test through production `rules/cast-spell`, never manual stack operations
