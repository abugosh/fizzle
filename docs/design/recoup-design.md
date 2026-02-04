# Recoup Implementation Design

> **Status:** Ready for Approval
> **Card:** Recoup (1R Sorcery)
> **Oracle Text:** Target sorcery card in your graveyard gains flashback until end of turn. The flashback cost is equal to the card's mana cost. Flashback {3}{R}

## Overview

Implementing Recoup requires three new infrastructure systems:

1. **Grant System** - Temporarily grant abilities/alternate costs to card instances
2. **Cast-Time Targeting System** - Declare and validate targets when casting
3. **Cleanup System** - Remove expired grants at end of turn

These systems are designed to be reusable for future cards.

---

## 1. Grant System

### Schema Addition

```clojure
;; In db/schema.cljs
:object/grants {}  ; Vector of grant maps
```

### Grant Structure

```clojure
{:grant/id        uuid              ; Unique identifier for this grant
 :grant/type      keyword           ; :alternate-cost, :ability, :keyword, :static-effect
 :grant/source    uuid              ; Object ID that created this grant (Recoup)
 :grant/expires   map               ; Expiration condition (see below)
 :grant/data      map}              ; The granted thing (alternate-cost, ability, etc.)
```

### Expiration Format

Explicit turn/phase tracking for flexible expiration:

```clojure
;; "Until end of turn" (Recoup)
{:expires/turn 3 :expires/phase :cleanup}

;; "Until end of your next turn"
{:expires/turn 4 :expires/phase :cleanup}

;; "Until end of combat"
{:expires/turn 3 :expires/phase :end-of-combat}

;; Permanent (never expires, e.g., Animate Dead)
{:expires/permanent true}

;; Until leaves current zone
{:expires/until-leaves-zone :graveyard}
```

### Recoup's Grant

When Recoup resolves targeting a sorcery (e.g., Ill-Gotten Gains with mana cost {2BB}):

```clojure
{:grant/id #uuid "..."
 :grant/type :alternate-cost
 :grant/source recoup-object-id
 :grant/expires {:expires/turn 3 :expires/phase :cleanup}
 :grant/data {:alternate/id :granted-flashback
              :alternate/zone :graveyard
              :alternate/mana-cost {:colorless 2 :black 2}  ; Copied from target
              :alternate/on-resolve :exile}}
```

### API Functions

```clojure
;; engine/grants.cljs

(defn add-grant
  "Add a grant to an object. Returns updated db."
  [db object-id grant])

(defn remove-grant
  "Remove a specific grant by ID. Returns updated db."
  [db object-id grant-id])

(defn remove-grants-by-source
  "Remove all grants from a source object. Returns updated db."
  [db source-id])

(defn get-grants
  "Get all grants on an object."
  [db object-id])

(defn get-grants-by-type
  "Get grants of a specific type on an object."
  [db object-id grant-type])

(defn expire-grants
  "Remove all grants that have expired given current turn/phase. Returns updated db."
  [db current-turn current-phase])
```

### Integration with Casting

Modify `rules/get-casting-modes` to include granted alternate costs:

```clojure
(defn get-casting-modes [db player-id object-id]
  (if-let [obj (q/get-object db object-id)]
    (let [card (:object/card obj)
          current-zone (:object/zone obj)
          ;; Card-level alternate costs
          card-alternates (get-alternate-modes card current-zone)
          ;; Object-level granted alternate costs
          grants (get-grants-by-type db object-id :alternate-cost)
          granted-alternates (->> grants
                                  (filter #(= current-zone
                                             (get-in % [:grant/data :alternate/zone])))
                                  (map #(alternate-to-mode (:grant/data %))))]
      (case current-zone
        :hand (into [(get-primary-mode card)]
                    (concat card-alternates granted-alternates))
        :graveyard (vec (concat card-alternates granted-alternates))
        []))
    []))
```

---

## 2. Cast-Time Targeting System

### Targeting Declaration on Cards

New card attribute for spells/abilities that target:

```clojure
;; On Recoup card definition
:card/targeting [{:target/id :graveyard-sorcery
                  :target/zone :graveyard
                  :target/controller :self
                  :target/criteria {:card/types #{:sorcery}}
                  :target/required true}]

;; On Deep Analysis
:card/targeting [{:target/id :player
                  :target/type :player
                  :target/options [:any-player]
                  :target/required true}]
```

### Target Structure

```clojure
{:target/id       keyword    ; Identifier for this target slot
 :target/type     keyword    ; :object or :player
 :target/zone     keyword    ; For objects: which zone (:graveyard, :battlefield, etc.)
 :target/controller keyword  ; :self, :opponent, :any
 :target/criteria map        ; Card attributes to match (for object targets)
 :target/options  vector     ; For player targets: [:self :opponent :any-player]
 :target/required boolean}   ; Must have valid target to cast
```

