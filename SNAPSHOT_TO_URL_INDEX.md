# Snapshot-to-URL Investigation Index

Three documents cover all aspects of game state capture, history, and serialization for enabling shareable snapshots via URL.

## Document Overview

### 1. **SNAPSHOT_TO_URL_INVESTIGATION.md** (Main Reference)
**Purpose**: Complete technical investigation with all findings.

**Sections**:
1. **Game State Shape** — App-db structure, Datascript schema, entities
2. **Fork/Replay System** — History tree, branch-points, auto-fork mechanics
3. **Event History & Event Log** — What gets captured, when, how
4. **Serialization** — Current infrastructure (localStorage EDN), limitations
5. **Game Setup** — Initialization flow, what's needed to reconstruct
6. **URL Routing** — Current status (none exists)
7. **State Size Estimation** — Typical snapshot sizes, compression impact
8. **Data Flow References** — Tracing paths through the codebase

**Read if you need**: Complete context, exact file locations, line numbers, understanding how things fit together.

---

### 2. **SNAPSHOT_TO_URL_SUMMARY.md** (Quick Lookup)
**Purpose**: Fast reference for what exists and what's missing.

**Sections**:
- **What Exists** — Table of components you can reuse
- **What's Missing** — Components to build
- **Data You Can Serialize** — Size, format
- **Key File Paths** — Quick navigation
- **URL Design Considerations** — Options and tradeoffs
- **Event Flow** — Snapshot creation, initialization, restoration
- **Size Estimate** — Per-component breakdown
- **Integration Points** — Where to hook new code
- **What NOT to Serialize** — Ephemeral state to exclude

**Read if you need**: "How much do I need to build?" "Where does the serialization happen?" "What are my options?"

---

### 3. **SNAPSHOT_DATA_STRUCTURE.md** (Data Reference)
**Purpose**: Exact data structure of what gets encoded.

**Sections**:
1. **Full Snapshot Map Structure** — Complete shape, all keys
2. **History Entry Structure** — Per-entry format
3. **Example: Minimal Shareable Snapshot** — Real example, one spell cast
4. **What's in the Datascript DB at Each Entry** — All entity types and their datoms
5. **Size Breakdown per Entry** — Bytes per component
6. **Full Game State Size** — Total payload (before/after compression)
7. **How Decoder Would Use This** — Implementation pseudocode
8. **What Can't Be in Snapshot** — Ephemeral state to exclude
9. **File Paths for Reference** — Where to find examples

**Read if you need**: "What exactly gets serialized?" "How big is the payload?" "What does the data look like?" "How do I deserialize it?"

---

## Navigation by Task

