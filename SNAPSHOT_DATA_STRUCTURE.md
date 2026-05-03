# Snapshot Data Structure for URL Encoding

This document shows the exact shape of data that would be serialized for sharing a game snapshot via URL.

## 1. Full Snapshot Map Structure

**What gets encoded for a shareable snapshot**:

```clojure
{:snapshot/timestamp  1710158400000        ; Optional: when snapshot was taken
 :snapshot/version    "1.0"                ; Version for decoder compatibility

 ;; Game state (the Datascript DB)
 :game/db {:datoms [...]                  ; Datascript internal: all entity facts
           ...}

 ;; Position in history
 :history/position    7                    ; Current position in effective-entries
 :history/current-branch nil               ; nil = on main; or fork-id UUID

 ;; Complete history tree
 :history/main [entry0 entry1 ... entry7] ; All main-branch entries up to position
 :history/forks {:fork-uuid-1 {:fork/id :fork-uuid-1
                                :fork/name "Fork 1"
                                :fork/branch-point 3  ; Branched at position 3
                                :fork/parent nil      ; Parent is main
                                :fork/entries [entry4 entry5]}
                  ...}

 ;; Deck config (for context/recovery)
 :setup/main-deck [{:card/id :dark-ritual :count 4}
                   {:card/id :cabal-ritual :count 4}
                   ...]
 :setup/bot-archetype :goldfish
 :setup/sideboard [...]
 :setup/must-contain {:dark-ritual 1}     ; What was sculpted in opening hand
}
```

## 2. History Entry Structure (What's in :history/main)

From `history/core.cljs:12-20`:

```clojure
{:entry/snapshot <Datascript-DB>          ; Full immutable DB at this point
 :entry/event-type :fizzle.events.game/cast-spell
 :entry/description "Cast Dark Ritual"    ; Human-readable
 :entry/turn 1                             ; Turn number
 :entry/principal :player-1}               ; Who acted (:player-1 or :opponent)
```

**Note**: `:entry/snapshot` is the **entire** Datascript DB value, which is a map of datoms:

```clojure
{:datoms
 [[entity-id attribute value transaction]  ; Facts about entities
  [1 :card/id :dark-ritual 536870913]
  [1 :card/name "Dark Ritual" 536870913]
  [1 :card/cmc 1 536870913]
  [2 :player/id :player-1 536870914]
  [2 :player/life 20 536870914]
  ...
  ]}
```

The Datascript DB is fully serializable via `pr-str`.

## 3. Example: Minimal Shareable Snapshot

After casting one spell and yielding:

```clojure
{:snapshot/version "1.0"
 :game/db <full-datascript-db-with-all-entities>
 :history/position 1
 :history/current-branch nil
 :history/main
 [{:entry/snapshot <datascript-db-at-turn-1-main1>
   :entry/event-type :fizzle.events.game/init-game
   :entry/description "Game started"
   :entry/turn 1
   :entry/principal nil}
  {:entry/snapshot <datascript-db-after-cast-dark-ritual>
   :entry/event-type :fizzle.events.game/cast-spell
   :entry/description "Cast Dark Ritual"
   :entry/turn 1
   :entry/principal :player-1}]
 :history/forks {}
 :setup/main-deck [... deck list ...]
 :setup/bot-archetype :goldfish
 :setup/sideboard []
 :setup/must-contain {}}
```

## 4. What's in the Datascript DB at Each Entry

Each `:entry/snapshot` is a complete, immutable Datascript DB containing:

### 4.1 Card Entities (~50 total, loaded once)

```clojure
[1 :card/id :dark-ritual]
[1 :card/name "Dark Ritual"]
[1 :card/cmc 1]
[1 :card/mana-cost {:black 1}]
[1 :card/colors #{:black}]
[1 :card/types #{:sorcery}]
[1 :card/text "Add {B}{B}{B} to your mana pool."]
[1 :card/effects [{:effect/type :add-mana :effect/mana {:black 3}}]]
...
```

### 4.2 Game Object Entities (20-30 typical)

```clojure
[1001 :object/id #uuid "550e8400-e29b-41d4-a716-446655440000"]  ; UUID, unique
[1001 :object/card 1]                                             ; Ref to card entity
[1001 :object/zone :hand]
[1001 :object/owner 2]                                            ; Ref to player
[1001 :object/controller 2]
[1001 :object/tapped false]
[1001 :object/position 0]
[1001 :object/is-copy false]
...
```

### 4.3 Player Entities (2 total)

```clojure
[2 :player/id :player-1]
[2 :player/name "Player"]
[2 :player/life 20]
[2 :player/mana-pool {:white 0 :blue 3 :black 0 :red 0 :green 0 :colorless 0}]
[2 :player/storm-count 1]
[2 :player/land-plays-left 1]
[2 :player/max-hand-size 7]
[2 :player/is-opponent false]
[2 :player/grants []]                       ; Vector of active grants
[2 :player/stops #{:main1 :main2}]         ; Phase stops
...
```

### 4.4 Game State Entity (1 singleton)