### Ability Targeting

Abilities use the same pattern with `:ability/targeting`:

```clojure
;; Cephalid Coliseum threshold ability
{:ability/type :activated
 :ability/targeting [{:target/id :player
                      :target/type :player
                      :target/options [:any-player]
                      :target/required true}]
 :ability/effects [...]}
```

### Casting Flow Changes

Current flow:
1. Check can-cast? (mana only)
2. Pay mana, move to stack
3. On resolution, prompt for selection if needed

New flow:
1. Check can-cast? (mana + valid targets exist)
2. **Prompt for target selection**
3. **Store targets on object**
4. Pay mana, move to stack
5. On resolution, **verify targets still legal**
6. If targets illegal, **fizzle** (no effect, move per mode)

### Schema Change

Update existing schema to store target map:

```clojure
;; Change from ref collection to map
:object/targets {}  ; Map of target-ref keyword → target-id (UUID or player-id)

;; Example stored value:
;; {:graveyard-sorcery #uuid "abc-123"
;;  :player :player-1}
```

This supports spells with multiple target slots.

### API Functions

```clojure
;; engine/targeting.cljs

(defn get-targeting-requirements
  "Get targeting requirements for a card/ability."
  [card-or-ability])

(defn find-valid-targets
  "Find all objects/players that are valid targets for a requirement."
  [db player-id target-requirement])

(defn has-valid-targets?
  "Check if at least one valid target exists for each required target."
  [db player-id card-or-ability])

(defn target-still-legal?
  "Check if a chosen target is still legal (zone, attributes match)."
  [db target-id target-requirement])

(defn all-targets-legal?
  "Check if all chosen targets on an object are still legal."
  [db object-id])

(defn build-target-selection
  "Build selection state for choosing targets."
  [db player-id object-id target-requirement])
```

### Fizzle on Resolution

When resolving a spell with targets:

```clojure
(defn resolve-spell [db player-id object-id]
  (let [obj (q/get-object db object-id)
        card (:object/card obj)
        targeting (:card/targeting card)]
    (if (and (seq targeting)
             (not (all-targets-legal? db object-id)))
      ;; Fizzle: move to destination without executing effects
      (let [cast-mode (:object/cast-mode obj)
            destination (or (:mode/on-resolve cast-mode) :graveyard)]
        (zones/move-to-zone db object-id destination))
      ;; Normal resolution
      (resolve-spell-effects db player-id object-id))))
```

---

## 3. Cleanup System

### Turn-Based Registration

```clojure
;; In turn-based.cljs
(registry/register-trigger!
  {:trigger/id :cleanup-expire-grants
   :trigger/source :game-rule
   :trigger/type :expire-grants
   :trigger/event-type :phase-entered
   :trigger/filter {:event/phase :cleanup}
   :trigger/uses-stack? false
   :trigger/controller :player-1})
```

### Effect Handler

```clojure
;; In trigger dispatch or effects
(defmethod execute-trigger :expire-grants
  [db trigger]
  (let [game-state (q/get-game-state db)
        current-turn (:game/turn game-state)]
    (grants/expire-grants db current-turn :cleanup)))
```

### Expiration Logic

```clojure
(defn grant-expired?
  "Check if a grant has expired given current turn/phase."
  [{:keys [expires/turn expires/phase expires/permanent]} current-turn current-phase]
  (cond
    permanent false
    (< turn current-turn) true
    (and (= turn current-turn)
         (phase-reached? current-phase phase)) true
    :else false))

(defn expire-grants
  "Remove all expired grants from all objects."
  [db current-turn current-phase]
  (let [all-objects (q/get-all-objects-with-grants db)]
    (reduce (fn [db obj]
              (let [grants (:object/grants obj)
                    active (remove #(grant-expired? (:grant/expires %)
                                                    current-turn
                                                    current-phase)
                                   grants)]
                (if (= (count grants) (count active))
                  db
                  (update-grants db (:object/id obj) active))))
            db
            all-objects)))
```

---

## 4. Recoup Card Definition

```clojure
(def recoup
  {:card/id :recoup
   :card/name "Recoup"
   :card/cmc 2
   :card/mana-cost {:colorless 1 :red 1}
   :card/colors #{:red}
   :card/types #{:sorcery}
   :card/text "Target sorcery card in your graveyard gains flashback until end of turn. The flashback cost is equal to the card's mana cost.\nFlashback {3}{R}"

   ;; Cast-time targeting
   :card/targeting [{:target/id :graveyard-sorcery
                     :target/type :object
                     :target/zone :graveyard
                     :target/controller :self
                     :target/criteria {:card/types #{:sorcery}}
                     :target/required true}]

   ;; Effect: grant flashback to target
   :card/effects [{:effect/type :grant-flashback
                   :effect/target-ref :graveyard-sorcery}]

   ;; Recoup's own flashback
   :card/alternate-costs [{:alternate/id :flashback
                           :alternate/zone :graveyard
                           :alternate/mana-cost {:colorless 3 :red 1}
                           :alternate/on-resolve :exile}]})
```

