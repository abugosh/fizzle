# SBA Call Chain Traces - Complete Paths

## TRACE 1: Spell Resolution (::resolve-top event)

**Entry**: `events/resolution.cljs:111-120` (event handler)
```clojure
(rf/reg-event-db
  ::resolve-top
  (fn [db _]
    (let [result (resolve-one-item (:game/db db))]  ;; LINE 114
      (if (:pending-selection result)
        ...
        (cleanup/maybe-continue-cleanup
          (assoc db :game/db (:db result)))))))
```

**↓ resolve-one-item**: `events/resolution.cljs:63-108`
```clojure
(defn resolve-one-item
  "Resolve the topmost stack-item."
  [game-db]
  (let [game-db (clear-peek-result game-db)
        top (stack/get-top-stack-item game-db)]
    (if-not top
      {:db game-db}
      (let [controller (:stack-item/controller top)
            result (engine-resolution/resolve-stack-item game-db top)]  ;; LINE 72
        (cond
          (:needs-selection result)
          (build-selection-from-result game-db controller top result)  ;; SELECTION PATH
          :else
          {:db (stack/remove-stack-item (:db result) (:db/id top))})))))  ;; LINE 108
```

**↓ engine-resolution/resolve-stack-item** (multimethod, not shown in full)
```clojure
;; In engine/resolution.cljs
;; Returns: {:db db' :needs-selection effect} OR {:db db'}
```

**↓ reduce-effects**: `engine/effects.cljs:186-211`
```clojure
(defn reduce-effects [db player-id effects object-id]
  (loop [db db
         [effect & remaining] (seq effects)]
    (if-not effect
      {:db (sba/check-and-execute-sbas db)}           ;; ✅ SBA LINE 207
      (let [result (execute-effect-checked db player-id effect object-id)]
        (if (:needs-selection result)
          (assoc result :remaining-effects (vec remaining))
          (recur (sba/check-and-execute-sbas (:db result)) remaining))))))  ;; ✅ SBA LINE 211
```

**↓ engine/state-based.cljs:66**
```clojure
(defn check-and-execute-sbas
  "Check and execute all applicable state-based actions."
  [db]
  ;; Implementation: loop checking conditions, execute actions
  ...)
```

**Returns**: `{:db db'}`

**SBA Calls**:
- ✅ **Line 207**: After all effects consumed
- ✅ **Line 211**: After each effect execution (in recur)
- ✅ **Interceptor**: Also fires after event completes (REDUNDANT)

---

## TRACE 2: Selection Confirmation (::confirm-selection event)

**Entry**: `events/selection.cljs:32-35` (event handler)
```clojure
(rf/reg-event-db
  ::confirm-selection
  (fn [db _]
    (core/confirm-selection-handler db)))  ;; LINE 35
```

**↓ confirm-selection-handler**: `events/selection/core.cljs:455-464`
```clojure
(defn confirm-selection-handler
  "Handle confirm-selection event."
  [app-db]
  (let [selection (:game/pending-selection app-db)]
    (if (validation/validate-selection selection)
      (confirm-selection-impl app-db)  ;; LINE 463
      app-db)))
```

**↓ confirm-selection-impl**: `events/selection/core.cljs:368-392`
```clojure
(defn confirm-selection-impl
  "Shared wrapper for all selection confirmations."
  [app-db]
  (let [selection (:game/pending-selection app-db)
        on-complete (:selection/on-complete selection)
        game-db (:game/db app-db)
        result (execute-confirmed-selection game-db selection)  ;; LINE 386 (multimethod)
        lifecycle (or (:selection/lifecycle selection) :standard)]
    (case lifecycle
      :chaining (chaining-path app-db result selection on-complete)
      :finalized (finalized-path app-db result on-complete ...)
      :standard (standard-path app-db result selection on-complete))))  ;; LINE 392
```

**↓ standard-path** (default lifecycle): `events/selection/core.cljs:308-336`
```clojure
(defn- standard-path
  "Standard lifecycle: execute remaining-effects and cleanup source."
  [app-db result selection on-complete]
  (let [remaining-effects (:selection/remaining-effects selection)
        player-id (:selection/player-id selection)
        object-id (:selection/spell-id selection)
        remaining-result (effects/reduce-effects (:db result) player-id  ;; LINE 316-317
                                                 (or remaining-effects []))]
    (if (:needs-selection remaining-result)
      ;; Pause for next interactive effect
      (let [next-effect (:needs-selection remaining-result)
            ...
            next-sel (build-selection-for-effect (:db remaining-result) ...)]
        ...)
      ;; No more interactive: cleanup and continue
      (let [db-final (cleanup-selection-source (:db remaining-result) selection)]
        ...))))
```

**↓ reduce-effects**: (same as TRACE 1)
```clojure
;; Calls: sba/check-and-execute-sbas at lines 207 and 211
```

**Returns**: `{:db db'}`

**SBA Calls**:
- ✅ **reduce-effects lines 207, 211**: During remaining-effects execution
- ✅ **Interceptor**: Also fires after event completes (REDUNDANT)

---

## TRACE 3: Mana Ability (::activate-mana-ability event)

