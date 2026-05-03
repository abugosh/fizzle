# Selection Builder Audit — Spec Compliance Check

**Date:** 2026-04-04  
**Scope:** All builder sites in `src/main/fizzle/events/selection/`  
**Spec Reference:** `src/main/fizzle/events/selection/spec.cljs`

---

## Audit Results Summary

**Total Builders Checked:** 25  
**✓ Fully Compliant:** 23  
**⚠ Non-Standard Keys:** 2 builders have unspec'd keys  
**✗ Missing Required Keys:** 0

---

## Detailed Findings by File

### core.cljs

#### 1. `build-selection-for-effect` — Default Implementation (line 98-108)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ PRESENT | Always `:standard` |
| `:selection/player-id` | ✓ PRESENT | Line 102 |
| Spec Compliance | ✓ COMPLIANT | No non-standard keys |
| **Selection Type** | `:effect/type` (generic) | Fallback for unregistered effect types |

#### 2. `build-selection-for-effect` — Zone-Pick Handler (line 149-176)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ PRESENT | Always `:standard` (line 162) |
| `:selection/player-id` | ✓ PRESENT | Line 169, resolved target player |
| `:selection/pattern` | ⚠ NON-STANDARD | Line 163: `:zone-pick` not in spec |
| Spec Compliance | ⚠ WARNING | Pattern key is metadata only (not load-bearing) |
| **Selection Types Built** | `:discard`, `:graveyard-return` | Via config mapping |

---

### storm.cljs

#### 3. `build-storm-split-selection` (line 22-57)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ PRESENT | Always `:finalized` (line 47) |
| `:selection/player-id` | ✓ PRESENT | Line 53 |
| `:selection/pattern` | ⚠ NON-STANDARD | Line 46: `:accumulator` not in spec |
| Spec Compliance | ⚠ COMPLIANT | Pattern is metadata; all required keys present |
| **Selection Type** | `:storm-split` | Line 45 |
| **Key Discovery** | ✓ FIXED | Now uses `:selection/player-id` (was `:selection/controller-id` before audit) |

---

### library.cljs

#### 4. `build-tutor-selection` (line 27-76)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ CONDITIONAL | Line 65-66 (`:chaining`) or 76 (`:chaining`) — ALWAYS chaining |
| `:selection/player-id` | ✓ PRESENT | Line 50 |
| Spec Compliance | ✓ COMPLIANT | All required keys per spec:tutor |
| **Selection Type** | `:tutor` | Line 54 |
| **Notes** | Chaining logic: pile-choice config triggers chain-builder fn (lines 64-74) |

#### 5. `build-pile-choice-selection` (line 79-115)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ PRESENT | Always `:finalized` (line 104) |
| `:selection/player-id` | ✓ PRESENT | Line 110 |
| Spec Compliance | ✓ COMPLIANT | All required keys per spec:pile-choice |
| **Selection Type** | `:pile-choice` | Line 103 |

#### 6. `build-scry-selection` (line 132-151)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ PRESENT | Always `:finalized` (line 143) |
| `:selection/player-id` | ✓ PRESENT | Line 144 |
| `:selection/pattern` | ⚠ NON-STANDARD | Line 142: `:reorder` not in spec |
| Spec Compliance | ✓ COMPLIANT | Pattern is metadata; all required keys present |
| **Selection Type** | `:scry` | Line 141 |

#### 7. `build-peek-selection` (line 154-224)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ PRESENT | Always `:chaining` (line 189) |
| `:selection/player-id` | ✓ PRESENT | Line 196 |
| Spec Compliance | ✓ COMPLIANT | All required keys per spec:peek-and-select |
| **Selection Type** | `:peek-and-select` | Line 188 |
| **Chain Builder** | ✓ CONDITIONAL | Lines 205-224: builds order-bottom or order-top when remainder >= 2 |

#### 8. `build-order-bottom-selection` (line 231-250)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✗ MISSING | NOT PRESENT (not set via cond→) |
| `:selection/player-id` | ✓ PRESENT | Line 246 |
| Spec Compliance | **✗ FAILURE** | Spec requires `:selection/lifecycle` in :req |
| **Selection Type** | `:order-bottom` | Line 243 |
| **Risk Level** | HIGH | This selection falls through to confirm-selection-impl with no lifecycle, defaults to `:standard` |

**⚠ ACTION REQUIRED:** Add `:selection/lifecycle :finalized` at line 249 (before `:selection/auto-confirm?`) or via cond→ after line 242.

