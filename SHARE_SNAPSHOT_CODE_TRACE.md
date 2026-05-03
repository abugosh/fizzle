# Share/Snapshot System — Code Trace

Complete execution paths from button click to action result.

---

## SHARE: Click "Share" Button → Copy URL

```
User clicks Share button
  ↓
(rf/dispatch [::snapshot/share-position])
  ↓
snapshot/events.cljs:98 :: share-position
  ├─ Extract: base-url = location.origin + location.pathname
  ├─ Pure call: encode-for-share(db, base-url)
  │  ├─ extractor/extract(game-db)
  │  │  ├─ objects-in-zone (pull hand, graveyard, exile, library, battlefield)
  │  │  ├─ Resolve refs: player-eid → :player/id, card-eid → :card/id
  │  │  ├─ Strip :db/id keys
  │  │  └─ Return portable map
  │  ├─ encoder/encode-snapshot(portable)
  │  │  ├─ Flatten to bits: header (life, storm, phase, priority, mana) + zones
  │  │  ├─ base64url encode
  │  │  ├─ Check length < 2000
  │  │  └─ Return string or nil
  │  └─ Prepend base-url + "#s="
  ├─ If url:
  │  ├─ Set :snapshot/share-status :copied
  │  └─ Effect: ::copy-to-clipboard url
  │     └─ js/navigator.clipboard.writeText (or fallback)
  │        └─ Auto-clear :copied → :idle after 2s
  └─ Else:
     └─ Set :snapshot/share-status :error-too-large
```

**Files involved:**
- snapshot/events.cljs:98 (event handler)
- sharing/extractor.cljs:176 (extract)
- sharing/encoder.cljs (encode-snapshot)

---

## RESTORE: Load URL with #s=... → Fresh Game State

### Phase 1: Page Load & Init (core.cljs)

```
Page load (refresh or first visit with hash)
  ↓
core/init (line 144)
  ├─ history-interceptor/register! (setup history tracking)
  ├─ db-effect/register! (setup SBA + bot chokepoint)
  ├─ (rf/dispatch-sync [::setup/init-setup])
  │  └─ events/setup.cljs :: init-setup
  │     ├─ Create empty :game/db (or skip if already set)
  │     ├─ Set :active-screen :setup
  │     └─ Load setup config from localStorage
  ├─ (rf/dispatch-sync [::calc-events/init-calculator])
  │  └─ Load calculator queries from localStorage
  ├─ GET hash: (.-hash js/location)
  ├─ IF hash present:
  │  ├─ Pure call: snapshot/restore-from-hash-handler(hash)
  │  │  ├─ Check: str/starts-with? "#s="?
  │  │  │  └─ If false: return nil (fallback to normal init)
  │  │  ├─ Extract encoded: subs from position 4 onward
  │  │  ├─ Pure call: decoder/decode-snapshot(encoded)
  │  │  │  ├─ base64url decode
  │  │  │  ├─ Parse binary:
  │  │  │  │  ├─ Read header: life, storm, phase, priority, mana
  │  │  │  │  ├─ Read zones: count + card-indices + state (tapped, counters, grants)
  │  │  │  │  └─ Reconstruct portable map
  │  │  │  └─ Return portable-map or {:error ...}
  │  │  ├─ Pure call: restorer/restore-game-state(decoded)
  │  │  │  ├─ Create fresh Datascript connection
  │  │  │  ├─ Transact: cards/all-cards (card definitions)
  │  │  │  ├─ Transact: players (create-complete-player for each)
  │  │  │  ├─ Load: player stops from localStorage
  │  │  │  ├─ Transact: objects for each zone
  │  │  │  ├─ Transact: game state entity
  │  │  │  ├─ Create: triggers for battlefield permanents
  │  │  │  └─ Return app-db with:
  │  │  │     └─ :game/db (fresh db)
  │  │  │     └─ :history/main [] (EMPTY)
  │  │  │     └─ :active-screen :game
  │  │  └─ Return app-db or nil
  │  ├─ IF restored:
  │  │  ├─ Clear hash: js/history.replaceState(nil, "", pathname)
  │  │  ├─ Dispatch: (rf/dispatch-sync [::snapshot/restore-from-snapshot restored-app-db])
  │  │  │  └─ snapshot/events.cljs:133 :: restore-from-snapshot
  │  │  │     └─ Merge: db = merge(db, restored-app-db)
  │  │  └─ Result: app-db now has :game/db + empty history
  │  └─ ELSE: (restored is nil)
  │     └─ Normal init continues (user playing from setup)
  └─ mount-root (render app)
     └─ Reagent renders [app] component
        ├─ Check screen = (rf/subscribe [::subs/active-screen])
        ├─ If :game: render game-screen
        │  ├─ Left sidebar: graveyard-view
        │  ├─ Center: battlefield, stack, hand, controls, mana-pool
        │  ├─ Right sidebar: history-view (disabled: no history)
        │  └─ Modals: selection-modal, mode-selector-modal
        └─ ...
```

