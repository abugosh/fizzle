# Complete Bot-Priority-Phase System Investigation

**Date:** 2026-03-27
**Scope:** ALL bot-specific code in priority/phase advancement. Comprehensive trace of how stops work for both players.

---

## 1. BOT-SPECIFIC FUNCTIONS IN priority_flow.cljs

### File: `/Users/abugosh/g/fizzle/src/main/fizzle/events/priority_flow.cljs`

#### 1.1 `player-is-bot?` (lines 97-102)
**Purpose:** Check if a player entity has a bot archetype set.
**Signature:** `(db, player-eid) -> boolean`
**Implementation:**
```clojure
(defn- player-is-bot?
  "Check if a player entity has a bot archetype set.
   Uses DB data only (no bot protocol calls).
   Pure function: (db, player-eid) -> boolean"
  [db player-eid]
  (boolean (:player/bot-archetype (d/pull db [:player/bot-archetype] player-eid))))
```
**What it does:** Queries the player entity in Datascript for `:player/bot-archetype` attribute. Returns boolean.
**Who calls it:** `bot-would-pass?` (line 101), `yield-advance-phase` (lines 156, 157), `negotiate-priority` (lines 254, 255, 257, 259).
**Why it matters:** Determines whether a player entity is bot-controlled or human. This is the SOURCE OF TRUTH for bot identity (not role-based dispatch, not position).

---

#### 1.2 `bot-would-pass?` (lines 105-114)
**Purpose:** Check if bot player would pass priority in current game state.
**Signature:** `(game-db, opponent-player-id) -> boolean`
**Implementation:**
```clojure
(defn- bot-would-pass?
  "Check if bot player would pass priority in current game state.
   Returns true if bot has no action to take (should auto-pass).
   Pure function: (game-db, opponent-player-id) -> boolean"
  [game-db opponent-player-id]
  (let [archetype (bot-protocol/get-bot-archetype game-db opponent-player-id)]
    (if archetype
      (= :pass (bot-protocol/bot-priority-decision
                 archetype {:db game-db :player-id opponent-player-id}))
      true)))
```
**What it does:**
1. Looks up bot archetype via `bot-protocol/get-bot-archetype` (dispatches on player-id)
2. If bot archetype exists, calls `bot-protocol/bot-priority-decision` with the bot spec and game context
3. Returns true if decision is `:pass`, meaning the bot has no actions
4. Returns true if no archetype (no bot)

**Who calls it:** `negotiate-priority` (line 256) — checks if opponent should auto-pass when human yields.
**Why it matters:** The ONLY place in priority_flow.cljs that calls the bot protocol. This is the hook that makes bots responsive (they can choose to hold priority if they have an action).

---

#### 1.3 `bot-turn-advance-one-phase` (lines 117-141)
**Purpose:** Advance exactly one phase during a bot's turn, handling cleanup/turn boundary.
**Signature:** `(app-db) -> {:app-db app-db'}`
**Implementation:**
```clojure
(defn- bot-turn-advance-one-phase
  "Advance exactly one phase during a bot's turn, handling cleanup/turn boundary.
   Returns {:app-db} with the game state after advancing one phase.
   Pure function: (app-db) -> {:app-db app-db'}"
  [app-db]
  (let [game-db (:game/db app-db)
        active-player-id (queries/get-active-player-id game-db)
        game-state (queries/get-game-state game-db)
        current-phase (:game/phase game-state)
        nxt (phases/next-phase current-phase)]
    (if (= nxt :cleanup)
      ;; Advancing to cleanup: advance, begin cleanup, cross turn boundary
      (let [advanced-db (phases/advance-phase game-db active-player-id)
            cleanup-result (cleanup/begin-cleanup advanced-db active-player-id)]
        (if (:pending-selection cleanup-result)
          {:app-db (-> app-db
                       (assoc :game/db (:db cleanup-result))
                       (assoc :game/pending-selection (:pending-selection cleanup-result)))}
          (let [db-after-cleanup (:db cleanup-result)
                db-after-turn (phases/start-turn db-after-cleanup active-player-id)]
            {:app-db (-> app-db
                         (assoc :game/db db-after-turn)
                         (dissoc :bot/action-count))})))
      ;; Normal: advance one phase
      {:app-db (assoc app-db :game/db (phases/advance-phase game-db active-player-id))})))
```
**What it does:**
1. Extracts active player, current phase, and next phase from game-db
2. **If advancing to cleanup:** Calls `phases/advance-phase`, then `cleanup/begin-cleanup`, then `phases/start-turn` (crosses turn boundary, resets active player)
3. **If normal advance:** Just calls `phases/advance-phase` once
4. **If pending selection:** Returns with pending-selection flag (cleanup discard)
5. **On turn boundary:** Clears `:bot/action-count` for next turn