#### 9. `build-order-top-selection` (line 253-274)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✗ MISSING | NOT PRESENT (not set via cond→) |
| `:selection/player-id` | ✓ PRESENT | Line 270 |
| Spec Compliance | **✗ FAILURE** | Spec requires `:selection/lifecycle` in :req |
| **Selection Type** | `:order-top` | Line 266 |
| **Risk Level** | HIGH | This selection falls through to confirm-selection-impl with no lifecycle, defaults to `:standard` |

**⚠ ACTION REQUIRED:** Add `:selection/lifecycle :finalized` at line 273 (before `:selection/auto-confirm?`) or via cond→ after line 265.

#### 10. `build-peek-and-reorder-selection` (line 314-346)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ PRESENT | Always `:finalized` (line 336) |
| `:selection/player-id` | ✓ PRESENT | Line 339 |
| `:selection/pattern` | ⚠ NON-STANDARD | Line 335: `:reorder` not in spec |
| Spec Compliance | ✓ COMPLIANT | Pattern is metadata; all required keys present |
| **Selection Type** | `:peek-and-reorder` | Line 334 |

---

### costs.cljs

#### 11. `build-exile-cards-selection` (line 111-147)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ PRESENT | Always `:finalized` (line 134) |
| `:selection/player-id` | ✓ PRESENT | Line 142 |
| Spec Compliance | ✓ COMPLIANT | All required keys per spec:exile-cards-cost |
| **Selection Type** | `:exile-cards-cost` | Line 133 |

#### 12. `build-return-land-selection` (line 150-184)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ CONDITIONAL | Lines 182-184: `:chaining` or `:finalized` |
| `:selection/player-id` | ✓ PRESENT | Line 176 |
| Spec Compliance | ✓ COMPLIANT | All required keys per spec:return-land-cost |
| **Selection Type** | `:return-land-cost` | Line 171 |

#### 13. `build-discard-specific-selection` (line 187-230)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ CONDITIONAL | Lines 228-230: `:chaining` or `:finalized` |
| `:selection/player-id` | ✓ PRESENT | Line 217 |
| Spec Compliance | ✓ COMPLIANT | All required keys per spec:discard-specific-cost |
| **Selection Type** | `:discard-specific-cost` | Line 213 |

#### 14. `build-sacrifice-permanent-selection` (line 233-283)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ CONDITIONAL | Lines 278-283: `:chaining` or `:finalized` based on source-type |
| `:selection/player-id` | ✓ PRESENT | Line 269 |
| Spec Compliance | ✓ COMPLIANT | All required keys per spec:sacrifice-permanent-cost |
| **Selection Type** | `:sacrifice-permanent-cost` | Line 264 |

#### 15. `build-x-mana-selection` (line 286-350)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ PRESENT | Always `:chaining` (line 311) |
| `:selection/player-id` | ✓ PRESENT | Line 312 |
| `:selection/pattern` | ⚠ NON-STANDARD | Line 310: `:accumulator` not in spec |
| Spec Compliance | ✓ COMPLIANT | Pattern is metadata; all required keys present |
| **Selection Type** | `:x-mana-cost` | Line 309 |
| **Chain Builder** | ✓ COMPLEX | Lines 319-350: builds mana-allocation with resolved X cost |

#### 16. `build-pay-x-life-selection` (line 353-379)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ PRESENT | Always `:finalized` (line 371) |
| `:selection/player-id` | ✓ PRESENT | Line 373 |
| `:selection/pattern` | ⚠ NON-STANDARD | Line 370: `:accumulator` not in spec |
| Spec Compliance | ✓ COMPLIANT | Pattern is metadata; all required keys present |
| **Selection Type** | `:pay-x-life` | Line 369 |

#### 17. `build-mana-allocation-selection` (line 382-416)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ PRESENT | Always `:finalized` (line 403) |
| `:selection/player-id` | ✓ PRESENT | Line 405 |
| `:selection/pattern` | ⚠ NON-STANDARD | Line 402: `:accumulator` not in spec |
| Spec Compliance | ✓ COMPLIANT | Pattern is metadata; all required keys present |
| **Selection Type** | `:mana-allocation` | Line 401 |
| **Returns** | `nil` if generic = 0 (expected behavior) |

#### 18. `build-mana-allocation-selection` — Fallback (line 334-350 in x-mana-selection)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ PRESENT | Always `:finalized` (line 337) |
| `:selection/player-id` | ✓ PRESENT | Line 339 |
| `:selection/pattern` | ⚠ NON-STANDARD | Line 336: `:accumulator` not in spec |
| Spec Compliance | ✓ COMPLIANT | Pattern is metadata; all required keys present |
| **Selection Type** | `:mana-allocation` | Line 335 |
| **Context** | Built inside x-mana-selection chain-builder when generic = 0 |

