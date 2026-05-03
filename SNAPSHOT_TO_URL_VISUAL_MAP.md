# Snapshot-to-URL: Visual Architecture Map

## Current State: What Exists

```
┌─────────────────────────────────────────────────────────────────┐
│                         APP-DB (re-frame)                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Setup Screen                 Game Screen           History    │
│  ─────────────                ───────────           ───────    │
│  :active-screen :setup        :active-screen :game              │
│  :setup/main-deck             :game/db (Datascript)  :history/ │
│  :setup/sideboard             :opening-hand/* (UI)   │ main    │
│  :setup/bot-archetype         :ui/* (collapsed)      │ forks   │
│  :setup/presets               :game/pending-select   │ position│
│  :setup/imported-decks                               │ current │
│  :setup/must-contain                                 │ branch  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │ localStorage
                         (pr-str/read)
┌─────────────────────────────────────────────────────────────────┐
│             Storage (db/storage.cljs)                           │
│  - fizzle-presets                                               │
│  - fizzle-imported-decks                                        │
│  - fizzle-stops                                                 │
│  - fizzle-last-preset                                           │
└─────────────────────────────────────────────────────────────────┘
```

## History Tree Structure (What Gets Captured)

```
Main Branch (always exists)
│
├─ Entry 0: init-game
│  └─ :entry/snapshot (Datascript DB at turn 1 main1)
│
├─ Entry 1: cast-spell (Dark Ritual)
│  └─ :entry/snapshot (DB after cast)
│
├─ Entry 2: yield
│  └─ :entry/snapshot (DB after yield)
│
├─ Entry 3: start-turn (turn 2)
│  ├─ :entry/snapshot (DB at start of turn 2)
│  │
│  └─── [Fork 1 branches here at position 3] ────────┐
│       Entry 3.1: cast-spell (Ritual)                │
│       Entry 3.2: yield                              │
│                                                     │
└─ Entry 4: cast-spell (Intuition) ← if no fork      │
   └─ :entry/snapshot                                │
                                                      │
                                              Fork 1  │
                                              ├─ Entry 4.1: cast
                                              └─ Entry 4.2: yield
```

## Datascript DB Contents (What Gets Serialized)

```
Datascript DB Value (@conn)
│
├─ Card Entities (50, loaded once)
│  ├─ id: :dark-ritual
│  ├─ name: "Dark Ritual"
│  ├─ cmc, mana-cost, colors, types
│  ├─ effects: [{:effect/type :add-mana ...}]
│  └─ ...
│
├─ Game Objects (20-30 per game)
│  ├─ id: UUID
│  ├─ card: (ref to card entity)
│  ├─ zone: :hand | :library | :graveyard | :battlefield
│  ├─ owner/controller: (ref to player)
│  ├─ tapped, counters, position, etc.
│  └─ ...
│
├─ Player Entities (2: player-1 + opponent)
│  ├─ id: :player-1 | :opponent
│  ├─ life: 20
│  ├─ mana-pool: {:blue 3 :black 0 ...}
│  ├─ storm-count: 1
│  ├─ stops, is-opponent, bot-archetype
│  └─ ...
│
├─ Game State (1 singleton)
│  ├─ id: :game-1
│  ├─ turn: 1
│  ├─ phase: :main1 | :main2 | :combat | :end
│  ├─ active-player, priority, winner
│  └─ ...
│
├─ Stack Items (0-10)
│  ├─ type: :spell | :ability | :etb
│  ├─ controller, source, effects, targets
│  └─ ...
│
└─ Triggers (0-5, stored as components)
   ├─ event-type, source, filter, effects
   └─ ...
```

## Current Data Flow: Event → Snapshot