**Who calls it:** `yield-advance-phase` (line 161) — when `bot-driving?` is true.
**Why it matters:** **This is the sole function used to advance bot turns one phase at a time.** Humans use `advance-with-stops` (batch advance). Bots get one phase per call so the event loop can fire the bot interceptor after each phase to decide actions like playing lands.

---

#### 1.4 `yield-advance-phase` (lines 144-219)
**Purpose:** Handle yield when all passed and stack is empty: advance phases.
**Signature:** `(app-db) -> {:app-db, :continue-yield?}`

**This is the most bot-specific function. Contains ALL bot vs human branching logic.**

**Implementation (split by path):**

**Setup (lines 151-158):**
```clojure
(let [game-db (:game/db app-db)
      auto-mode (priority/get-auto-mode game-db)
      f6? (= :f6 auto-mode)
      active-player-id (queries/get-active-player-id game-db)
      active-eid (queries/get-player-eid game-db active-player-id)
      bot-turn? (player-is-bot? game-db active-eid)
      priority-holder-eid (priority/get-priority-holder-eid game-db)
      bot-driving? (and bot-turn? (player-is-bot? game-db priority-holder-eid))]
```

**Key check:** `bot-driving?` = `(and bot-turn? (player-is-bot? game-db priority-holder-eid))`
- True IFF both active player AND priority holder are bots
- This is when bot interceptor drives one-phase-at-a-time advancement

**BOT PATH (lines 159-188):**
```clojure
(if bot-driving?
  ;; Bot interceptor driving during bot's turn: one phase at a time
  (let [result (bot-turn-advance-one-phase app-db)
        result-db (:game/db (:app-db result))
        new-active-id (queries/get-active-player-id result-db)
        crossed-turn? (not= active-player-id new-active-id)
        new-phase (:game/phase (queries/get-game-state result-db))]
    (cond
      ;; Pending selection (e.g., cleanup discard)
      (:game/pending-selection (:app-db result))
      result

      ;; Turn boundary crossed — continue
      crossed-turn?
      (let [human-pid (queries/get-human-player-id result-db)
            landed-on-human? (= new-active-id human-pid)]
        (if (and landed-on-human? f6?)
          {:app-db (update (:app-db result) :game/db priority/clear-auto-mode)
           :continue-yield? true}
          {:app-db (:app-db result)
           :continue-yield? true}))

      ;; Stop hit on bot's turn (human's opponent-turn stops) — pause unless F6
      (and (not f6?)
           (priority/check-stop result-db active-eid new-phase))
      {:app-db (:app-db result)}

      ;; Same turn, no stop — pause for bot interceptor to dispatch ::bot-decide
      :else
      {:app-db (:app-db result)}))
```