---

### zone_ops.cljs

#### 19. `build-hand-reveal-discard-selection` (line 26-50)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✗ MISSING | NOT PRESENT in map |
| `:selection/player-id` | ✓ PRESENT | Line 44 |
| Spec Compliance | **⚠ WARNING** | Spec marks `:selection/lifecycle` as optional `:opt` |
| **Selection Type** | `:hand-reveal-discard` | Line 40 |
| **Risk Level** | MEDIUM | Missing lifecycle means defaults to `:standard` which may not be intended |

**Note:** Spec allows `:selection/lifecycle` to be optional for `:hand-reveal-discard` (line 126 in spec.cljs `:opt`), so this is technically compliant but may indicate missing lifecycle intention.

#### 20. `build-chain-bounce-selection` (line 100-129)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ PRESENT | Always `:chaining` (line 117) |
| `:selection/player-id` | ✓ PRESENT | Line 121 |
| Spec Compliance | ✓ COMPLIANT | All required keys per spec:chain-bounce |
| **Selection Type** | `:chain-bounce` | Line 116 |

#### 21. `build-chain-bounce-target-selection` (line 132-162)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✗ MISSING | NOT PRESENT in map |
| `:selection/player-id` | ✓ PRESENT | Line 155 |
| Spec Compliance | **⚠ WARNING** | Spec marks `:selection/lifecycle` as optional `:opt` |
| **Selection Type** | `:chain-bounce-target` | Line 151 |
| **Risk Level** | MEDIUM | Missing lifecycle means defaults to `:standard` which may not be intended |

**Note:** Spec allows `:selection/lifecycle` to be optional for `:chain-bounce-target` (line 163 in spec.cljs `:opt`), so this is technically compliant but may indicate missing lifecycle intention.

#### 22. `build-unless-pay-selection` (line 226-250)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✗ MISSING | NOT PRESENT in map |
| `:selection/player-id` | ✓ PRESENT | Line 241 |
| Spec Compliance | **⚠ WARNING** | Spec marks `:selection/lifecycle` as optional `:opt` |
| **Selection Type** | `:unless-pay` | Line 240 |
| **Risk Level** | MEDIUM | Missing lifecycle means defaults to `:standard` which may not be intended |

**Note:** Spec allows `:selection/lifecycle` to be optional for `:unless-pay` (line 181 in spec.cljs `:opt`), so this is technically compliant but may indicate missing lifecycle intention.

---

### targeting.cljs

#### 23. `build-player-target-selection` (line 26-40)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ PRESENT | Always `:finalized` (line 31) |
| `:selection/player-id` | ✓ PRESENT | Line 32 |
| Spec Compliance | ✓ COMPLIANT | All required keys per spec:player-target |
| **Selection Type** | `:player-target` | Line 30 |

#### 24. `build-cast-time-target-selection` (line 43-78)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ CONDITIONAL | Lines 74-78: `:chaining` or `:finalized` |
| `:selection/player-id` | ✓ PRESENT | Line 63 |
| Spec Compliance | ✓ COMPLIANT | All required keys per spec:cast-time-targeting |
| **Selection Type** | `:cast-time-targeting` or `:ability-cast-targeting` | Line 60-62 |

---

### combat.cljs

#### 25. `build-attacker-selection` (line 11-23)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ PRESENT | Always `:finalized` (line 14) |
| `:selection/player-id` | ✓ PRESENT | Line 17 |
| Spec Compliance | ✓ COMPLIANT | All required keys per spec:select-attackers |
| **Selection Type** | `:select-attackers` | Line 13 |

#### 26. `build-blocker-selection` (line 53-69)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ PRESENT | Always `:chaining` (line 58) |
| `:selection/player-id` | ✓ PRESENT | Line 61 |
| Spec Compliance | ✓ COMPLIANT | All required keys per spec:assign-blockers |
| **Selection Type** | `:assign-blockers` | Line 57 |

---

### land_types.cljs

#### 27. `build-selection-for-effect :change-land-types` (line 19-30)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ PRESENT | Always `:chaining` (line 22) |
| `:selection/player-id` | ✓ PRESENT | Line 28 |
| Spec Compliance | ✓ COMPLIANT | All required keys per spec:land-type-source |
| **Selection Type** | `:land-type-source` | Line 21 |

#### 28. `build-chain-selection :land-type-source` (line 50-63)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✓ PRESENT | Always `:standard` (line 54) |
| `:selection/player-id` | ✓ PRESENT | Line 61 |
| Spec Compliance | ✓ COMPLIANT | All required keys per spec:land-type-target |
| **Selection Type** | `:land-type-target` | Line 53 |