**Files involved:**
- core.cljs:144 (init)
- snapshot/events.cljs:56 (restore-from-hash-handler)
- sharing/decoder.cljs (decode-snapshot)
- sharing/restorer.cljs:176 (restore-game-state)

---

## TAKE ACTION: Click "Cast" Button → Resolution

### Step 1: Cast Event (events/casting.cljs)

```
User selects card, clicks "Cast"
  ↓
views/controls.cljs:58 :: on-click
  ├─ Check: can-cast? @(rf/subscribe [::subs/can-cast?])
  │  └─ subs/game.cljs :: can-cast? subscription
  │     ├─ Get game-db from [::game-db] sub
  │     ├─ Get selected card from [::selected-card] sub
  │     ├─ Pure: rules/can-cast?(game-db, human-id, object-id)
  │     │  ├─ Check: priority-phase? (line 17 of priority.cljs)
  │     │  │  └─ Is phase in #{:upkeep :draw :main1 :combat :main2 :end}?
  │     │  ├─ Check: has-restriction?(db, player, :cannot-cast-spells)
  │     │  ├─ Check: spell timing (instant vs sorcery)
  │     │  ├─ Check: card in hand
  │     │  ├─ Check: can-pay-mana?
  │     │  ├─ Check: can-pay-additional-costs?
  │     │  └─ Check: valid-targets-exist?
  │     └─ Return true/false
  ├─ If false: button disabled (grayed out)
  └─ If true:
     └─ (rf/dispatch [::casting-events/cast-spell])
        ↓
        events/casting.cljs:200+ :: cast-spell
        ├─ Extract app-db, dispatch payload (if any)
        ├─ Get game-db from app-db
        ├─ Get selected-card-id
        ├─ Get human player ID
        ├─ Check: can-cast?(game-db, human-id, selected-id)
        │  └─ If false: return unchanged app-db
        ├─ If modal card:
        │  ├─ build-spell-mode-selection (line 184)
        │  │  └─ Filter modes by valid targets
        │  ├─ Set :game/pending-mode-selection
        │  └─ Return app-db (pause for mode selection)
        ├─ Else: (non-modal or mode pre-determined)
        │  ├─ Pre-cast pipeline (line 141):
        │  │  ├─ Loop through [:exile-cards-cost :return-land-cost :discard-specific-cost
        │  │  │                :sacrifice-permanent-cost :pay-x-life :x-mana-cost
        │  │  │                :targeting :mana-allocation]
        │  │  ├─ For each step: evaluate-pre-cast-step(step, ctx)
        │  │  │  ├─ If nil: continue loop (skip step)
        │  │  │  ├─ If {:selection s}: set :game/pending-selection, return (pause)
        │  │  │  └─ If {:db db}: cast complete, return
        │  │  └─ If all steps return nil: cast immediately
        │  └─ Return app-db
```

### Step 2: Director Runs (events/director.cljs)

After cast event returns (with :game/pending-selection or :game/db updated), director is invoked:

