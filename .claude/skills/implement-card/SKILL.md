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
- **Card Definitions:** `src/main/fizzle/cards/iggy_pop.cljs`

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

### Step 2.4: Generate Skeleton

Generate card definition with Scryfall verification comments:

```clojure
;; [CARD_NAME] - [TYPE_LINE]
;; Oracle: [ORACLE_TEXT]
;; Scryfall verified: [TODAY'S DATE]
(def card-name-kebab
  {:card/id :card-name-kebab  ;; Scryfall: {name}
   :card/name "[NAME]"  ;; Scryfall: {name}
   :card/cmc [CMC]  ;; Scryfall: {cmc}
   :card/mana-cost [CONVERTED_MANA]  ;; Scryfall: {mana_cost}
   :card/colors #{[COLORS]}  ;; Scryfall: {colors}
   :card/types #{[TYPES]}  ;; Scryfall: {type_line}
   ;; :card/subtypes #{} - Add if applicable
   ;; :card/supertypes #{} - Add if applicable
   :card/text "[ORACLE_TEXT]"  ;; Scryfall: {oracle_text}
   ;; TODO: Add effects based on oracle text
   :card/effects []})
```

**Card ID:** Use kebab-case version of card name (e.g., `Dark Ritual` → `:dark-ritual`)

---

## Phase 3: Implementation

**Goal:** Implement card effects using TDD workflow.

### Step 3.1: Create Test File

Create test file at: `src/test/fizzle/cards/[card_name]_test.cljs`

Use existing test files as reference for structure.

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

### Consultation Checkpoint

When oracle text describes behavior the DSL cannot express, stop and consult:

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

**Do not proceed without user approval for simplifications.**

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
- [ ] **Validation passes:** `make validate` succeeds

### Final Validation

```bash
make validate
```

This runs lint, format check, and tests. All must pass.

### Commit

Once verification complete:

```bash
git add src/main/fizzle/cards/[file].cljs src/test/fizzle/cards/[file]_test.cljs
git commit -m "Add [Card Name] implementation with tests

- Scryfall verified attributes
- Tests derived from oracle text and rulings
- [Brief description of effects implemented]"
```

---

## Quick Reference

### Scryfall API URLs

```
Card data: https://api.scryfall.com/cards/named?exact=CARDNAME
Rulings: (use rulings_uri from card response)
```

### File Locations

```
Card definitions: src/main/fizzle/cards/iggy_pop.cljs
Card tests: src/test/fizzle/cards/[card_name]_test.cljs
DSL reference: docs/card-dsl.md
```

### Commands

```bash
make test      # Run all tests
make validate  # Run lint + format + tests
make fmt       # Auto-fix formatting
```
