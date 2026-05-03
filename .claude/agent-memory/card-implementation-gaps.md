# Card Implementation Gaps Analysis (2026-02-15)

## Overview
Analysis of what exists and what's missing for three hypothetical card implementations:
1. Decree of Justice - Cycling with triggered token creation
2. Ad Nauseam - Iterative reveal-and-choose loop
3. Chain of Vapor - Bounce with opponent choice and chaining

---

## Card 1: Decree of Justice

**MTG Mechanics:** Cycling (discard from hand, pay cost, draw a card), triggered ability on cycle (pay X to create X 1/1 tokens), token creation, X mana cost payment.

### What EXISTS

#### Cycling Mechanism
- **Cycle keyword trigger type**: `:card-cycled` trigger type defined in schema (db/schema.cljs:84)
- **Trigger system**: `engine/trigger-dispatch.cljs` handles trigger detection and dispatch
- **Trigger registry**: Triggers stored as entities in Datascript with `:trigger/event-type :card-cycled`

#### X Mana Cost Support
- **X cost detection**: `has-mana-x-cost?` in `events/selection/costs.cljs`
- **X mana selection**: `build-x-mana-selection` builder (costs.cljs)
- **X cost execution**: `execute-confirmed-selection :x-mana-cost` handler (costs.cljs)
- **X value storage**: `:object/x-value` field on game objects (schema.cljs:43)
- **Mana cost system**: Full cost payment infrastructure in `engine/costs.cljs`

#### Activated Ability Support
- **Ability types**: `:mana` and `:activated` types exist (seen in cards)
- **Activation system**: `engine/abilities.cljs` provides `can-activate?`, `pay-all-costs`, `activate-ability`
- **Ability costs**: Support for `:tap`, `:remove-counter`, `:sacrifice-self`, `:discard-hand`, `:pay-life`, `:mana`

#### Draw Support
- **Draw effect**: `:draw` effect type implemented (effects.cljs:302-341)
- **Discard as cost**: `:discard` effect type (effects.cljs:474-493)

#### Triggered Ability Execution
- **Stack item type**: `:permanent-tapped` and other trigger types have dedicated handling
- **Trigger resolution**: `resolve-stack-item :default` method handles effects (resolution.cljs:39-62)

### What's MISSING

#### Token Creation (CRITICAL)
- **No token effect type**: No `:create-token` or `:create-tokens` effect method in engine/effects.cljs
- **No token objects**: Schema has no `:token/type` or `object/is-token` field
- **No token creation infrastructure**: No builder for creating game objects that are temporary
- **Token lifecycle**: No mechanism for auto-removal when token leaves the stack/zone

**Module changes needed:**
1. `db/schema.cljs` - Add `:object/is-token` field and potentially token-specific metadata
2. `engine/effects.cljs` - Add `defmethod execute-effect-impl :create-token` handler
3. `engine/zones.cljs` - Extend `move-to-zone` to handle token removal on leaving certain zones
4. Possibly `engine/rules.cljs` - Add helper to determine token status

#### Cycling Ability Type
- **No cycling ability type**: Cycling is not an `:ability/type` value
- **Cycling as cost**: Cycling needs to be modeled as either an ability with special cost or a special card mechanic

**Module changes needed:**
1. `cards/` - Model cycling as `:ability/type :cycling` with custom cost handler
2. `engine/costs.cljs` - Add `:cycling` cost type that discards from hand + draws a card
3. `events/abilities.cljs` - May need special handling for cycling trigger dispatch

---

## Card 2: Ad Nauseam