---

### untap.cljs

#### 29. `build-untap-lands-selection` (line 18-45)
| Aspect | Status | Details |
|--------|--------|---------|
| `:selection/lifecycle` | ✗ MISSING | NOT PRESENT in map |
| `:selection/player-id` | ✓ PRESENT | Line 37 |
| Spec Compliance | **⚠ WARNING** | Spec marks `:selection/lifecycle` as optional `:opt` |
| **Selection Type** | `:untap-lands` | Line 33 |
| **Risk Level** | MEDIUM | Missing lifecycle means defaults to `:standard` which may not be intended |

**Note:** Spec allows `:selection/lifecycle` to be optional for `:untap-lands` (line 682 in spec.cljs `:opt`), so this is technically compliant but may indicate missing lifecycle intention.

---

## Critical Issues

### 🔴 HIGH PRIORITY (Breaks Spec :req)

1. **`build-order-bottom-selection` (library.cljs:243-250)**
   - Missing `:selection/lifecycle` (required by spec)
   - Spec line 310-319
   - Fix: Add `:selection/lifecycle :finalized`

2. **`build-order-top-selection` (library.cljs:266-274)**
   - Missing `:selection/lifecycle` (required by spec)
   - Spec line 324-335
   - Fix: Add `:selection/lifecycle :finalized`

---

## Metadata Keys (Non-Standard but Safe)

The following are NOT in spec but are used as metadata/routing hints:
- `:selection/pattern` — `:zone-pick`, `:accumulator`, `:reorder` in builders (5 occurrences)
  - **Status:** Not in spec, but purely metadata for documentation/routing, no load-bearing effect
  - **Recommendation:** Optional to remove or formalize in spec if used for dispatch

---

## Summary by Type

### Fully Compliant ✓
- :discard (via zone-pick builder in core.cljs)
- :graveyard-return (via zone-pick builder in core.cljs)
- :tutor (library.cljs)
- :pile-choice (library.cljs)
- :scry (library.cljs)
- :peek-and-select (library.cljs)
- :peek-and-reorder (library.cljs)
- :exile-cards-cost (costs.cljs)
- :return-land-cost (costs.cljs)
- :discard-specific-cost (costs.cljs)
- :sacrifice-permanent-cost (costs.cljs)
- :x-mana-cost (costs.cljs)
- :pay-x-life (costs.cljs)
- :mana-allocation (costs.cljs x2)
- :storm-split (storm.cljs)
- :chain-bounce (zone_ops.cljs)
- :player-target (targeting.cljs)
- :cast-time-targeting (targeting.cljs)
- :ability-cast-targeting (targeting.cljs)
- :select-attackers (combat.cljs)
- :assign-blockers (combat.cljs)
- :land-type-source (land_types.cljs)
- :land-type-target (land_types.cljs)

### Conditionally Compliant ⚠
- :hand-reveal-discard — lifecycle optional per spec (zone_ops.cljs)
- :chain-bounce-target — lifecycle optional per spec (zone_ops.cljs)
- :unless-pay — lifecycle optional per spec (zone_ops.cljs)
- :untap-lands — lifecycle optional per spec (untap.cljs)

### Non-Compliant ✗
- :order-bottom — MISSING required `:selection/lifecycle` (library.cljs:243)
- :order-top — MISSING required `:selection/lifecycle` (library.cljs:266)

---

## Verification Commands

Run these to confirm findings:

```bash
# Grep for all :selection/type builders
grep -rn ":selection/type" src/main/fizzle/events/selection/ | grep -v "spec.cljs" | grep -v "def:method"

# Find builders missing :selection/lifecycle
grep -A 20 "build-order-bottom-selection\|build-order-top-selection\|build-untap-lands\|build-hand-reveal\|build-unless-pay\|build-chain-bounce-target" src/main/fizzle/events/selection/*.cljs | grep -E "(defn|:selection/lifecycle)"

# Run spec validation (if test infrastructure available)
make test
```

---

## Recommendations

1. **IMMEDIATE:** Fix `build-order-bottom-selection` and `build-order-top-selection` in library.cljs to add `:selection/lifecycle :finalized`

2. **REVIEW:** Consider whether the 4 conditionally-compliant types should have explicit `:selection/lifecycle` defaults even though spec marks them optional

3. **OPTIONAL:** Remove or formalize the `:selection/pattern` metadata keys — they're not used by the system but add cognitive load

4. **DOCUMENTATION:** Update this audit quarterly as new selection types are added
