# Snapshot-to-URL: Quick Reference

## What Exists (Can Use)

| Component | Location | Status | Notes |
|-----------|----------|--------|-------|
| Game state snapshots | history/core.cljs | ✓ | Full Datascript DB per entry |
| History tree | history/core.cljs | ✓ | Main branch + forks with branch-points |
| Entry metadata | history/core.cljs:12-20 | ✓ | Turn, principal, description per snapshot |
| EDN serialization | db/storage.cljs | ✓ | pr-str/read-string for config |
| Fork/replay logic | history/core.cljs | ✓ | Tree navigation, step-to, auto-fork |
| Game initialization | events/init.cljs:117 | ✓ | Accepts deck config, creates full state |
| Setup config | events/setup.cljs | ✓ | Deck lists, presets (serialized to localStorage) |

## What's Missing (Must Build)

| Component | Impact | Complexity |
|-----------|--------|-----------|
| URL routing | No way to decode/encode URLs | High (need router + encoding scheme) |
| URL generation | Can't create shareable links | Medium (ID assignment + URL building) |
| State restoration from URL | Can't load game from URL params | Medium (new event handler) |
| Server storage | Game states disappear on reload | High (backend + API) |
| URL compression | EDN too verbose for URLs | Medium (base64 or similar) |

## Data You Can Serialize

**Per snapshot (~10-15KB)**:
- Full Datascript DB (:game/db)
- All game objects (with card refs)
- Both players + mana pool + storm count
- Stack items + targets
- Triggers

**Per history entry**:
- Snapshot DB + turn + principal + description string
- Event type keyword
- Fork structure (branch-points, parent refs)

## Key File Paths

| Purpose | File | Lines | Key Function |
|---------|------|-------|--------------|
| Game state schema | src/main/fizzle/db/schema.cljs | 11-113 | `schema` def — all entity types |
| History structure | src/main/fizzle/history/core.cljs | 1-271 | `make-entry`, `effective-entries`, `auto-fork` |
| Snapshot capture | src/main/fizzle/history/interceptor.cljs | 104-168 | `history-interceptor` :after |
| Game init | src/main/fizzle/events/init.cljs | 117-169 | `init-game-state` — full game setup |
| Setup flow | src/main/fizzle/events/setup.cljs | 255-270 | `start-game-handler` — deck → init |
| Serialization | src/main/fizzle/db/storage.cljs | 1-103 | `save-presets!`, `load-presets` (EDN format) |
| App-db structure | src/main/fizzle/core.cljs | 1-132 | Entry point; shows init flow |

## URL Design Considerations

**What to encode**:
1. Current snapshot (Datascript DB)
2. Position in history (:history/position)
3. Branch ID if not on main (:history/current-branch)
4. Fork tree structure (for full context)

**Encoding options**:
- Base64(pr-str(snapshot-map)) — simple, verbose
- Base64(gzip(pr-str(...))) — compact, requires library
- Server ID reference — cleanest, requires backend

**Example snapshot-map for URL**:
```clojure
{:game/db <serialized-datascript-db>
 :history/position 7
 :history/current-branch :fork-id
 :history/main [entry0 entry1 ...]  ; entries up to position
 :history/forks {...}               ; all fork metadata}
```

## Event Flow to Understand

**Snapshot Creation** (automatic):
```
Event dispatch → history-interceptor:before
  ↓ (event executes)
  ↓
history-interceptor:after checks if priority event
  ↓
If yes: create entry with snapshot, append or auto-fork
  ↓
Done (no new event needed)
```

**Game Initialization** (from setup):
```
start-game-handler (setup.cljs:255)
  ↓
init-game-state (events/init.cljs:117)
  ↓
Returns: {:game/db @conn :history/main [] ...}
  ↓
Active screen changes to :opening-hand
```

**State Restoration** (new, for URL):
```
Parse URL
  ↓
Deserialize snapshot + history tree
  ↓
Dispatch event to restore :game/db to snapshot
  ↓
Set :history/position and :history/current-branch
  ↓
UI renders restored state
```

## Size Estimate

| Component | Size | Notes |
|-----------|------|-------|
| Single snapshot (Datascript DB) | 10-15 KB | Excludes ~50KB one-time card defs |
| History entry (with snapshot) | 1-2 KB | Metadata overhead only |
| Typical game history (8-12 entries) | 100-150 KB | All snapshots + metadata |
| With 3-5 forks @ 3 entries each | +50-100 KB | Fork tree overhead |
| **Full game state for URL** | 150-250 KB | Typical mid-game scenario |

**Compression impact**:
- Base64: +33% (raw bytes → text)
- Gzip: ~60-70% reduction (typical for EDN)
- **Final URL**: ~5-7 KB (gzipped + base64)

## Integration Points

**To implement snapshot-to-URL**:

1. **URL encoding** → new module `sharing/encode.cljs`
   - Serialize app-db subsets to EDN
   - Compress + Base64
   - Generate short ID (or full payload in URL)

2. **URL decoding** → extend `events/setup.cljs` or new `events/sharing.cljs`
   - Parse URL params
   - Decompress + deserialize
   - Restore :game/db via existing init flow

3. **URL routing** → integrate router (e.g., accountant, reitit)
   - Hash routes like `#/game/abc123` or `#/game?snapshot=...`
   - Detect on app init, trigger restoration

4. **History storage** (optional)
   - Send snapshots to server on fork/undo
   - Return shareable ID
   - Fetch snapshot on URL load

## What NOT to Serialize

- `:game/pending-selection` (UI state only, ephemeral)
- `:opening-hand/` keys (one-time setup)
- `:setup/` keys (not relevant after game starts)
- `:ui/` keys (collapsed panels, etc.)
- Subscription results (functions, not data)