### "I need to understand the game state"
1. Read: **INVESTIGATION.md, Section 1** (Game State Shape)
2. Reference: **DATA_STRUCTURE.md, Section 4** (What's in Datascript DB)
3. Explore: `/Users/abugosh/g/fizzle/src/main/fizzle/db/schema.cljs`

### "I need to understand fork/replay"
1. Read: **INVESTIGATION.md, Section 2** (Fork/Replay System)
2. Reference: **SUMMARY.md, Event Flow** (How snapshots are created)
3. Explore: `/Users/abugosh/g/fizzle/src/main/fizzle/history/core.cljs`

### "I need to design the URL encoding"
1. Read: **SUMMARY.md, URL Design Considerations**
2. Reference: **DATA_STRUCTURE.md, Section 6** (Size estimates)
3. Reference: **DATA_STRUCTURE.md, Section 1-2** (What to encode)

### "I need to build URL encoding/decoding"
1. Reference: **DATA_STRUCTURE.md, Section 1** (Full snapshot map)
2. Reference: **DATA_STRUCTURE.md, Section 7** (How decoder works)
3. Read: **INVESTIGATION.md, Section 4** (Serialization format)

### "I need to build URL restoration"
1. Read: **SUMMARY.md, Event Flow, State Restoration**
2. Reference: **INVESTIGATION.md, Section 5** (Game initialization)
3. Explore: `/Users/abugosh/g/fizzle/src/main/fizzle/events/init.cljs:117`

### "I need to add URL routing"
1. Read: **INVESTIGATION.md, Section 6** (URL Routing Status)
2. Reference: **SUMMARY.md, Integration Points**
3. Note: No router currently exists; need to choose one

### "I need a quick estimate of build effort"
1. Read: **SUMMARY.md, What Exists / What's Missing**
2. Reference: **DATA_STRUCTURE.md, Section 6** (Payload size)
3. Skim: **INVESTIGATION.md, Section 10** (Current Limitations)

---

## Key Findings Summary

### What You Can Reuse
- ✓ Datascript DB snapshots (fully serializable, captured automatically)
- ✓ EDN serialization (pr-str/read-string already used in codebase)
- ✓ History tree structure (fork/branch-point already tracked)
- ✓ Game initialization (accepts config, creates full state)
- ✓ Setup config serialization (deck lists, presets already in localStorage)

### What You Must Build
- ✗ URL router (none exists; choose accountant, reitit, etc.)
- ✗ URL encoding/decoding (serialize snapshot → URL params)
- ✗ URL restoration flow (new event handler + app init)
- ✗ State persistence (optional, but current snapshots are in-memory only)
- ✗ Compression (optional, for URL length — Base64 alone adds 33%)

### Critical Constraints
- **No existing router**: Must add routing library
- **Snapshots are large**: 10-15 KB per entry (compressed: 3-5 KB)
- **History can grow**: Full game with branches: 150-250 KB raw (50-80 KB gzipped)
- **EDN is verbose**: pr-str(db) is text-heavy; compression essential for URLs
- **Datascript not designed for serialization**: DB is serializable, but intended for in-memory use

### Size Estimates
| Scenario | Raw Size | Gzipped | URL Friendly? |
|----------|----------|---------|---------------|
| Single snapshot | 10-15 KB | 3-5 KB | ✓ (if base64'd into URL) |
| Typical game (8 entries) | 100-120 KB | 30-40 KB | ~ (may hit URL length limits) |
| Full game + forks | 150-250 KB | 50-80 KB | ✗ (exceeds most URL limits) |

**Note**: 2000-character URL limit is typical for browsers. Gzipped full game state + base64 (~110 KB base64) **exceeds this**. Solutions:
- Send only current snapshot (skip history)
- Use server storage with ID reference
- Implement chunking/pagination

---

## Integration Checklist

To implement snapshot-to-URL:

- [ ] **URL Router**: Choose and integrate router library
- [ ] **Snapshot Encoder**: Create `sharing/encode.cljs`
  - [ ] Select compression (gzip? brotli? none?)
  - [ ] Select encoding (base64? base32? custom?)
  - [ ] Decide: full history vs current snapshot only
- [ ] **Snapshot Decoder**: Create `sharing/decode.cljs`
  - [ ] Parse URL params
  - [ ] Decompress & deserialize
  - [ ] Validate snapshot structure
- [ ] **Restoration Handler**: New event in `events/sharing.cljs`
  - [ ] Dispatch on app init if URL param present
  - [ ] Restore :game/db and :history/* state
  - [ ] Set :active-screen to :game
- [ ] **UI**: Share button (new view component)
  - [ ] Generate current snapshot as URL
  - [ ] Copy to clipboard
  - [ ] Display QR code (optional)
- [ ] **Storage** (optional): Persist snapshots to server
  - [ ] POST snapshot, get ID back
  - [ ] Use ID in URL instead of full payload
  - [ ] Fetch snapshot on page load

---

## Files Modified by Investigation

- ✓ `/Users/abugosh/g/fizzle/SNAPSHOT_TO_URL_INVESTIGATION.md` (new, 450+ lines)
- ✓ `/Users/abugosh/g/fizzle/SNAPSHOT_TO_URL_SUMMARY.md` (new, 250+ lines)
- ✓ `/Users/abugosh/g/fizzle/SNAPSHOT_DATA_STRUCTURE.md` (new, 350+ lines)
- ✓ `/Users/abugosh/g/fizzle/SNAPSHOT_TO_URL_INDEX.md` (this file)

---

## Recommended Reading Order

**For Planning**:
1. SUMMARY.md → get the lay of the land
2. INVESTIGATION.md, Section 10 → understand limitations
3. DATA_STRUCTURE.md, Section 6 → estimate effort

**For Implementation**:
1. SUMMARY.md, Integration Points → pick your modules
2. DATA_STRUCTURE.md, Section 1 → understand snapshot structure
3. INVESTIGATION.md, Sections 4-5 → understand serialization

**For Deep Dives**:
- INVESTIGATION.md, Section 2 → understand fork/replay internals
- INVESTIGATION.md, Section 8 → trace data flows through code
- DATA_STRUCTURE.md, Section 7 → see decoder pseudocode

---

## Quick Links to Code

| What | Path | Lines | Purpose |
|------|------|-------|---------|
| Datascript schema | `src/main/fizzle/db/schema.cljs` | 11-113 | All entity types and attributes |
| History structure | `src/main/fizzle/history/core.cljs` | 1-50 | Tree, entries, forks |
| Snapshot capture | `src/main/fizzle/history/interceptor.cljs` | 104-168 | How snapshots are created |
| Game init | `src/main/fizzle/events/init.cljs` | 117-169 | Full game state creation |
| Setup flow | `src/main/fizzle/events/setup.cljs` | 255-270 | Deck → init pipeline |
| Serialization | `src/main/fizzle/db/storage.cljs` | 1-103 | EDN pr-str/read-string |
| App entry | `src/main/fizzle/core.cljs` | 95-132 | App render, init sequence |
| Test helpers | `src/test/fizzle/test_helpers.cljs` | 1-∞ | How to create test DBs |

---

## Document Statistics

| Document | Lines | Sections | Code Examples |
|----------|-------|----------|----------------|
| INVESTIGATION | 450+ | 10 | ~15 |
| SUMMARY | 250+ | 10 | ~5 |
| DATA_STRUCTURE | 350+ | 9 | ~20 |
| INDEX (this file) | 300+ | 6 | ~3 |
| **Total** | **1350+** | **35+** | **~43** |

All documents cross-referenced and linked for easy navigation.