```
cast-spell event completes
  ↓
Priority-flow event handlers call director:
  ├─ ::yield event → (director/run-to-decision db {:human-yielded? true})
  ├─ ::yield-all event → (director/run-to-decision db {:yield-all? true})
  └─ Or embedded in continuation chain
     └─ selection/core.cljs :: apply-continuation :resolve-one-and-stop
        └─ (director/run-to-decision app-db {...})

Director loop (events/director.cljs:318-359)
  ↓
loop [app-db, yield-all?, yield-through-stack?, human-yielded?, steps]
  ├─ If steps >= 300: return {:app-db app-db :reason :safety-limit}
  ├─ If :game/pending-selection: return {:app-db app-db :reason :pending-selection}
  ├─ Else:
  │  ├─ Get current priority holder: (priority/get-priority-holder-eid game-db)
  │  ├─ Convert eid → player-id (human-player-id or opponent-player-id)
  │  ├─ Determine whose turn:
  │  │  ├─ If bot holds priority:
  │  │  │  └─ step-bot-action (line 176)
  │  │  │     ├─ Pure call: bot-act(game-db, bot-id)
  │  │  │     │  ├─ Get bot archetype from player entity
  │  │  │     │  ├─ Get current phase
  │  │  │     │  ├─ Pure call: bot-protocol/bot-phase-action(archetype, phase, db, pid)
  │  │  │     │  │  └─ Check if action is :play-land
  │  │  │     │  ├─ If play-land:
  │  │  │     │  │  ├─ Find land in hand via can-play-land?
  │  │  │     │  │  ├─ Call: lands/play-land(db, player-id, land-id)
  │  │  │     │  │  ├─ Call: sba/check-and-execute-sbas(result-db)
  │  │  │     │  │  └─ Return {:action-type :play-land :game-db db' ...}
  │  │  │     │  ├─ Else:
  │  │  │     │  │  ├─ Pure call: bots-interceptor/bot-decide-action(game-db)
  │  │  │     │  │  │  ├─ Get bot archetype
  │  │  │     │  │  │  ├─ Pure call: bot-protocol/bot-priority-decision(archetype, ctx)
  │  │  │     │  │  │  │  └─ Return {:object-id oid :target tid} or :pass
  │  │  │     │  │  │  ├─ If :pass: return {:action :pass}
  │  │  │     │  │  │  ├─ Else:
  │  │  │     │  │  │  │  ├─ Get mana cost from card
  │  │  │     │  │  │  │  ├─ Pure call: find-tap-sequence(db, pid, mana-cost)
  │  │  │     │  │  │  │  │  └─ Find lands to tap (colored first, then generic)
  │  │  │     │  │  │  │  ├─ Check: can-pay?(mana-cost, tap-seq)
  │  │  │     │  │  │  │  └─ Return {:action :cast-spell :tap-sequence [...] ...}
  │  │  │     │  │  │     or {:action :pass}
  │  │  │     │  │  ├─ Apply taps: reduce activate-mana-ability
  │  │  │     │  │  ├─ Call: casting/cast-spell-handler (same as human path)
  │  │  │     │  │  │  └─ Eval pre-cast pipeline
  │  │  │     │  │  ├─ If selection: return pending-selection (pause for interactive)
  │  │  │     │  │  ├─ Else: spell on stack
  │  │  │     │  │  │  ├─ Call: sba/check-and-execute-sbas
  │  │  │     │  │  │  └─ Return {:action-type :cast-spell :game-db db' ...}
  │  │  │     │  │  └─ If :pass: return {:action-type :pass :game-db db}
  │  │  │     └─ Use result from bot-act
  │  │  │     ├─ If :play-land: continue loop (land added)
  │  │  │     ├─ If :cast-spell + pending-selection: return {:done ...} (pause)
  │  │  │     ├─ If :cast-spell (spell on stack):
  │  │  │     │  └─ Call: priority/yield-priority(db, holder-eid)
  │  │  │     │     └─ Add holder to :game/passed set
  │  │  │     │  └─ Continue loop (transfer priority)
  │  │  │     └─ If :pass:
  │  │  │        ├─ Call: priority/yield-priority(db, holder-eid)
  │  │  │        ├─ Call: priority/both-passed?(db)
  │  │  │        │  └─ Check :game/passed count >= 2
  │  │  │        ├─ If not both passed: transfer priority, continue loop
  │  │  │        └─ If both passed:
  │  │  │           ├─ Reset passes: priority/reset-passes
  │  │  │           ├─ Set priority to active player: priority/set-priority-holder
  │  │  │           ├─ If stack non-empty: step-resolve-stack
  │  │  │           │  └─ (resolution/resolve-one-item game-db)
  │  │  │           │     ├─ Pull top stack item
  │  │  │           │     ├─ Dispatch resolve-effect multimethod
  │  │  │           │     └─ Return {:db db' :pending-selection?} or {:db db' :fizzled?}
  │  │  │           │  └─ Call: sba/check-and-execute-sbas(result-db)
  │  │  │           │  └─ Continue loop
  │  │  │           └─ Else: stack empty
  │  │  │              └─ step-advance-phase
  │  │  │                 ├─ Get next phase: phases/next-phase(current)
  │  │  │                 ├─ If :cleanup:
  │  │  │                 │  ├─ Advance phase
  │  │  │                 │  ├─ Begin cleanup: cleanup/begin-cleanup(db, active-pid)
  │  │  │                 │  │  └─ Discard down to 7 (interactive)
  │  │  │                 │  └─ If selection: return {:done ...}
  │  │  │                 │  └─ Else: start turn
  │  │  │                 └─ Else: advance phase, continue loop
  │  │  │
  │  │  ├─ If human holds priority:
  │  │  │  └─ step-human-action (line 217)
  │  │  │     ├─ Get human player ID
  │  │  │     ├─ Get human stops
  │  │  │     ├─ Determine effective stops (own turns: use :player/stops,
  │  │  │     │                            opponent turns: use :player/opponent-stops)
  │  │  │     ├─ Pure call: human-should-auto-pass(game-db, human-eid, stops,
  │  │  │     │                                      yield-all?, yield-through-stack?)
  │  │  │     │  ├─ If yield-all?: return true (F6 mode)
  │  │  │     │  ├─ Else if yield-through-stack? + stack non-empty: return true
  │  │  │     │  ├─ Else if stack non-empty: return false (STOP for response)
  │  │  │     │  └─ Else: return (not (contains? stops phase))
  │  │  │     ├─ If auto-pass? false: return {:done {:app-db app-db :reason :await-human}}
  │  │  │     └─ Else (auto-pass):
  │  │  │        ├─ Call: priority/yield-priority(db, holder-eid)
  │  │  │        ├─ Call: priority/both-passed?(db)
  │  │  │        ├─ If not both passed: transfer priority, continue loop
  │  │  │        └─ If both passed: reset, resolve or advance phase
  │  │  │
  │  │  └─ Else (neither human nor bot holds priority):
  │  │     └─ Return {:done {:app-db app-db :reason :await-human}}
  │  │
  │  └─ Recur with updated state
  │
  └─ Return {:app-db ... :reason reason}

Return from director:
  ↓
priority-flow-events/yield handler (line 62-78)
  ├─ Apply result: (apply-director-result result)
  │  └─ Return {:db (:app-db result)}
  ├─ Build history entry
  └─ Set :history/pending-entry
```

