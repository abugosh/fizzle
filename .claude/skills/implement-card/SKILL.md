---
name: implement-card
description: Card implementation workflow with Scryfall validation - enforces oracle-accurate implementation
---

# Implement Card Skill

Implements Magic: The Gathering cards in Fizzle with Scryfall-validated accuracy.

## Purpose

Prevent rule hallucination and attribute errors by:
1. Fetching canonical card data from Scryfall before implementation
2. Generating skeleton with verified attributes
3. Deriving tests from oracle text and rulings only
4. Cross-checking final implementation against Scryfall data

## References

- **DSL Reference:** [docs/card-dsl.md](../../../docs/card-dsl.md) - All effect, ability, cost, trigger, and condition types
- **Comprehensive Rules:** [MTG Comprehensive Rules](https://media.wizards.com/2026/downloads/MagicCompRules%2020260116.txt)
- **Card Definitions:** `src/main/fizzle/cards/{color}/` — per-card files in color-identity subdirectories
- **Card Registry:** `src/main/fizzle/cards/registry.cljs` — single require point for all cards

---

## Consultation Checkpoint (Available From Any Phase)

Whenever oracle text describes behavior the DSL cannot express, stop and consult the user. Do not silently simplify, reinterpret, or invent DSL extensions.

**Triggers:**
- Phase 1: oracle text references a mechanic not in docs/card-dsl.md
- Phase 2: no clean mapping for a type/supertype/subtype
- Phase 3: effect or trigger cannot be expressed with existing `:effect/type` or `:trigger/*` values
- Phase 5: verification reveals behavior the tests cover but the implementation does not

**Template:**

```markdown
## Consultation Checkpoint

**Card:** [Name]
**Oracle Text:** "[exact text from Scryfall]"

**Behavior requiring simplification:**
[What the oracle text says should happen]

**Current DSL support:**
[What the DSL can currently express - reference docs/card-dsl.md]

**Gap:**
[What behavior cannot be implemented]

**Proposed simplifications:**
- Option A: [description and trade-off]
- Option B: [description and trade-off]

**What is lost:**
[Behavior that won't be modeled]

**Why acceptable for practice tool:**
[Justification - e.g., "rarely relevant in goldfish testing"]

**User decision required:** Which approach to take?
```

**Do not proceed past the checkpoint without explicit user approval.**

---

## Phase 1: Scryfall Lookup

**Goal:** Fetch canonical card data before any implementation work.

### Step 1.1: Fetch Card Data

Use WebFetch to get card data from Scryfall:

```
URL: https://api.scryfall.com/cards/named?exact=CARDNAME
```

Replace `CARDNAME` with the exact card name. URL-encode special characters:
- Apostrophes: `Urza's Bauble` → `Urza%27s%20Bauble`
- Split cards: Use one half name, e.g., `Fire` for Fire // Ice

### Step 1.2: Extract Required Fields

From the response, extract:

| Field | Location | Use |
|-------|----------|-----|
| `name` | `.name` | Card name |
| `mana_cost` | `.mana_cost` | Mana cost string like `{1}{U}{B}` |
| `cmc` | `.cmc` | Converted mana cost (number) |
| `colors` | `.colors` | Array of color names |
| `type_line` | `.type_line` | Full type line |
| `oracle_text` | `.oracle_text` | Rules text |
| `rulings_uri` | `.rulings_uri` | URL for rulings |

### Step 1.3: Fetch Rulings

Make a second WebFetch request to the `rulings_uri` URL.

Extract the `.data` array. Each ruling has:
- `published_at`: Date of ruling
- `comment`: The ruling text

Rulings inform test cases and clarify ambiguous oracle text.

### Step 1.4: Display for Verification

Present extracted data to user:

```
## Scryfall Data for [CARD NAME]

**Mana Cost:** {mana_cost}
**CMC:** {cmc}
**Colors:** {colors}
**Type:** {type_line}

**Oracle Text:**
{oracle_text}

**Rulings ({count}):**
- ({date}): {comment}
- ...
```

### Error Handling

**404 Not Found:** Card name may be misspelled. Ask user to verify:
- Check spelling (case-insensitive but exact match required)
- For split cards, try each half separately
- For DFCs (double-faced cards), use front face name

**Empty rulings:** Normal for many cards. Proceed without ruling-based tests.

---

## Phase 2: Skeleton Generation

**Goal:** Generate card definition template with Scryfall-verified attributes.

### Step 2.1: Convert Mana Cost

Convert Scryfall mana symbols to Fizzle format using the table in docs/card-dsl.md:

| Scryfall | Fizzle |
|----------|--------|
| `{W}` | `:white 1` |
| `{U}` | `:blue 1` |
| `{B}` | `:black 1` |
| `{R}` | `:red 1` |
| `{G}` | `:green 1` |
| `{C}` | `:colorless 1` |
| `{1}`, `{2}`, etc. | `:colorless N` |

**Examples:**
- `{1}{U}{B}` → `{:colorless 1 :blue 1 :black 1}`
- `{B}{B}{B}` → `{:black 3}`
- (empty) → `{}` (for lands)

### Step 2.2: Convert Colors

Convert Scryfall color array to Fizzle set:

| Scryfall | Fizzle |
|----------|--------|
| `["B"]` | `#{:black}` |
| `["U", "B"]` | `#{:blue :black}` |
| `[]` | `#{}` |

### Step 2.3: Parse Type Line

Parse Scryfall type line into types, subtypes, and supertypes:

```
"Legendary Land" → :types #{:land}, :supertypes #{:legendary}
"Basic Land — Island" → :types #{:land}, :subtypes #{:island}, :supertypes #{:basic}
"Instant" → :types #{:instant}
"Creature — Human Wizard" → :types #{:creature}, :subtypes #{:human :wizard}
```

### Step 2.4: Determine Color Identity Subdirectory

Place the card file in the appropriate color-identity subdirectory:

| Color Identity | Directory |
|----------------|-----------|
| Black only | `cards/black/` |
| Blue only | `cards/blue/` |
| White only | `cards/white/` |
| Red only | `cards/red/` |
| Green only | `cards/green/` |
| Multicolor | `cards/multicolor/` |
| Colorless non-land | `cards/artifacts/` |
| Land | `cards/lands/` |

**File naming:** Use snake_case matching the card name (e.g., `dark_ritual.cljs`, `lions_eye_diamond.cljs`).

### Step 2.5: Generate Skeleton

Create file at `src/main/fizzle/cards/{color}/{card_name}.cljs`.

Each card file exports a single `def` named `card`:

```clojure
(ns fizzle.cards.{color}.{card-name-kebab}
  "Card Name card definition.

   Card Name: Type Line
   Oracle text here.")


(def card
  {:card/id :card-name-kebab
   :card/name "[NAME]"
   :card/cmc [CMC]
   :card/mana-cost [CONVERTED_MANA]
   :card/colors #{[COLORS]}
   :card/types #{[TYPES]}
   ;; :card/subtypes #{} - Add if applicable
   ;; :card/supertypes #{} - Add if applicable
   :card/text "[ORACLE_TEXT]"
   ;; TODO: Add effects based on oracle text
   :card/effects []})
```

**Card ID:** Use kebab-case version of card name (e.g., `Dark Ritual` → `:dark-ritual`)

### Step 2.6: Register in Registry

Add the new card to `src/main/fizzle/cards/registry.cljs`:

1. Add a require in the `:require` block (alphabetical within color group):
   ```clojure
   [fizzle.cards.{color}.{card-name-kebab} :as {card-name-kebab}]
   ```

2. Add `{card-name-kebab}/card` to the `all-cards` vector.

---

## Phase 3: Implementation

**Goal:** Implement card effects using TDD workflow.

### Step 3.1: Create Test File

Create test file mirroring the card file path:
`src/test/fizzle/cards/{color}/{card_name}_test.cljs`

**Follow CLAUDE.md's Card Testing Requirements.** That section is authoritative for:
- Mandatory test categories A–D (card definition, cast-resolve happy path, cannot-cast guards, storm count) and conditional categories E–I (selection, targeting, edge cases, flashback, triggers)
- Minimum test counts per card type (simple spell: 5, targeted: 8, selection: 8, land w/ ability: 8, flashback/storm: 12)
- **Gold-standard reference files** — copy the structure from the reference file matching your card type (Cabal Ritual for simple, Lightning Bolt for targeted, Vision Charm for modal, Duress for selection, etc.)
- Top anti-patterns to avoid (reimplementing production handlers, tautological `some?`/`string?` assertions, copy-pasted test variants)

Pick the gold-standard reference that matches your card type before writing the first test. Do not invent a new test structure.

### Step 3.1a: Use Production Path Helpers

**CRITICAL:** Happy-path cast-resolve tests MUST use the composable test helpers from `fizzle.test-helpers`. Never manually construct selection maps or call internal selection/effect functions.

| Card type | Helper pattern |
|-----------|---------------|
| Simple spell | `th/cast-and-resolve` |
| Targeted spell | `th/cast-with-target` → `th/resolve-top` |
| Modal+targeted spell | `th/cast-mode-with-target` → `th/resolve-top` |
| Interactive spell | `rules/cast-spell` → `th/resolve-top` → `th/confirm-selection` |

**Forbidden in happy-path tests:**
- `sel-targeting/confirm-cast-time-target` (use `th/cast-with-target`)
- `effects/execute-effect` (use `th/resolve-top`)
- `sel-core/execute-confirmed-selection` (use `th/confirm-selection`)
- `d/db-with [[:db/add _ :object/chosen-mode _]]` (use `th/cast-mode-with-target`)
- `library/execute-peek-selection` (use `th/resolve-top` + `th/confirm-selection`)
- `library/build-tutor-selection` (use `th/resolve-top` + `th/confirm-selection`)

### Step 3.2: Write Failing Test (RED)

**CRITICAL:** Every test MUST trace to oracle text or a Scryfall ruling.

Format test with source citation:

```clojure
;; Oracle: "Add {B}{B}{B}."
(deftest dark-ritual-adds-three-black-mana
  (testing "Dark Ritual adds 3 black mana to pool"
    ;; ... test implementation
    ))

;; Ruling (2004-10-04): "This is a mana ability."
(deftest dark-ritual-is-mana-ability
  (testing "Dark Ritual resolves without using the stack"
    ;; ... test implementation
    ))
```

**Anti-hallucination check:** Before writing any test, quote the exact oracle text or ruling that justifies the test. If you cannot cite a source, do not write the test.

### Step 3.3: Run Test (Confirm RED)

```bash
make test
```

Verify the test fails for the expected reason (not found, not implemented, etc.).

### Step 3.4: Implement Effects

Reference docs/card-dsl.md to implement effects matching oracle text behavior.

Common patterns:
- "Add {X}" → `:effect/type :add-mana`
- "Draw N cards" → `:effect/type :draw`
- "Mill N cards" → `:effect/type :mill`
- "Search your library for..." → `:effect/type :tutor`

### Step 3.5: Run Test (Confirm GREEN)

```bash
make test
```

All tests should pass. If not, iterate on implementation.

### Step 3.6: Refactor (if needed)

Clean up implementation while keeping tests green.

If oracle text describes behavior the DSL cannot express, stop and invoke the **Consultation Checkpoint** (see top of this skill).

---

## Phase 4: Testing

**Goal:** Ensure all tests derive from canonical sources.

### Test Derivation Rules

1. **Every test MUST cite its source**
   - Oracle text line, or
   - Scryfall ruling with date

2. **No invented rules**
   - Do not test behavior not described in oracle/rulings
   - Do not assume interactions with other cards
   - Do not test "common sense" MTG rules unless oracle states them

3. **Test format with citation**

```clojure
;; Oracle: "[exact quote]"
(deftest test-name-from-oracle-behavior
  ...)

;; Ruling (YYYY-MM-DD): "[exact quote]"
(deftest test-name-from-ruling
  ...)
```

### Common Test Categories

Based on oracle text, write tests for:

| Oracle Pattern | Test Category |
|----------------|---------------|
| "Add {X}" | Mana production amount and type |
| "Draw N" | Card draw count |
| "Mill N" | Mill target and count |
| "Search...put into" | Tutor criteria and destination |
| "Storm" | Storm copy generation |
| "Threshold" | Condition checking and alternate effects |
| "When...becomes tapped" | Trigger firing on tap |
| "Sacrifice" | Zone change to graveyard |

### Production Path Verification

Before proceeding to Phase 5, verify:
- [ ] Happy-path cast-resolve test uses production path helpers (no manual selection construction)
- [ ] `can-cast?` is implicitly tested via helper assertions
- [ ] No direct imports of `events.selection.targeting`, `events.selection.core`, or `engine.effects` in happy-path tests (edge case tests may use these)

### Running Tests

```bash
make test
```

All tests must pass before proceeding to Phase 5.

---

## Phase 5: Verification

**Goal:** Cross-check implementation against Scryfall data.

### Verification Checklist

Before marking implementation complete, verify:

- [ ] **Name matches:** `:card/name` equals Scryfall `name` exactly
- [ ] **CMC correct:** `:card/cmc` equals Scryfall `cmc`
- [ ] **Mana cost converted correctly:** `:card/mana-cost` matches conversion of Scryfall `mana_cost`
- [ ] **Colors correct:** `:card/colors` matches Scryfall `colors` array
- [ ] **Types parsed correctly:** `:card/types`, `:card/subtypes`, `:card/supertypes` match Scryfall `type_line`
- [ ] **Oracle text captured:** `:card/text` contains Scryfall `oracle_text`
- [ ] **All oracle behaviors have effects:** Each sentence/ability in oracle text has corresponding effect implementation
- [ ] **All tests cite sources:** No tests without oracle/ruling citations
- [ ] **No hallucinated rules:** No tests for behavior not in oracle/rulings
- [ ] **Production path tested:** Happy-path test uses `th/cast-and-resolve`, `th/cast-with-target`, `th/cast-mode-with-target`, or equivalent — no manual selection construction

### View-Layer Verification (when card introduces new selection type)

This section applies when a card's effects use a `:selection/type` value not already handled by an existing `render-selection-modal` defmethod in `src/main/fizzle/views/modals.cljs`. If the card only uses existing selection types (e.g., `:discard`, `:tutor`, `:scry`), skip this section.

- [ ] **Defmethod exists:** `render-selection-modal` in `views/modals.cljs` has a defmethod for the new selection type (or it correctly falls through to `:default` if the generic `card-selection-modal` is appropriate for this use case)
- [ ] **Card-source handled:** If the selection uses a new `:card-source` value not in the existing cases, the `::selection-cards` subscription in `subs/game.cljs` handles it
- [ ] **Modal renders correctly:** The modal component displays the right cards, valid-targets filtering works (selectable vs dimmed), and confirm/cancel buttons function

**Common selection-type-to-modal mappings:**

| Selection Pattern | Expected Modal | File Reference |
|---|---|---|
| Object targeting (permanents) | `object-target-modal` | views/modals.cljs |
| Player targeting | `player-target-modal` | views/modals.cljs |
| Hand/discard/tutor operations | `card-selection-modal` (default) | views/modals.cljs |
| Specialized UX (scry, peek, storm-split) | Dedicated modal component | views/modals.cljs |

### Final Validation (hard gate before commit)

```bash
make validate
```

This runs lint, format check, and tests. **All three must be green before you stage or commit anything.** If validate fails:

- Diagnose and fix the underlying issue (do not weaken assertions or disable lint rules to make it pass)
- Re-run `make validate`
- Only proceed to commit once the output is clean

Do not commit a red tree. Do not commit alongside a "will fix next" note.

### Commit

Only after `make validate` is green. Stage only the files this skill created or modified — do not sweep in unrelated WIP:

```bash
git add src/main/fizzle/cards/{color}/{card_name}.cljs \
       src/main/fizzle/cards/registry.cljs \
       src/test/fizzle/cards/{color}/{card_name}_test.cljs
git commit -m "Add [Card Name] implementation with tests

- Scryfall verified attributes
- Tests derived from oracle text and rulings
- [Brief description of effects implemented]"
```

**If a pre-commit hook fails:** the commit did not happen. Fix the underlying issue, re-stage, and create a **new** commit. Do not use `--amend` — the previous commit (if any) is a different one and amending it may destroy earlier work.

### Hand Back to User

After the commit succeeds:

- Report the commit SHA and subject line to the user
- **Do not push.** The user validates the card (smoke test in the UI, diff review, or otherwise) before deciding when to push
- Wait for an explicit instruction from the user before running `git push`

---

## Quick Reference

### Scryfall API URLs

```
Card data: https://api.scryfall.com/cards/named?exact=CARDNAME
Rulings: (use rulings_uri from card response)
```

### File Locations

```
Card definitions: src/main/fizzle/cards/{color}/{card_name}.cljs
Card registry:    src/main/fizzle/cards/registry.cljs
Card tests:       src/test/fizzle/cards/{color}/{card_name}_test.cljs
DSL reference:    docs/card-dsl.md

Color directories: black/, blue/, white/, red/, green/, multicolor/, lands/, artifacts/
```

### Commands

```bash
make test      # Run all tests
make validate  # Run lint + format + tests
make fmt       # Auto-fix formatting
```
