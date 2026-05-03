# Share/Snapshot Quick Reference

## TL;DR

**Share:** game-db → portable map → binary → base64url → URL `#s=...` → clipboard
**Restore:** URL hash → decode → fresh db → empty history → app renders
**Can't undo:** History is empty (by design — shared states are read-only)
**Can't act:** Priority/stops/rule-checks limit actions; UI buttons disable accordingly

---

## Five Critical Functions

### 1. Share Entry: snapshot/events.cljs line 98

```clojure
(rf/reg-event-fx
  ::share-position
  (fn [{:keys [db]} _]
    (let [base-url (str (.-origin js/location) (.-pathname js/location))
          url      (encode-for-share db base-url)]
      (if url
        {:db     (set-share-status db :copied)
         ::copy-to-clipboard url}
        {:db (set-share-status db :error-too-large)}))))
```

Calls `encode-for-share` (pure), result is clipboard write effect.

### 2. Restore Entry: core.cljs line 153

```clojure
(let [hash     (.-hash js/location)
      restored (snapshot/restore-from-hash-handler hash)]
  (when restored
    (.replaceState js/history nil "" (.-pathname js/location))
    (rf/dispatch-sync [:fizzle.snapshot.events/restore-from-snapshot restored])))
```

Calls `restore-from-hash-handler` (pure), merges result into db, clears hash.

### 3. Pure Restore Handler: snapshot/events.cljs line 56

```clojure
(defn restore-from-hash-handler
  [hash-str]
  (when (and (string? hash-str)
             (str/starts-with? hash-str hash-prefix))
    (let [encoded (subs hash-str (count hash-prefix))
          decoded (decoder/decode-snapshot encoded)]
      (when-not (:error decoded)
        (restorer/restore-game-state decoded)))))
```

Three calls: decode → restore. Returns app-db or nil (caller handles fallback).

### 4. DB Reconstruction: sharing/restorer.cljs line 176

```clojure
(defn restore-game-state
  [snapshot]
  (let [conn   (d/create-conn schema)
        _      (d/transact! conn cards/all-cards)
        stops  (storage/load-stops)
        players (:players snapshot)]
    ;; ... transact players, zones, triggers, game state
    {:game/db              @conn
     :active-screen        :game
     ;; ...
     :history/main         []           ; EMPTY
     :history/forks        {}
     :history/current-branch nil
     :history/position    -1}))
```

Fresh Datascript db from card registry. History initialized empty.

### 5. Director: events/director.cljs line 290

```clojure
(defn run-to-decision
  [app-db opts]
  (loop [app-db app-db
         yield-all? ...
         yield-through-stack? ...
         human-yielded? ...
         steps 0]
    (cond
      (>= steps max-director-steps) {:app-db app-db :reason :safety-limit}
      (:game/pending-selection app-db) {:app-db app-db :reason :pending-selection}
      :else
      ;; ... determine priority holder, dispatch to step-* functions
      )))
```

Pure sync loop. Determines when to pause for human input.

---

## Three Restriction Layers

### Layer 1: Priority Phases (engine/priority.cljs line 11)

Players only act in: `:upkeep :draw :main1 :combat :main2 :end`
No priority in: `:untap :cleanup`

Director checks this before asking human to act.

### Layer 2: Stops (director.cljs line 64-77, priority_flow.cljs line 22-34)

Each player has `:player/stops` set (e.g., `#{:main1}`).

Director uses `human-should-auto-pass` (line 43) to check if phase is in stops:
- Phase in stops → human gets priority
- Phase not in stops → auto-pass

### Layer 3: Rule Checks (engine/rules.cljs)

`can-cast?` checks:
1. Priority phase (director already ensures)
2. No restrictions (e.g., `:cannot-cast-spells`)
3. Spell timing (instant any time, sorcery = main + empty stack)
4. Card in hand
5. Can pay mana + additional costs
6. Valid targets exist

UI subscription disables button if false.

---

## Shared State Lifecycle

### On Load

```
URL: https://example.com/#s=ABCD1234...

core/init (line 153-158)
├─ Check hash
├─ restore-from-hash-handler (pure)
│  ├─ decoder/decode-snapshot
│  ├─ restorer/restore-game-state
│  └─ Return app-db with empty history
├─ Merge app-db
├─ Clear hash
└─ Mount root
```

### During Play