**Entry**: `events/abilities.cljs:25-30` (event handler)
```clojure
(rf/reg-event-db
  ::activate-mana-ability
  (fn [db [_ object-id mana-color player-id]]
    (let [game-db (:game/db db)
          pid (or player-id (queries/get-human-player-id game-db))]
      (assoc db :game/db (activate-mana-ability game-db pid object-id mana-color)))))  ;; LINE 30
```

**↓ activate-mana-ability**: `engine/mana_activation.cljs:18-119`
```clojure
(defn activate-mana-ability
  "Activate a mana ability on a land."
  [db player-id object-id mana-color]
  (if-not (priority/in-priority-phase? (:game/phase (q/get-game-state db)))
    db
    (let [obj (q/get-object db object-id)
          ...
          db-after-costs (abilities/pay-all-costs db object-id (:ability/cost mana-ability))  ;; LINE 80
          db-after-produces (if produces
                              (let [resolved-mana ...]
                                (effects/execute-effect db-after-costs ...))  ;; LINE 89
                              db-after-costs)
          db-after-effects (reduce (fn [db' effect]  ;; LINE 97
                                     (effects/execute-effect db' player-id resolved-effect))
                                   db-after-produces
                                   (:ability/effects mana-ability []))
          db-after-triggers (dispatch/dispatch-event db-after-effects  ;; LINE 112
                                                     (game-events/permanent-tapped-event ...))
          db-after-sbas (state-based/check-and-execute-sbas db-after-triggers)]  ;; ✅ SBA LINE 115
      db-after-sbas)))
```

**Returns**: `db'`

**SBA Calls**:
- ✅ **Line 115**: Direct call in engine/mana_activation.cljs
- ✅ **Interceptor**: Also fires after event completes (REDUNDANT)

---

## TRACE 4: Toggle Selection with Auto-Confirm

**Entry**: `events/selection.cljs:26-29` (event handler)
```clojure
(rf/reg-event-db
  ::toggle-selection
  (fn [db [_ id]]
    (core/toggle-selection-impl db id)))  ;; LINE 29
```

**↓ toggle-selection-impl**: `events/selection/core.cljs:399-448`
```clojure
(defn toggle-selection-impl
  "Handle toggling a selection item."
  [app-db id]
  (let [selection (get app-db :game/pending-selection)
        ...
        [new-db selected?] (cond
                             currently-selected? [... false]
                             (= select-count 1) [... true]
                             ...)]
    ;; Auto-confirm when select-count=1 AND :selection/auto-confirm?=true
    (if (and selected?
             (= select-count 1)
             (:selection/auto-confirm? selection))
      (confirm-selection-impl new-db)  ;; LINE 447 - IF auto-confirm
      new-db)))
```

**↓ confirm-selection-impl**: (same as TRACE 2)
```clojure
;; Flows through standard-path → reduce-effects → SBA
```

**Returns**: `app-db'`

**SBA Calls**:
- ✅ **reduce-effects**: If auto-confirm path is taken
- ✅ **Interceptor**: Also fires after event completes (REDUNDANT if auto-confirm)

---

## TRACE 5: Mana Allocation Auto-Confirm

**Entry**: `events/selection/costs.cljs:787-790` (event handler)
```clojure
(rf/reg-event-db
  ::allocate-mana-color
  (fn [db [_ color]]
    (allocate-mana-color-impl db color)))  ;; LINE 790
```

**↓ allocate-mana-color-impl**: `events/selection/costs.cljs:750-767`
```clojure
(defn allocate-mana-color-impl
  "Allocate one generic mana to a color."
  [app-db color]
  (let [selection (:game/pending-selection app-db)
        remaining (get selection :selection/generic-remaining 0)
        ...
        new-remaining (dec remaining)
        ...
        updated-db (-> app-db
                       (assoc-in [:game/pending-selection :selection/generic-remaining] new-remaining)
                       (assoc-in [:game/pending-selection :selection/allocation] new-allocation)
                       (assoc-in [:game/pending-selection :selection/remaining-pool] new-pool))]
    (if (zero? new-remaining)
      (core/confirm-selection-impl updated-db)  ;; LINE 766 - IF allocation complete
      updated-db)))
```

**↓ confirm-selection-impl**: (same as TRACE 2)
```clojure
;; Flows through standard-path → reduce-effects → SBA
```

**Returns**: `app-db'`

**SBA Calls**:
- ✅ **reduce-effects**: If allocation complete and auto-confirm triggered
- ✅ **Interceptor**: Also fires after event completes (REDUNDANT)

---

## TRACE 6: Combat Attacker Selection (Bot Path)