```
Player Action (e.g., cast-spell)
│
├─────────────────────────┐
│                         │
▼                         │
history-interceptor       │
:before                   │
├─ Capture :game/db       │
├─ Capture selection type │
├─ Capture principal      │
│                         │
▼                         │
Event Handler             │
(:cast-spell, etc.)       │
├─ Modify app-db          │
├─ Modify :game/db        │
│                         │
▼                         │
history-interceptor       │
:after                    │
├─ Check if priority      │
├─ Create entry:          │
│  ├─ snapshot: (:game/db db-after)
│  ├─ event-type: :cast-spell
│  ├─ description: "Cast Dark Ritual"
│  ├─ turn: (from game state)
│  └─ principal: :player-1
│                         │
▼                         │
At Tip?                   │
├─ Yes: append-entry      │
└─ No: auto-fork          │
       ├─ Create fork-id
       ├─ Add to forks map
       ├─ Start new entries vec
       └─ Update current-branch
```

## What Needs to Be Built: URL Path

```
                    ┌──────────────────────────────────┐
                    │   URL Snapshot Sharing (NEW)     │
                    └──────────────────────────────────┘
                                 │
                ┌────────────────┼────────────────┐
                │                │                │
                ▼                ▼                ▼
          ┌──────────┐     ┌──────────┐    ┌──────────┐
          │ Encoder  │     │ Router   │    │ Decoder  │
          └──────────┘     └──────────┘    └──────────┘
               │                │               │
               │ 1. Collect     │ 3. Parse      │
               │    snapshot +  │    URL        │
               │    history     │               │
               │                │               │
               │ 2. Compress    │ 4. Route to   │
               │    (gzip?)     │    /game/:id  │
               │                │               │
               │ 3. Encode      │ 5. Fetch or   │
               │    (base64)    │    decompress │
               │                │               │
               │ 4. Shorten     │ 6. Deserialize
               │    (server ID) │    & restore
               │                │
               └────┬───────────┴────────────┬──┘
                    │                       │
                    ▼                       ▼
            Share URL                  Restored Game
            #/game/abc123              ┌────────────┐
                                       │ :game/db   │
                                       │ :history/* │
                                       │ App renders│
                                       └────────────┘
```

## Data Size at Each Stage

```
Snapshot Snapshot
Selection in Memory    Serialized  Gzipped    Base64+URL
─────────────────────────────────────────────────────────
Minimal      10 KB      10 KB      3-4 KB     4-6 KB
(1 spell)

Typical      14.5 KB    14.5 KB    4-5 KB     6-7 KB
(1 entry)

Game         116 KB     116 KB     35-45 KB   47-60 KB
(8 entries)

Full         193 KB     193 KB     60-75 KB   80-100 KB
(8 entries
+ 2 forks)

⚠ URL limit:  Most browsers: 2000-2048 chars
              Full game: ~110 KB base64 = 110k chars ❌
              Solution: Server storage with ID reference
```

## Three Serialization Options

```
Option 1: Full State in URL
┌────────────────────────────────────────┐
│ #/game?snapshot=<gzipped+base64 blob>  │
├────────────────────────────────────────┤
│ Pros:                                  │
│ - No server needed                     │
│ - Stateless sharing                    │
│ - Offline-friendly                     │
├────────────────────────────────────────┤
│ Cons:                                  │
│ - URL too long for full games (>2KB)   │
│ - Hard to remember/share manually      │
│ - Bloats history if many entries       │
└────────────────────────────────────────┘

Option 2: Current Snapshot Only (No History)
┌────────────────────────────────────────┐
│ #/game?snapshot=<gzipped+base64 blob>  │
├────────────────────────────────────────┤
│ Pros:                                  │
│ - Much smaller URL (~10-20 KB base64)  │
│ - Still shareable, no server           │
├────────────────────────────────────────┤
│ Cons:                                  │
│ - Loses game history/forks             │
│ - Can't replay decisions                │
│ - Player must accept game state as-is  │
└────────────────────────────────────────┘

Option 3: Server Storage with ID
┌────────────────────────────────────────┐
│ #/game/abc123                          │
├────────────────────────────────────────┤
│ Pros:                                  │
│ - Short, clean URL                     │
│ - Handles arbitrarily large states     │
│ - Can expire old snapshots             │
│ - Analytics possible                   │
├────────────────────────────────────────┤
│ Cons:                                  │
│ - Requires backend server              │
│ - Server storage overhead              │
│ - URL breaks if server deletes         │
│ - Privacy: snapshots on server         │
└────────────────────────────────────────┘
```