```
User clicks "Cast"
├─ Subscription ::can-cast? evaluates
├─ If true: dispatch ::cast-spell
├─ Event: casting/cast-spell-handler
│  ├─ Pre-cast pipeline (costs, targeting, mana)
│  ├─ If selection: show dialog
│  └─ Else: cast spell + set :game/db
└─ Director runs (embedded in event handlers)
   ├─ Apply spell resolution
   ├─ If bot holds priority: call bot-act repeatedly
   ├─ If human should auto-pass: advance phases
   └─ Return :await-human or :pending-selection
```

### Undo/Fork

```
User clicks "Undo" button
└─ Disabled (history-sidebar checks :history/main length)

User clicks "Fork" button
└─ Disabled (history-sidebar checks :history/forks)
```

---

## Key State Fields

### Restored app-db (restorer.cljs line 221-230)

```clojure
{:game/db              ; Fully-playable Datascript db
 :active-screen        :game
 :game/game-over-dismissed false
 :ui/stack-collapsed   false
 :ui/gy-collapsed      false
 :ui/history-collapsed false
 :history/main         []              ; EMPTY
 :history/forks        {}
 :history/current-branch nil
 :history/position    -1}
```

### Game state in db

```clojure
{:game/id :game-1
 :game/active-player  <eid>
 :game/priority       <eid>            ; Who holds priority
 :game/turn           1
 :game/phase          :main1
 :game/passed         #{<eid> <eid>}   ; Who has passed
 :game/human-player-id :player-1}
```

### Player state

```clojure
{:player/id :player-1
 :player/name "Player"
 :player/life 20
 :player/mana-pool {:white 0 :blue 0 ...}
 :player/storm-count 0
 :player/land-plays-left 1
 :player/stops #{:draw}              ; Phases where player pauses
 :player/opponent-stops #{}          ; Phases where player pauses opponent turn
 :player/is-opponent false
 :player/bot-archetype nil           ; nil for human, keyword for bot
 :player/grants []}
```

---

## File Quick Map

| What | File | Key Function |
|------|------|--------------|
| Share event | `snapshot/events.cljs:98` | `::share-position` → `encode-for-share` |
| Restore (pure) | `snapshot/events.cljs:56` | `restore-from-hash-handler` |
| Extractor | `sharing/extractor.cljs:176` | `extract` (game-db → portable map) |
| Encoder | `sharing/encoder.cljs` | `encode-snapshot` (portable → binary → base64url) |
| Decoder | `sharing/decoder.cljs` | `decode-snapshot` (base64url → portable map) |
| Restorer | `sharing/restorer.cljs:176` | `restore-game-state` (portable → fresh db + app-db) |
| Director | `events/director.cljs:290` | `run-to-decision` (pure game loop) |
| Priority checks | `engine/priority.cljs:11-14` | `priority-phases` set, `in-priority-phase?` |
| Director priority | `events/director.cljs:43` | `human-should-auto-pass` (checks stops, yield flags) |
| Director bot | `events/director.cljs:90` | `bot-act` (pure bot decision + application) |
| Rule checks | `engine/rules.cljs` | `can-cast?`, `can-play-land?` |
| UI subscriptions | `subs/game.cljs` | `::can-cast?`, `::can-play-land?` |
| UI buttons | `views/controls.cljs:32` | `controls-view` (renders Cast/Play/Yield, checks subscriptions) |
| App init | `core.cljs:144` | `init` (setup → restore if hash → mount) |
| Setup init | `events/setup.cljs` | `::init-setup` (normal game setup) |

---

## Debugging Checklist

**Players can't take actions:**
1. Check `:game/phase` — is it in `priority-phases`?
2. Check `:player/stops` — does it contain current phase?
3. Check can-cast?/can-play-land? subscriptions — are they false?
4. Check `:game/pending-selection` — is a selection active?
5. Check `:game/priority` — does human hold priority?

**Share URL is too large:**
1. Check encoder.cljs line 65: `url-char-limit` is 2000
2. Check extractor.cljs: all zones are serialized
3Count objects in all zones; if >~50 cards, URL will exceed limit

**Restore fails:**
1. Check hash format: must start with `#s=`
2. Check decoder output for `:error` key
3. Check all cards are in registry (restorer calls `engine/cards.cljs` lookup)
4. Check card definitions have `:card/id` keyword

**History not updating:**
1. Check `:history/main` — events appended?
2. Check history interceptor (history/interceptor.cljs) — registered?
3. Check event handlers dispatch events (not just mutations)