### Grant Flashback Effect

```clojure
;; In effects.cljs
(defmethod execute-effect :grant-flashback
  [db player-id effect object-id]
  (let [;; Get the target from object's stored targets
        obj (q/get-object db object-id)
        target-ref (:effect/target-ref effect)
        target-id (get-target-by-ref obj target-ref)
        target-obj (q/get-object db target-id)
        target-card (:object/card target-obj)
        ;; Build the grant
        game-state (q/get-game-state db)
        current-turn (:game/turn game-state)
        grant {:grant/id (random-uuid)
               :grant/type :alternate-cost
               :grant/source (:object/id obj)
               :grant/expires {:expires/turn current-turn
                               :expires/phase :cleanup}
               :grant/data {:alternate/id :granted-flashback
                            :alternate/zone :graveyard
                            :alternate/mana-cost (:card/mana-cost target-card)
                            :alternate/on-resolve :exile}}]
    (grants/add-grant db target-id grant)))
```

---

## 5. Implementation Order

### Phase 1: Grant System
1. Add `:object/grants` to schema
2. Create `engine/grants.cljs` with core functions
3. Modify `rules/get-casting-modes` to include granted alternates
4. Write tests for grant add/remove/query

### Phase 2: Cleanup System
5. Add expiration logic to grants
6. Register cleanup trigger in turn-based
7. Implement expire-grants effect handler
8. Write tests for expiration

### Phase 3: Targeting System Foundation
9. Update `:object/targets` schema to map format
10. Create `engine/targeting.cljs` with core functions:
    - `get-targeting-requirements`
    - `find-valid-targets`
    - `has-valid-targets?`
    - `target-still-legal?`
    - `all-targets-legal?`
11. Write tests for targeting validation

### Phase 4: Migrate Deep Analysis (Stepping Stone)
12. Add `:card/targeting` to Deep Analysis card definition
13. Modify casting flow to prompt for targets before stack
14. Update resolution to use stored targets
15. Add fizzle logic for invalid targets
16. Update Deep Analysis tests
17. Verify existing behavior preserved

### Phase 5: Migrate Cephalid Coliseum (Stepping Stone)
18. Add `:ability/targeting` to Cephalid Coliseum threshold ability
19. Extend targeting system to work with abilities
20. Update ability activation flow for cast-time targeting
21. Update Cephalid Coliseum tests
22. Verify existing behavior preserved

### Phase 6: Grant Flashback Effect
23. Implement `:grant-flashback` effect type
24. Effect reads target's mana cost and creates grant
25. Write tests for effect

### Phase 7: Recoup Card
26. Create Recoup card definition with targeting + flashback
27. Add to iggy-pop cards
28. Write comprehensive tests covering:
    - Basic flow (cast, grant, use flashback)
    - Targeting fizzle
    - Flashback Recoup
    - End of turn cleanup
    - Multiple grants across turns

---

## Design Decisions

1. **Target storage format:** `:object/targets` stores a map of target-ref → target-id to support multiple target slots (e.g., a spell that targets a creature AND a player).

2. **Ability targeting:** Abilities use the same `:ability/targeting` pattern as cards. The targeting system is unified.

3. **Retargeting:** Deferred to future work. Not needed for current cards.

4. **Migration scope:** Deep Analysis and Cephalid Coliseum will be migrated to the new targeting system as stepping stones before implementing Recoup.

---

## Test Scenarios

### Recoup Basic Flow
1. Cast Brain Freeze targeting self, mill Ill-Gotten Gains + Recoup
2. Cast Recoup from hand targeting Ill-Gotten Gains
3. Verify IGG has flashback equal to its mana cost {2BB}
4. Cast IGG from graveyard using granted flashback
5. Verify IGG exiles after resolution

### Targeting Fizzle
1. Cast Recoup targeting a sorcery in graveyard
2. Before Recoup resolves, exile the target (e.g., via Tormod's Crypt)
3. Verify Recoup fizzles (no effect, goes to graveyard)

### Flashback Recoup
1. Mill Recoup to graveyard
2. Cast Recoup via flashback {3}{R}
3. Verify Recoup exiles after resolution
4. Verify target got flashback grant

### End of Turn Cleanup
1. Grant flashback via Recoup
2. Advance to cleanup phase
3. Verify granted flashback is removed
4. Verify card can no longer be cast from graveyard

### Multiple Grants
1. Cast Recoup on IGG turn 1
2. IGG flashback expires at end of turn 1
3. Cast Recoup on IGG turn 2 (new grant)
4. Verify turn 2 grant works independently