## State Restoration Flow (New)

```
Browser loads URL: https://fizzle.com#/game?snapshot=...
│
▼
App init (core.cljs)
│
├─ Parse URL
│  └─ Extract snapshot param
│
├─ history-interceptor registered
├─ bot-interceptor registered
├─ sba-interceptor registered
│
├─ setup/init-setup dispatched
│
▼
New Event Handler (to be added)
::sharing/restore-snapshot
│
├─ Decompress + deserialize
│  └─ Get {:game/db ... :history/* ... }
│
├─ Validate structure
│  └─ Check Datascript schema
│
├─ Reset app-db
│  ├─ :game/db ← restored
│  ├─ :history/* ← restored
│  └─ :active-screen ← :game
│
├─ Clear ephemeral UI state
│  ├─ :game/pending-selection
│  ├─ :opening-hand/*
│  └─ :setup/*
│
▼
mount-root
│
▼
Game Screen Renders
│ (with restored state, history sidebar shows forks)
```

## Missing Components (Checklist)

```
┌─ ROUTING ────────────────────────────────────┐
│ ☐ Choose router library (reitit? accountant?)
│ ☐ Define routes (#/game/:id, #/game?snapshot=...)
│ ☐ Wire router to app init
└──────────────────────────────────────────────┘

┌─ ENCODING ────────────────────────────────────┐
│ ☐ Create sharing/encode.cljs
│ ☐ Collect snapshot + history
│ ☐ Compress (gzip lib dependency)
│ ☐ Encode (base64 lib dependency)
│ ☐ Return URL-friendly string
└──────────────────────────────────────────────┘

┌─ DECODING ────────────────────────────────────┐
│ ☐ Create sharing/decode.cljs
│ ☐ Extract from URL param
│ ☐ Base64 decode
│ ☐ Decompress (gzip)
│ ☐ Deserialize (read-string)
│ ☐ Validate structure
└──────────────────────────────────────────────┘

┌─ RESTORATION EVENT ────────────────────────────┐
│ ☐ Create events/sharing.cljs
│ ☐ New event: ::restore-snapshot
│ ☐ Set :game/db to restored state
│ ☐ Set :history/* fields
│ ☐ Clear UI state
│ ☐ Set :active-screen :game
└──────────────────────────────────────────────┘

┌─ UI COMPONENT ─────────────────────────────────┐
│ ☐ Create views/sharing.cljs
│ ☐ Share button (generates URL)
│ ☐ Copy to clipboard
│ ☐ Maybe: QR code
│ ☐ Maybe: Analytics
└──────────────────────────────────────────────┘

┌─ OPTIONAL: SERVER STORAGE ─────────────────────┐
│ ☐ API endpoint: POST /api/snapshot
│ ☐ API endpoint: GET /api/snapshot/:id
│ ☐ Update encoder to use server
│ ☐ Update decoder to fetch from server
│ ☐ Handle 404 (snapshot expired/deleted)
└──────────────────────────────────────────────┘
```

## File Organization (Proposed)

```
src/main/fizzle/
├── ... existing files ...
├── sharing/
│   ├── encode.cljs        # (new) Snapshot → URL blob
│   └── decode.cljs        # (new) URL blob → snapshot
└── events/
    └── sharing.cljs       # (new) ::restore-snapshot event
```

---

## Cross-Reference to Detailed Docs

| Visual | Read Section | File |
|--------|--------------|------|
| Current State diagram | Section 1 | INVESTIGATION.md |
| History Tree | Section 2 | INVESTIGATION.md |
| Datascript Contents | Section 1.2 | INVESTIGATION.md |
| Data Flow | Section 8.1-8.3 | INVESTIGATION.md |
| Size progression | Section 7 | INVESTIGATION.md |
| Exact data structure | Section 1-7 | DATA_STRUCTURE.md |
| Serialization format | Section 4 | INVESTIGATION.md |
| URL design options | URL Design Considerations | SUMMARY.md |
| Integration points | Integration Points | SUMMARY.md |
| Build checklist | Checklist (bottom) | SUMMARY.md |