**MTG Mechanics:** Iterative reveal-and-choose (reveal top card of library one at a time, player decides to keep going or stop, paying life equal to each card's mana cost), repeated player interaction.

### What EXISTS

#### Life Payment Support
- **Pay life as cost**: `:pay-life` cost type in `engine/costs.cljs:189-228`
- **Life checks**: `can-pay? :pay-life` validates controller has sufficient life
- **Life effects**: `:lose-life` effect type (effects.cljs:183-210)

#### Reveal from Library
- **Peek mechanism**: `:peek-and-select` effect type (effects.cljs:530-544)
- **Library access**: `q/get-top-n-library` query exists in `db/queries.cljs`

#### Single Card Selection
- **Peek selection type**: `execute-confirmed-selection :peek-and-select` (selection/library.cljs)
- **Selection UI**: Modal system exists for player choices

#### X Effects and Remaining Effects
- **Effect chaining**: `effects/reduce-effects` handles sequential effects with interactive pauses (effects.cljs:108-133)
- **Remaining effects tracking**: Selection system tracks `:remaining-effects` for effect chains

### What's MISSING

#### Iterative/Looping Selection (CRITICAL)
- **No loop/repeat mechanism**: Selection system executes once; no built-in way to re-trigger same selection
- **No "continue/stop" choice**: Each effect triggers one selection; no "reveal next or stop" toggle
- **No conditional re-entry**: After confirming a selection, can't conditionally restart the same selection

**Module changes needed:**
1. `events/selection/core.cljs` - Add new selection type `:iterative-choice` multimethod
2. `events/selection/library.cljs` (new file or extend) - Builder and executor for "reveal next or stop" loop
3. Effect system - Either `:iterative-reveal` effect type or special `:ad-nauseam` effect that manages its own loop

#### Conditional Life Payment
- **Pay life PER card revealed**: Current `:pay-life` cost is atomic; no per-iteration payment
- **Variable payment amount**: Cost system doesn't support "pay X equal to revealed card's mana cost"

**Module changes needed:**
1. `engine/costs.cljs` - Add `:pay-variable-life` cost type that accepts amount from effect data
2. `engine/effects.cljs` - Add `:variable-life-loss` effect type or extend `:lose-life` to accept mana cost lookup

#### Mana Cost Lookup
- **Card mana cost not queryable from object**: Objects reference card definitions but no built-in cost lookup helper
- **Cost data structure**: Would need to extract from card def and apply to effect

**Module changes needed:**
1. `db/queries.cljs` - Add `get-card-mana-cost` helper
2. Effect execution - Cost lookup in `:lose-life` or new effect type

---

## Card 3: Chain of Vapor

**MTG Mechanics:** Bounce (return target nonland permanent to owner's hand), opponent choice (controller of bounced permanent may sacrifice a land), copy/chain mechanic (if they sacrifice, copy the spell targeting another permanent — can chain indefinitely).

### What EXISTS

#### Bounce/Return to Hand
- **No direct bounce effect**: No `:bounce` or `:return-to-hand` effect type
- **Return from graveyard only**: `:return-from-graveyard` exists (effects.cljs:393-427) but not from battlefield
- **Zone movement infrastructure**: `engine/zones.cljs` provides `move-to-zone` capability

**Module changes needed (minor):**
1. `engine/effects.cljs` - Add `:bounce` or `:return-to-hand` effect (can be ~10 lines, similar to return-from-graveyard)

#### Spell Copy Mechanism
- **Storm copy creation**: `create-spell-copy` exists in `engine/triggers.cljs:35-93`
- **Copy inheritance**: Copies inherit targets from source `:stack-item/targets`
- **Copy stack-items**: `:storm-copy` type resolves via standard resolution path
- **Is-copy tracking**: `:object/is-copy` field in schema (schema.cljs:41)

#### Target Inheritance
- **Copy target support**: `create-spell-copy` can accept `target-override` parameter
- **Target bridging**: Resolution system bridges `:object/targets` to `:stack-item/targets`

### What's MISSING

#### Opponent Choice / Conditional Logic (CRITICAL)
- **No opponent-choice effect**: No way to offer opponent a choice (sacrifice land or not)
- **No conditional execution**: Effects are deterministic; no "if opponent chose X, do Y" branching
- **No opponent interaction**: Opponent is passive (goldfish); no mechanism for opponent decisions affecting resolution

**Module changes needed:**
1. **New selection type**: `execute-confirmed-selection :opponent-choice` or `:conditional-choice`
2. `events/selection/` (new file) - Builder and handler for opponent choice scenarios
3. `engine/effects.cljs` - Add `:opponent-choice` effect type that pauses resolution for opponent input
4. `events/game.cljs` - Logic to switch priority/player control to opponent during opponent-choice resolution

#### Conditional Chain Trigger (CRITICAL)
- **No conditional effect triggering**: Effects don't have "if/then" logic; all effects execute unconditionally
- **No dynamic spell generation**: Resolution doesn't create new spells based on effect results
- **No self-referential copy**: Storm uses hardcoded effect (`:storm-copies`); no way for spell to copy itself conditionally

**Module changes needed:**
1. `engine/effects.cljs` - Add `:conditional-spell-copy` effect type that:
   - Checks a condition (e.g., "was this ability resolved?")
   - Creates copy if condition met
   - Targets another permanent
2. **Selection return format**: Need to track what happened in opponent-choice, pass to next effect
3. `events/selection/core.cljs` - May need to extend `execute-confirmed-selection` to return metadata about choices made

#### Dynamic Targeting
- **Current targeting**: Cast-time targeting is fixed; target selected once at casting
- **Copy retargeting**: `create-spell-copy` supports `target-override` but requires caller to specify
- **Multi-step targeting**: Chain of Vapor needs to let opponent choose a different target for each copy

**Module changes needed:**
1. `events/selection/targeting.cljs` - Extend to support "retargeting for copy" as selection type
2. Resolution flow - Need to pause after each copy creation to let player retarget

---

## Summary Table

| Feature | Status | Location | Notes |
|---------|--------|----------|-------|
| **Decree of Justice** | | | |
| Cycling keyword | EXISTS | schema.cljs:84 | Trigger type only, no cost/ability model |
| X mana costs | EXISTS | selection/costs.cljs | Full support |
| Token creation | **MISSING** | - | No effect type, schema fields, or lifecycle |
| Triggered abilities | EXISTS | trigger_dispatch.cljs | Infrastructure present |
| Activated abilities | EXISTS | engine/abilities.cljs | :mana and :activated types |
| **Ad Nauseam** | | | |
| Life payment | EXISTS | engine/costs.cljs | Atomic per-effect, not iterative |
| Reveal from library | EXISTS | effects.cljs:530 | :peek-and-select effect |
| Iterative selection | **MISSING** | - | No loop/repeat/continue mechanism |
| Conditional payment | **MISSING** | - | No "pay per revealed card" pattern |
| **Chain of Vapor** | | | |
| Bounce/return-to-hand | **MOSTLY MISSING** | effects.cljs | :return-from-graveyard exists, bounce needs 1 new effect type |
| Spell copies | EXISTS | engine/triggers.cljs | Storm copy mechanism |
| Opponent choice | **MISSING** | - | No opponent interaction during resolution |
| Conditional chaining | **MISSING** | - | No dynamic effect execution or self-targeting |
| Dynamic retargeting | **MISSING** | - | Copy system exists but no per-copy retargeting UI |

---

## Implementation Difficulty Assessment

### Decree of Justice: MEDIUM
- Token creation is the gating factor (new effect type + schema)
- Cycling ability model needs design decision (cost type vs ability type)
- X costs already fully supported
- Triggered ability infrastructure exists

**Estimated effort:** 3-4 modules changed, ~200-300 new lines

### Ad Nauseam: HIGH
- Iterative selection is a significant new interaction pattern
- Requires redesign of how effects pause/resume/loop
- Conditional payment adds complexity
- Challenge: "Continue or stop?" decision after each reveal

**Estimated effort:** 2-3 new modules + significant changes to selection/core.cljs, ~400-500 new lines

### Chain of Vapor: HIGH
- Opponent choice is the critical gap (needs priority/control switching)
- Conditional chaining requires effect metadata and dynamic execution
- Retargeting per copy is moderate complexity
- Challenge: Integrating opponent decisions into deterministic resolution

**Estimated effort:** 2-3 new modules + major changes to resolution.cljs and events/game.cljs, ~500-700 new lines

---

## Architectural Notes

### Blocking Decisions Needed

1. **Tokens**: Should tokens auto-expire when leaving battlefield? How are they created (effect factory vs entity builder)?
2. **Opponent Choices**: Can opponent participate in decisions during single-player game? Should there be "auto-yield" mode?
3. **Iterative Effects**: Should loops be first-class (selection type) or effect-level (new effect type)?
4. **Dynamic Copying**: Should spell copies support mid-chain retargeting, or only at copy-creation time?

### Potential Architectural Extensions

These features would be reusable for future cards:

1. **Token System** (Decree + Future tokens):
   - `:create-token` effect → opens token-based mechanics (tokens, emblems, etc.)

2. **Iterative Selection** (Ad Nauseam + Future loop mechanics):
   - `:iterative-choice` selection type → enables other loop cards (Spiritmonger triggers, etc.)

3. **Opponent Interaction** (Chain of Vapor + Future cards with choices):
   - Opponent-choice selection + priority switching → enables opponent modes (mirror universe, etc.)

4. **Conditional Effects** (Chain of Vapor + Future conditional mechanics):
   - `:if-then-else` effect structure → opens conditional damage, mill, etc.