### Step 3: UI Updates

```
Director returns, event handler returns
  ↓
Re-frame db updated with :game/db (new state) or :game/pending-selection
  ↓
Subscriptions re-evaluate:
  ├─ [::game-db] → new :game/db
  ├─ [::can-cast?] → rules/can-cast?(new-db, ...) → false or true
  ├─ [::can-play-land?] → rules/can-play-land?(new-db, ...) → false or true
  ├─ [::stack] → query all stack items
  ├─ [::hand] → query hand objects
  ├─ [::battlefield] → query battlefield objects
  └─ ...
  ↓
Reagent re-renders:
  ├─ controls-view (Cast/Play buttons now enabled/disabled based on new subs)
  ├─ stack-view (stack updated)
  ├─ hand-view (hand updated)
  ├─ battlefield-view (board updated)
  ├─ mana-pool-view (mana updated)
  ├─ selection-modal (if pending-selection)
  └─ ...
```

---

## UNDO: Click "Undo" Button → Fails

```
User clicks Undo button
  ↓
history-sidebar component (views/history.cljs)
  ├─ Subscribe to :history/main
  │  └─ Current app-db has :history/main []
  ├─ Check: (seq (:history/main db))
  │  └─ Empty sequence
  ├─ Button: NOT rendered (hidden in when-not clause)
  └─ Result: No button to click
```

**Why undo fails:**
- History is empty (restorer initializes with `[]`)
- No events to replay from
- UI hides undo button conditionally

---

## Files Used at Each Stage

| Stage | Files | Key Functions |
|-------|-------|----------------|
| **Share** | snapshot/events.cljs, sharing/extractor.cljs, sharing/encoder.cljs | `::share-position`, `encode-for-share`, `extract`, `encode-snapshot` |
| **Restore** | core.cljs, snapshot/events.cljs, sharing/decoder.cljs, sharing/restorer.cljs | `init`, `restore-from-hash-handler`, `decode-snapshot`, `restore-game-state` |
| **Cast Check** | subs/game.cljs, engine/rules.cljs | `::can-cast?`, `can-cast?` |
| **Cast Event** | events/casting.cljs, events/selection/* | `::cast-spell`, `evaluate-pre-cast-step`, builder multimethods |
| **Director** | events/director.cljs, engine/priority.cljs | `run-to-decision`, `human-should-auto-pass`, `bot-act` |
| **Resolution** | engine/resolution.cljs, engine/effects.cljs | `resolve-one-item`, `reduce-effects` |
| **SBAs** | engine/state-based.cljs | `check-and-execute-sbas` |
| **UI Update** | views/controls.cljs, views/hand.cljs, views/battlefield.cljs | Reagent components, subscriptions |
| **History** | history/events.cljs, history/interceptor.cljs, views/history.cljs | Event logging, undo/fork UI |