**Entry**: `events/resolution.cljs:63-108` (resolve-one-item function)
```clojure
(defn resolve-one-item [game-db]
  (let [game-db (clear-peek-result game-db)
        top (stack/get-top-stack-item game-db)]
    (if-not top
      {:db game-db}
      (let [controller (:stack-item/controller top)
            result (engine-resolution/resolve-stack-item game-db top)]
        (cond
          (:needs-attackers result)
          (let [eligible (:eligible-attackers result)
                archetype (bot-protocol/get-bot-archetype game-db controller)]
            (if archetype
              ;; Bot path: choose automatically, confirm inline
              (let [chosen (bot-protocol/bot-choose-attackers archetype eligible)  ;; LINE 84
                    sel (sel-combat/build-attacker-selection ...)  ;; LINE 85-86
                    sel (assoc sel :selection/selected (set chosen))  ;; LINE 87
                    app-db {:game/db game-db :game/pending-selection sel}
                    result-db (sel-core/confirm-selection-impl app-db)]  ;; ✅ LINE 89
                {:db (:game/db result-db)})
              ;; Human path: return pending selection for UI
              {:db game-db
               :pending-selection (sel-combat/build-attacker-selection ...)}))))))
```

**↓ sel-core/confirm-selection-impl**: (same as TRACE 2)
```clojure
;; Flows through finalized-path → no reduce-effects
;; No SBA call here (no effects to execute)
;; Interceptor would NOT fire (no event)
```

**Returns**: `{:db game-db'}`

**SBA Calls**:
- ❌ **No SBA here**: Combat attacker selection doesn't execute effects
- ✅ **Interceptor**: Fires after ::resolve-top event completes (if resolve-top called)

---

## TRACE 7: Continuation (:resolve-one-and-stop)

**Entry**: `events/selection/core.cljs:334-335` (standard-path applies continuation)
```clojure
(if on-complete
  (apply-continuation on-complete updated)  ;; LINE 335
  updated)
```

**↓ apply-continuation** multimethod: `events/selection/core.cljs:194-211`
```clojure
(defmulti apply-continuation
  (fn [continuation _app-db] (:continuation/type continuation)))

;; Dispatch on continuation type
```

**↓ For :resolve-one-and-stop**: `events/priority_flow.cljs:374-376`
```clojure
(defmethod sel-core/apply-continuation :resolve-one-and-stop
  [_ app-db]
  (resolve-one-and-stop app-db))  ;; LINE 376
```

**↓ resolve-one-and-stop**: `events/priority_flow.cljs:359-369`
```clojure
(defn- resolve-one-and-stop
  "Resolve the top stack item with temporary :resolving auto-mode."
  [app-db]
  (if (or (:game/pending-selection app-db)
          (queries/stack-empty? (:game/db app-db)))
    app-db
    (let [adb (update app-db :game/db priority/set-auto-mode :resolving)
          result (yield-impl adb)]  ;; LINE 368
      (update (:app-db result) :game/db priority/clear-auto-mode))))
```

**↓ yield-impl**: `events/priority_flow.cljs:274-292`
```clojure
(defn yield-impl
  "Core priority passing logic."
  [app-db]
  (let [result (negotiate-priority app-db)]
    (if (:all-passed? result)
      (let [negotiated-app-db (:app-db result)
            game-db (:game/db negotiated-app-db)]
        (if (not (queries/stack-empty? game-db))
          (yield-resolve-stack negotiated-app-db)  ;; LINE 290 (if stack not empty)
          (yield-advance-phase negotiated-app-db)))
      {:app-db (:app-db result)})))
```

**↓ yield-resolve-stack** (if stack not empty): `events/priority_flow.cljs:65-92`
```clojure
(defn- yield-resolve-stack
  "Handle yield when all passed and stack is not empty: resolve top item."
  [app-db]
  (let [game-db (:game/db app-db)
        ...
        result (resolution/resolve-one-item game-db)]  ;; LINE 71
    (if (:pending-selection result)
      ...
      (let [resolved-db (:db result)]
        ...))))
```

**↓ resolve-one-item**: (same as TRACE 1)
```clojure
;; → engine-resolution/resolve-stack-item
;; → reduce-effects
;; → sba/check-and-execute-sbas (lines 207, 211)
```

**Returns**: `app-db'`

**SBA Calls**:
- ✅ **reduce-effects**: If resolve-one-item → reduce-effects path taken
- ❌ **No other call** unless stack is resolved

---

## SUMMARY: Where SBA Happens

| Trace | Entry Point | Direct SBA | Via reduce-effects | Via Interceptor |
|-------|-------------|-----------|-------------------|-----------------|
| 1 | `::resolve-top` | ❌ | ✅ (lines 207, 211) | ✅ (redundant) |
| 2 | `::confirm-selection` | ❌ | ✅ (if standard path) | ✅ (redundant) |
| 3 | `::activate-mana-ability` | ✅ (line 115) | ❌ | ✅ (redundant) |
| 4 | `::toggle-selection` | ❌ | ✅ (if auto-confirm) | ✅ (maybe redundant) |
| 5 | `::allocate-mana-color` | ❌ | ✅ (if complete) | ✅ (maybe redundant) |
| 6 | `::resolve-top` (combat) | ❌ | ❌ | ✅ (only SBA source) |
| 7 | Continuation | ❌ | ✅ (if stack resolved) | ❌ (not an event) |

**Conclusion**:
- Traces 1, 2, 3, 4, 5 have **redundant SBA calls** (both direct + interceptor)
- Trace 6 (combat) relies **only on interceptor**
- Trace 7 (continuation) has **no event**, so interceptor doesn't fire