**BOT PATH DETAILS:**
1. Calls `bot-turn-advance-one-phase` to advance ONE phase
2. **If pending selection:** Return immediately (cleanup discard pauses)
3. **If turn boundary crossed:** Return with `continue-yield? true` (human's turn now, cascade ::yield to handle next turn)
4. **If stop hit on bot's turn:** Check if human configured an "opponent-turn stop" (e.g., `:opponent-turn :main1`). If bot hit this and not F6, pause. This allows humans to watch bot phases.
5. **Normal case:** Return without continue-yield, pausing so bot interceptor can fire to decide actions (e.g., play lands)

**HUMAN PATH (lines 190-219):**
```clojure
;; Human turn: batch advance to next stop
(let [result (advance-with-stops app-db f6?)
      result-db (:game/db (:app-db result))
      new-active-id (queries/get-active-player-id result-db)
      crossed-turn? (not= active-player-id new-active-id)]
  (cond
    ;; Pending selection (e.g., cleanup discard)
    (:game/pending-selection (:app-db result))
    result

    ;; Turn boundary crossed — continue (next yield re-reads active player)
    crossed-turn?
    (let [human-pid (queries/get-human-player-id result-db)
          landed-on-human? (= new-active-id human-pid)]
      (if (and landed-on-human? f6?)
        ;; Crossed to human turn with F6 — clear auto-mode, keep yielding to stop
        {:app-db (update (:app-db result) :game/db priority/clear-auto-mode)
         :continue-yield? true}
        ;; Crossed to other player — continue cascade
        {:app-db (:app-db result)
         :continue-yield? true}))

    ;; F6 within same turn — clear auto-mode (stop hit)
    f6?
    (update result :app-db
            (fn [adb] (update adb :game/db priority/clear-auto-mode)))

    ;; Normal: same player's turn, stop here
    :else
    result))
```

**HUMAN PATH DETAILS:**
1. Calls `advance-with-stops` to batch advance through phases to the next stop
2. **If pending selection:** Return immediately (cleanup discard pauses)
3. **If turn boundary crossed:** Return with `continue-yield? true` (continues cascade to opponent's turn)
4. **If F6 mode:** Clear auto-mode when stop is hit
5. **Normal case:** Return and pause at stop

---

## 2. HOW advance-with-stops WORKS FOR HUMANS

### File: `/Users/abugosh/g/fizzle/src/main/fizzle/events/priority_flow.cljs`, lines 15-64

**Purpose:** Advance phases until a stop is hit or a turn boundary is crossed.
**Signature:** `(app-db, ignore-stops?) -> {:app-db app-db'}`
**Behavior: BOT-AGNOSTIC** — checks stops for any player (human OR bot playing with human's stops).

**Implementation:**
```clojure
(defn advance-with-stops
  "Advance phases until a stop is hit or a turn boundary is crossed.
   Handles cleanup and start-turn automatically.
   After crossing a turn boundary, returns immediately — the caller (yield-impl)
   handles continuing through the new turn via recursive ::yield dispatch.
   When ignore-stops? is true (F6 mode), skips player phase stop checks.
   Bot-agnostic: checks stops for any player the same way.
   Returns {:app-db app-db'} with game-db at the stopped phase.
   Pure function: (app-db, ignore-stops?) -> {:app-db app-db'}"
  [app-db ignore-stops?]
  (let [game-db (:game/db app-db)
        active-player-id (queries/get-active-player-id game-db)
        player-eid (queries/get-player-eid game-db active-player-id)]
    (loop [gdb game-db]
      (let [game-state (queries/get-game-state gdb)
            current-phase (:game/phase game-state)
            nxt (phases/next-phase current-phase)]
        (if (= nxt :cleanup)
          ;; Advancing to cleanup: advance phase, begin cleanup, then cross turn boundary
          (let [advanced-db (phases/advance-phase gdb active-player-id)
                cleanup-result (cleanup/begin-cleanup advanced-db active-player-id)]
            (if (:pending-selection cleanup-result)
              ;; Cleanup needs discard — pause with pending-selection
              {:app-db (-> app-db
                           (assoc :game/db (:db cleanup-result))
                           (assoc :game/pending-selection (:pending-selection cleanup-result)))}
              ;; No discard needed — cross turn boundary (start-turn switches active player)
              (let [db-after-cleanup (:db cleanup-result)
                    db-after-turn (phases/start-turn db-after-cleanup active-player-id)]
                ;; Always return at turn boundary — yield-impl handles continuation
                ;; via recursive ::yield dispatch (re-reads active player from db)
                {:app-db (-> app-db
                             (assoc :game/db db-after-turn)
                             (dissoc :bot/action-count))})))
          ;; Normal phase advance
          (let [advanced-db (phases/advance-phase gdb active-player-id)
                ;; Read actual phase from advanced-db (may differ from nxt
                ;; when combat is skipped due to no creatures)
                actual-phase (:game/phase (queries/get-game-state advanced-db))]
            ;; Check if new phase triggers anything on the stack
            (if (not (queries/stack-empty? advanced-db))
              ;; Stack triggered — stop and give priority
              {:app-db (assoc app-db :game/db advanced-db)}
              ;; Check stop (skipped in F6 mode)
              (if (and (not ignore-stops?)
                       (priority/check-stop advanced-db player-eid actual-phase))
                ;; Stop hit — pause here
                {:app-db (assoc app-db :game/db advanced-db)}
                ;; No stop or F6 — continue advancing
                (recur advanced-db)))))))))
```

**Key flow:**
1. **Loop:** Iteratively advance phases via `phases/advance-phase` until stop or turn boundary
2. **Cleanup special case:** On cleanup, advance phase, begin cleanup, cross turn boundary if no selection
3. **Stack check:** If phase triggers stack items, stop and return
4. **Stop check:** If player has `:player/stops` containing the current phase (unless F6), stop
5. **Loop or return:** If no stop, continue loop. If stop or turn boundary, return

**Critical:** Always stops at turn boundary (by returning before recursion). This is how human turn is handed to opponent.

---

## 3. HOW BOT STOPS ARE CONFIGURED

### Initial Game Setup: `/Users/abugosh/g/fizzle/src/main/fizzle/events/init.cljs`, lines 144-145

```clojure
(d/transact! conn [[:db/add player-eid :player/stops (:player stops)]
                   [:db/add opp-eid :player/stops (:opponent stops)]])
```

**The ONLY place stops are set during game initialization.**

**Where `stops` comes from (line 125):**
```clojure
stops (storage/load-stops)
```

### Default Stops: `/Users/abugosh/g/fizzle/src/main/fizzle/db/storage.cljs`, lines 90-95

```clojure
(defn default-stops
  "Return default phase stops.
   Player stops at main phases; opponent has no stops."
  []
  {:player #{:main1 :main2}
   :opponent #{}})
```

**Default behavior:**
- **Human player (:player stops):** `#{:main1 :main2}` — stops at both main phases
- **Opponent player (:opponent stops):** `#{}` — NO stops, bot phases advance uninterrupted (unless F6)

### Stops Storage: `/Users/abugosh/g/fizzle/src/main/fizzle/db/storage.cljs`, lines 108-125

```clojure
(defn save-stops!
  "Persist stops map to localStorage as EDN."
  [stops]
  (try
    (.setItem js/localStorage "fizzle-stops" (pr-str stops))
    (catch :default _)))

(defn load-stops
  "Load stops map from localStorage. Returns default-stops if missing or corrupt."
  []
  (try
    (let [raw (.getItem js/localStorage "fizzle-stops")]
      (if raw
        (reader/read-string raw)
        (default-stops)))
    (catch :default _
      (default-stops))))
```

**Stops are:**
- Persisted to `localStorage` via `save-stops!`
- Loaded from `localStorage` on game init via `load-stops`
- Fallback to `default-stops` if missing or corrupt

### Test Helper: `/Users/abugosh/g/fizzle/src/test/fizzle/test_helpers.cljs`, lines 182-197

**`add-opponent` helper:**
```clojure
(defn add-opponent
  "Add opponent with standard opponent settings and turn-based triggers.
   Opts map supports: {:bot-archetype :goldfish :stops #{:main1}}
   Returns updated db."
  ([db]
   (add-opponent db {}))
  ([db opts]
   (let [conn (d/conn-from-db db)
         overrides (cond-> {:player/name "Opponent"
                            :player/is-opponent true}
                     (:bot-archetype opts) (assoc :player/bot-archetype (:bot-archetype opts))
                     (:stops opts) (assoc :player/stops (:stops opts)))]
     (d/transact! conn (game-state/create-player-tx game-state/opponent-player-id overrides))
     (let [opp-eid (q/get-player-eid @conn game-state/opponent-player-id)]
       (d/transact! conn (turn-based/create-turn-based-triggers-tx opp-eid game-state/opponent-player-id)))
     @conn)))
```

**Bot setup in tests:**
1. Call `add-opponent` with `:bot-archetype` (e.g., `:goldfish`)
2. Optionally pass `:stops` map to override defaults
3. Creates opponent player with `player/bot-archetype` set
4. **If no `:stops` passed, defaults to `#{}` (no stops)** — bot phases run uninterrupted

**Critical:** Default test opponent has NO stops, so bot phases advance one-at-a-time through `bot-turn-advance-one-phase`, which allows bot interceptor to fire between phases.

---

## 4. NEGOTIATE-PRIORITY FUNCTION — COMPLETE FLOW

### File: `/Users/abugosh/g/fizzle/src/main/fizzle/events/priority_flow.cljs`, lines 222-275

**Purpose:** Handle priority passing between players after yields.
**Signature:** `(app-db) -> {:app-db app-db', :all-passed? bool}`

**Returns:** When `all-passed? true`, passes are RESET in the returned db.

**Implementation:**

```clojure
(defn negotiate-priority
  "Handle priority passing between players.
   Returns {:app-db updated-app-db, :all-passed? bool}.

   When all-passed? is true, passes are reset in the returned app-db.
   When all-passed? is false, priority has been transferred to the opponent.

   Bot auto-passing rules:
   - Bot players auto-pass when a human yields AND bot protocol returns :pass
   - When a bot yields, the human is NEVER auto-passed
   - During a bot's turn with empty stack, human opponent auto-passes too
   - During a bot's turn with non-empty stack, human keeps priority to respond
   - In auto-mode (:resolving, :f6), both players auto-pass regardless

   Pure function: (app-db) -> {:app-db app-db', :all-passed? bool}"
  [app-db]
  (let [game-db (:game/db app-db)
        auto-mode (priority/get-auto-mode game-db)
        holder-eid (priority/get-priority-holder-eid game-db)
        ;; Step 1: current player passes
        gdb (priority/yield-priority game-db holder-eid)
        active-player-id (queries/get-active-player-id gdb)
        active-eid (queries/get-player-eid gdb active-player-id)
        opponent-player-id (queries/get-other-player-id gdb active-player-id)
        ;; Step 2: determine if opponent should auto-pass
        should-auto-pass-opponent?
        (when opponent-player-id
          (let [opp-eid (queries/get-player-eid gdb opponent-player-id)]
            (or auto-mode
                (and (not (player-is-bot? gdb holder-eid))
                     (or (and (player-is-bot? gdb opp-eid)
                              (bot-would-pass? gdb opponent-player-id))
                         (and (player-is-bot? gdb active-eid)
                              (queries/stack-empty? gdb))))
                (and (player-is-bot? gdb active-eid)
                     (queries/stack-empty? gdb)
                     (not (priority/check-stop gdb active-eid
                                               (:game/phase (queries/get-game-state gdb))))))))
        gdb (if should-auto-pass-opponent?
              (let [opp-eid (queries/get-player-eid gdb opponent-player-id)]
                (-> gdb
                    (priority/yield-priority active-eid)
                    (priority/yield-priority opp-eid)))
              gdb)
        all-passed (or (not opponent-player-id)
                       (priority/both-passed? gdb))]
    (if all-passed
      {:app-db (assoc app-db :game/db (priority/reset-passes gdb))
       :all-passed? true}
      {:app-db (assoc app-db :game/db (priority/transfer-priority gdb holder-eid))
       :all-passed? false})))
```

### CRITICAL ANALYSIS: What happens after `reset-passes`?

**When `all-passed? true`:**
1. Both players have passed (`:game/passed` contains 2 player-eids)
2. `priority/reset-passes` CLEARS the `:game/passed` set
3. **Priority holder is NOT changed** — still holds whatever entity-id they held before
4. Returns `{:all-passed? true}`

**The problem:** After `reset-passes`, priority-holder might be the HUMAN (if human was last to yield). Then `yield-advance-phase` would see `bot-driving? = false` and use `advance-with-stops` instead of `bot-turn-advance-one-phase`.

**EXAMPLE SCENARIO (bot's turn, stack empty):**
1. Bot has priority, yields → `gdb = priority/yield-priority(game-db, bot-eid)`
2. Negotiate checks: human not bot, so auto-pass opponent? NO (human is not opponent, it's the active player)
3. Transfer priority to human → human now has priority
4. Human yields → `gdb = priority/yield-priority(game-db, human-eid)`
5. Both passed → `reset-passes` → **priority-holder is still HUMAN**
6. `yield-advance-phase` reads priority-holder-eid (human) → `bot-driving? = false` → uses `advance-with-stops`

**VERIFICATION NEEDED:** Who holds priority after `reset-passes`? Is it automatically switched to active player (bot), or does it stay with whoever held it before the reset?

**Answer from code:** `priority/reset-passes` only clears the `:game/passed` set. It does NOT modify `:game/priority`. So priority-holder is unchanged.

**BUT:** Line 115 in `phases/start-turn` sets priority to the NEW active player:
```clojure
[:db/add game-eid :game/priority next-player-eid]
```

So after the human's turn ends and bot's turn begins, priority is automatically switched to the bot.

---

## 5. BOT SPEC PHASE-ACTIONS

### File: `/Users/abugosh/g/fizzle/src/main/fizzle/bots/definitions.cljs`

**Goldfish spec (lines 9-17):**
```clojure
(def goldfish-spec
  {:bot/name "Goldfish"
   :bot/deck [{:card/id :plains :count 12}
              {:card/id :island :count 12}
              {:card/id :swamp :count 12}
              {:card/id :mountain :count 12}
              {:card/id :forest :count 12}]
   :bot/phase-actions {:main1 :play-land}
   :bot/priority-rules []})
```

**Burn spec (lines 20-31):**
```clojure
(def burn-spec
  {:bot/name "Burn"
   :bot/deck [{:card/id :mountain :count 20}
              {:card/id :lightning-bolt :count 40}]
   :bot/phase-actions {:main1 :play-land}
   :bot/attack-strategy :all
   :bot/priority-rules
   [{:rule/mode :auto
     :rule/conditions [{:check :zone-contains :zone :hand :player :self :card-id :lightning-bolt}
                       {:check :has-untapped-source :color :red}
                       {:check :stack-empty}]
     :rule/action {:action :cast-spell :card-id :lightning-bolt :target :opponent}}]})
```

**Phase actions:**
- **Goldfish:** Only `{:main1 :play-land}` — plays a land at main1 if one is available, else passes
- **Burn:** Only `{:main1 :play-land}` — plays a land at main1 if available, else checks priority rules to cast Lightning Bolt

**How they're used:**
File: `/Users/abugosh/g/fizzle/src/main/fizzle/bots/interceptor.cljs`, lines 217-225:
```clojure
phase-action (when archetype
               (bot/bot-phase-action archetype current-phase game-db player-id))
```

Then (lines 221-225):
```clojure
(and (= :play-land (:action phase-action))
     (find-bot-land-to-play game-db player-id))
(let [land-id (find-bot-land-to-play game-db player-id)]
  {:db (assoc app-db :bot/action-count (inc bot-action-count))
   :fx [[:dispatch [:fizzle.events.lands/play-land land-id player-id]]]})
```

**Key:** Phase actions only specify WHICH phase triggers land-play. Actual land decision is made by `find-bot-land-to-play`, which uses `rules/can-play-land?` to enforce one-land-per-turn.

---

## 6. FULL YIELD CASCADE DURING BOT TURN

### Game initialization (via `events/init.cljs`):

**Initial state:**
- Active player: human (:player-1)
- Priority holder: human (:player-1)
- Phase: :main1
- Turn: 1

### Human plays a card and yields:

1. **Human casts spell** → event dispatches `:fizzle.events.priority-flow/yield`
2. **yield-handler** (line 305-336) calls `yield-impl`
3. **yield-impl** (line 278-296):
   - Calls `negotiate-priority` → human yields, bot auto-passes (if conditions met) or transfers priority
   - If all-passed:
     - Stack not empty → resolve one item
     - Stack empty → advance phases
4. **advance-with-stops** (human's turn):
   - Human has stops at `:main1` and `:main2`, so it halts at `:main2`

### Human yields at main2:

1. **yield-impl** → negotiate-priority
2. **negotiate-priority:**
   - Human yields (holder-eid = human)
   - Bot not bot (:player-is-bot? human) = false, so no auto-pass-opponent logic
   - Transfer priority to opponent (bot) → `transfer-priority` sets `:game/priority` to bot-eid
   - Return `{:all-passed? false}`
3. **yield-impl** → not all-passed, return with `:continue-yield? false` (wait for bot)
4. **bot interceptor** runs → `bot-decide` dispatched
5. **bot-decide-handler** (interceptor.cljs, line 177-239):
   - Checks `:bot/action-count` < 50
   - Gets bot archetype, current phase, phase action
   - If `play-land` action: dispatch land play event
   - Else: check priority rules, cast spell or yield
6. **If bot passes** → dispatches `::yield`
7. **yield-impl** → negotiate-priority
8. **negotiate-priority:**
   - Bot yields (holder-eid = bot)
   - Check should-auto-pass-opponent:
     - `(not (player-is-bot? gdb holder-eid))` = false (holder IS bot)
     - Line 259: `(and (player-is-bot? gdb active-eid) (queries/stack-empty? gdb))` = true (bot is active, stack empty)
       - If bot has stop at current phase, don't auto-pass (line 261)
       - Else, auto-pass opponent (human)
   - **Result:** Human auto-passes (unless bot has a stop), then both passed
   - Reset passes, advance phases via `bot-turn-advance-one-phase`
9. **yield-advance-phase** (bot-driving? = true):
   - Bot turn advance one phase at a time
   - Return without `continue-yield?` (pause for bot interceptor)
   - Event loop fires bot interceptor again for next phase

### End of bot's turn (bot at cleanup):

1. **bot-turn-advance-one-phase**:
   - Detects `nxt = :cleanup`
   - Advances to cleanup, clears pass flags, crosses turn boundary
   - `phases/start-turn` sets:
     - Active player: human (:player-1)
     - Priority holder: human (:player-1)
     - Phase: :untap
     - Turn: 2
2. **yield-advance-phase** detects turn boundary → returns with `continue-yield? true`
3. **yield-handler** dispatches `::yield` again
4. **yield-impl** re-reads active player (human) and re-checks stops
5. **advance-with-stops** (human's turn again):
   - Advances from :untap to :main1
   - Hits human's stop at :main1
   - Returns

---

## 7. WHERE HUMAN'S STOPS ARE SET

### Storage (persisted to localStorage):
File: `/Users/abugosh/g/fizzle/src/main/fizzle/db/storage.cljs`
- `load-stops` (line 116): Loads `:player` and `:opponent` stops from localStorage
- `default-stops` (line 90): Human has `#{:main1 :main2}`, bot has `#{}`

### At game init:
File: `/Users/abugosh/g/fizzle/src/main/fizzle/events/init.cljs`, line 144-145:
```clojure
(d/transact! conn [[:db/add player-eid :player/stops (:player stops)]
                   [:db/add opp-eid :player/stops (:opponent stops)]])
```

Where `stops = (storage/load-stops)` (line 125).

### Stops data structure (in Datascript):
Each player entity has `:player/stops` attribute = a set of phase keywords like `#{:main1 :main2}`.

### Checking stops:
File: `/Users/abugosh/g/fizzle/src/main/fizzle/engine/priority.cljs`, lines 82-94:
```clojure
(defn check-stop
  "Check if a player has a stop set for a given phase.
   Returns true if the player's :player/stops contains the phase."
  [db player-eid phase]
  (let [stops (:player/stops (d/pull db [:player/stops] player-eid))]
    (boolean (and stops (contains? stops phase)))))
```

Called in:
- `advance-with-stops` (line 60): Stops human phases
- `yield-advance-phase` (line 183): Checks if bot has a stop (bot shouldn't, unless test sets one)
- `negotiate-priority` (line 261): Checks if bot has a stop during empty-stack (auto-pass logic)

---

## 8. HOW BOTH PLAYERS ARE HANDLED IN TESTS

### Test setup via `test_helpers.cljs`:

**`create-test-db` (lines 27-48):**
```clojure
(defn create-test-db
  "Create a game state with all card definitions loaded.
   No-arg version: all-zero mana pool, standard player.
   Opts map supports: {:mana {:blue 1 :black 3} :life 20 :storm-count 0 :land-plays 1}"
  ([]
   (create-test-db {}))
  ([opts]
   (let [conn (d/create-conn schema)
         ...
         overrides (cond-> {:player/name "Player"
                            :player/mana-pool mana-pool}
                     (:life opts) (assoc :player/life (:life opts))
                     ...)]
     (d/transact! conn cards/all-cards)
     (d/transact! conn (game-state/create-player-tx game-state/human-player-id overrides))
     (let [player-eid (q/get-player-eid @conn game-state/human-player-id)]
       (d/transact! conn (game-state/create-game-entity-tx player-eid {}))
       ...
       (when-let [stops (:stops opts)]
         (d/transact! conn [[:db/add player-eid :player/stops stops]])))
     @conn)))
```

**Human player** (player-1):
- Created with default overrides
- If `:stops` in opts, set `:player/stops` to that set
- Else, defaults to whatever `create-player-tx` sets (which is nil, no default)

**`add-opponent` (lines 182-197):**
```clojure
(defn add-opponent
  "Add opponent with standard opponent settings and turn-based triggers.
   Opts map supports: {:bot-archetype :goldfish :stops #{:main1}}
   Returns updated db."
  ([db]
   (add-opponent db {}))
  ([db opts]
   (let [conn (d/conn-from-db db)
         overrides (cond-> {:player/name "Opponent"
                            :player/is-opponent true}
                     (:bot-archetype opts) (assoc :player/bot-archetype (:bot-archetype opts))
                     (:stops opts) (assoc :player/stops (:stops opts)))]
     ...)))
```

**Opponent player** (player-2):
- Created with `:player/is-opponent true`
- If `:bot-archetype` in opts, set `:player/bot-archetype` (e.g., `:goldfish`)
- If `:stops` in opts, set `:player/stops`
- **Else, no stops** (defaults to nil, which means no phase stops)

---

## SUMMARY TABLE: Bot-Specific Functions & Their Roles

| Function | File | Lines | Purpose | Who Calls | Bot-Only? |
|----------|------|-------|---------|-----------|-----------|
| `player-is-bot?` | priority_flow | 97-102 | Check if player entity has bot archetype | 5 places | No (works for any player) |
| `bot-would-pass?` | priority_flow | 105-114 | Call bot protocol to check if would pass | negotiate-priority | Yes (calls bot protocol) |
| `bot-turn-advance-one-phase` | priority_flow | 117-141 | Advance one phase during bot's turn | yield-advance-phase | Yes (bot-only path) |
| `yield-advance-phase` | priority_flow | 144-219 | Decide whether to batch or one-phase advance | yield-impl | No (bot/human branching) |
| `advance-with-stops` | priority_flow | 15-64 | Batch advance to stop or turn boundary | yield-advance-phase (human path only) | No (human path only, but bot-agnostic) |
| `negotiate-priority` | priority_flow | 222-275 | Pass priority & auto-pass opponents | yield-impl | No (human & bot logic) |
| `check-stop` | priority.cljs | 89-94 | Check if player has phase stop | Multiple | No (works for any player) |

---

## KEY FINDINGS: What Would Break If Functions Didn't Exist

### 1. **Without `player-is-bot?`:**
- No way to identify bots (would need role dispatch everywhere)
- `bot-driving?` would fail
- `negotiate-priority` auto-pass logic would break
- Bot protocol would be called unconditionally (inefficient, error on human)

### 2. **Without `bot-would-pass?`:**
- Bot protocol wouldn't be consulted during priority passing
- Bots couldn't hold priority for reactive spells
- Burn bot's Lightning Bolt priority rules would never fire
- Auto-pass logic would be generic (not responsive)

### 3. **Without `bot-turn-advance-one-phase`:**
- Bot phases would advance in batches (like humans)
- Bot interceptor wouldn't fire between phases
- Bots couldn't play lands mid-turn or cast spells reactively
- Bot turns would collapse to single atomic action

### 4. **Without `yield-advance-phase`:**
- No distinction between bot and human phase advancement
- Bot turns would use `advance-with-stops` and run to human's stops (if human configured them for bot)
- Priority logic would be one-size-fits-all
- F6 (force all) mode might not work correctly on bot turns

### 5. **Without `advance-with-stops`:**
- Human turns would advance one phase at a time (like bots)
- Humans couldn't batch through multiple phases to their next stop
- Phase stops would be meaningless for humans
- Turn interaction would require stopping after every phase

### 6. **Without `negotiate-priority`:**
- No auto-pass logic
- Both players would need to manually pass after every yield
- Burn bot would hang after casting spell (opponent wouldn't auto-pass)
- Game would be unplayable with bots

---

## CRITICAL ARCHITECTURE INSIGHTS

### 1. **Priority Holder Ownership**
- Priority-holder entity-id can be either player at any time
- Updated via `transfer-priority` and `reset-passes` only
- Not automatically switched to active player (except at turn start in `phases/start-turn`)
- **Consequence:** After human and bot both pass, priority might be on human before advancing to bot's turn

### 2. **Stop Configuration is Per-Player**
- Stops stored as `:player/stops` set on each player entity
- Human defaults: `#{:main1 :main2}` (loaded from storage)
- Bot defaults: `#{}` (no stops, unless test explicitly sets)
- Stops are checked identically for both players via `check-stop`
- **Consequence:** Bot can have stops configured, but doesn't by default

### 3. **Bot Phases are One-at-a-Time**
- During bot's turn (active-player = bot), `yield-advance-phase` uses `bot-turn-advance-one-phase`
- This advances exactly one phase per yield
- Returns control to event loop so bot interceptor fires
- **Consequence:** Bot can take actions at every phase (land plays, priority decisions)

### 4. **Human Phases are Batched**
- During human's turn (active-player = human), `yield-advance-phase` uses `advance-with-stops`
- This advances through multiple phases to the next stop
- Returns control at stop, not at every phase
- **Consequence:** Human must configure stops or all phases blur together

### 5. **Bot Protocol is Consulted During Priority, Not Phase**
- `bot-would-pass?` is called during `negotiate-priority` to decide auto-passing
- `bot-phase-action` is called during `bot-decide-handler` to decide land plays
- These are SEPARATE check points (one per priority pass, one per phase)
- **Consequence:** Bot must have both rules (priority-rules for instants, phase-actions for land plays)

### 6. **Turn Boundary is Atomic**
- Cleanup, turn boundary crossing, and priority reset happen together in `bot-turn-advance-one-phase` or `advance-with-stops`
- Active player and priority-holder are updated together at next turn start
- **Consequence:** No partial turn states; full state consistent at turn boundaries