```clojure
[3 :game/id :game-1]
[3 :game/turn 1]
[3 :game/phase :main1]
[3 :game/step :untap]
[3 :game/active-player 2]                   ; Ref to player
[3 :game/priority 2]
[3 :game/winner nil]
[3 :game/human-player-id :player-1]
...
```

### 4.5 Stack Items (0-10 typical)

```clojure
[1234 :stack-item/position 0]                ; LIFO: higher = resolves first
[1234 :stack-item/type :spell]
[1234 :stack-item/controller 2]              ; Ref to player
[1234 :stack-item/source 1001]               ; Ref to object (the spell)
[1234 :stack-item/effects [{...}]]           ; Effect maps
[1234 :stack-item/targets {}]                ; Targeting choices
[1234 :stack-item/description "Dark Ritual"]
...
```

### 4.6 Trigger Entities (0-5 typical)

```clojure
[1500 :trigger/event-type :permanent-tapped]
[1500 :trigger/source 1020]                  ; Ref to permanent that has trigger
[1500 :trigger/controller 2]                 ; Ref to player
[1500 :trigger/filter {:event/object-id :self}]  ; Event matching
[1500 :trigger/effects [{...}]]
[1500 :trigger/uses-stack? true]
...
```

## 5. Size Breakdown per Entry

**Single Datascript DB snapshot**:

| Component | Datoms Count | Approx Size |
|-----------|--------------|-------------|
| Card definitions | 50 cards × 8 attributes | 400 datoms (~8KB) |
| Game objects | 25 objects × 10 attributes | 250 datoms (~5KB) |
| Players | 2 × 15 attributes | 30 datoms (~0.5KB) |
| Game state | 1 × 12 attributes | 12 datoms (~0.2KB) |
| Stack items | 3 × 8 attributes | 24 datoms (~0.5KB) |
| Triggers | 2 × 8 attributes | 16 datoms (~0.3KB) |
| **Total** | ~730 datoms | **~14.5 KB** |

**History entry metadata**:
- `:entry/event-type` keyword: ~30 bytes
- `:entry/description` string: ~50 bytes
- `:entry/turn` integer: ~5 bytes
- `:entry/principal` keyword: ~15 bytes
- **Per entry overhead**: ~100 bytes

## 6. Full Game State Size

**For a mid-game with 8 entries + 2 small forks**:

| Component | Count | Size |
|-----------|-------|------|
| Main branch entries | 8 | 8 × 14.5 KB = 116 KB |
| Fork 1 entries | 3 | 3 × 14.5 KB = 43.5 KB |
| Fork 2 entries | 2 | 2 × 14.5 KB = 29 KB |
| Entry metadata | 13 | ~1.3 KB |
| Fork metadata | 2 | ~1 KB |
| Setup deck list | 1 | ~2 KB |
| **Serialized (pr-str)** | | **~193 KB** |
| **Gzipped** | | **~60-80 KB** (compression ratio ~30-40%) |
| **Base64 encoded** | | **~80-110 KB** (gzip + base64) |

## 7. How Decoder Would Use This

```clojure
;; 1. Parse URL and extract encoded snapshot
snapshot-blob = decode-url-param("snapshot")

;; 2. Decompress and deserialize
snapshot-map = read-string(gunzip(base64-decode(snapshot-blob)))

;; 3. Extract game-db and history
game-db = (:game/db snapshot-map)
history = {:history/main (:history/main snapshot-map)
           :history/forks (:history/forks snapshot-map)
           :history/position (:history/position snapshot-map)
           :history/current-branch (:history/current-branch snapshot-map)}

;; 4. Restore app-db
app-db = (merge {:game/db game-db}
                history
                {:active-screen :game
                 :ui/stack-collapsed false
                 :ui/gy-collapsed false
                 :ui/history-collapsed false})

;; 5. Initialize subscriptions and render
rf/reset-db! app-db
mount-root
```

## 8. What Can't Be in the Snapshot

**Ephemeral UI state** (cleared on restore):
- `:game/pending-selection` — mid-resolution choices
- `:game/selected-card` — active card highlight
- `:game/pending-mode-selection` — modal selection

**One-time setup state** (not relevant after game starts):
- `:opening-hand/` keys
- `:setup/` keys (deck lists, presets)

**Subscription/function references** (not serializable):
- re-frame subscription functions
- event handlers
- any clojure functions

## 9. File Paths for Reference

| What | File | How to Get |
|------|------|-----------|
| Full DB example | `src/test/fizzle/cards/.../test.cljs` | `th/create-test-db` helper |
| DB schema | `src/main/fizzle/db/schema.cljs` | `:db/attr definitions` |
| Entry creation | `src/main/fizzle/history/core.cljs:12-20` | `make-entry` function |
| Snapshot capture | `src/main/fizzle/history/interceptor.cljs:163` | `:entry/snapshot game-db-after` |
| EDN serialization | `src/main/fizzle/db/storage.cljs` | `pr-str` / `reader/read-string` |
| Datascript API | `node_modules/datascript` | Use `@conn` to get DB value |
