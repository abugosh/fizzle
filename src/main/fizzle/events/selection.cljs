(ns fizzle.events.selection
  "Selection state management for player choices during spell/ability resolution.

  Selection Types (all use :selection/type as discriminator):
  ──────────────────────────────────────────────────────────
  1. Tutor (:type :tutor)
     zone, select-count, exact?, shuffle?, allow-fail-to-find?, candidates, target-zone

  2. Discard (:type :discard)
     zone :hand, select-count, player-id

  3. Cleanup Discard (:type :cleanup-discard)
     zone :hand, select-count, player-id

  4. Graveyard Return (:type :graveyard-return)
     zone :graveyard, select-count, min-count, target-zone, candidate-ids

  5. Scry (:type :scry)
     cards, top-pile, bottom-pile

  6. Pile Choice (:type :pile-choice)
     candidates, hand-count, selected, bottom-pile

  7. Peek-and-Select (:type :peek-and-select)
     select-count, candidates

  8. Exile Cards Cost (:type :exile-cards-cost)
     select-count, candidate-ids

  9. X Mana Cost (:type :x-mana-cost)
     max-x, selected-x

  10. Targeting (:type :cast-time-targeting / :ability-targeting)
      target-requirement, valid-targets, selected, select-count

  11. Player Target (:type :player-target)
      selected, select-count, valid-targets, target-effect

  Common keys (all namespaced :selection/*):
    selected, spell-id, remaining-effects, player-id, source-type, stack-item-eid"
  (:require
    [clojure.set :as set]
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.zones :as zones]
    [re-frame.core :as rf]))


;; =====================================================
;; Stack-Item Cleanup Helper
;; =====================================================

(defn- remove-spell-stack-item
  "Remove the stack-item for a spell after resolution.
   Looks up the stack-item by the spell's object EID and removes it.
   Returns updated db. Safe to call when no stack-item exists."
  [game-db spell-id]
  (let [obj-eid (d/q '[:find ?e .
                       :in $ ?oid
                       :where [?e :object/id ?oid]]
                     game-db spell-id)]
    (if-let [si (when obj-eid (stack/get-stack-item-by-object-ref game-db obj-eid))]
      (stack/remove-stack-item game-db (:db/id si))
      game-db)))


;; =====================================================
;; Selection Detection Helpers
;; =====================================================

(defn has-selection-effect?
  "Check if any effect in the list requires player selection."
  [effects]
  (some #(or (= :player (:effect/selection %))
             (= :tutor (:effect/type %))
             (and (= :scry (:effect/type %))
                  (pos? (or (:effect/amount %) 0))))
        effects))


(defn find-selection-effect-index
  "Find the index of the first effect that requires player selection.
   This includes:
   - :discard with :selection :player - player chooses cards to discard
   - :tutor - player searches library for matching card
   - :scry with amount > 0 - player arranges top N cards
   - :effect/target :any-player - player chooses a target player
   - :peek-and-select - player chooses from top N cards (Flash of Insight)
   - :return-from-graveyard with :selection :player - player chooses cards to return
   Returns nil if no selection effect found."
  [effects]
  (first (keep-indexed (fn [i effect]
                         (when (or (= :player (:effect/selection effect))
                                   (= :tutor (:effect/type effect))
                                   (and (= :scry (:effect/type effect))
                                        (pos? (or (:effect/amount effect) 0)))
                                   (= :any-player (:effect/target effect))
                                   (= :peek-and-select (:effect/type effect))
                                   (= :return-from-graveyard (:effect/type effect)))
                           i))
                       effects)))


(defn- split-effects-around-index
  "Split effects list around the effect at idx.
   Returns [effects-before target-effect effects-after]."
  [effects idx]
  (let [v (vec effects)]
    [(subvec v 0 idx)
     (nth v idx)
     (subvec v (inc idx))]))


;; =====================================================
;; X Cost Selection Helpers
;; =====================================================

(defn has-exile-cards-x-cost?
  "Check if mode has an :exile-cards additional cost with :cost/count :x"
  [mode]
  (some (fn [cost]
          (and (= :exile-cards (:cost/type cost))
               (= :x (:cost/count cost))))
        (:mode/additional-costs mode)))


(defn get-exile-cards-x-cost
  "Get the :exile-cards additional cost with :cost/count :x from a mode"
  [mode]
  (first (filter (fn [cost]
                   (and (= :exile-cards (:cost/type cost))
                        (= :x (:cost/count cost))))
                 (:mode/additional-costs mode))))


(defn has-mana-x-cost?
  "Check if mode has :x true in its mana cost"
  [mode]
  (true? (get-in mode [:mode/mana-cost :x])))


;; =====================================================
;; Selection Builders
;; =====================================================

(defn build-tutor-selection
  "Build pending selection state for a tutor effect.
   Pre-filters library to find valid candidates.

   Supports multi-select via :effect/select-count (default 1 for backwards compat).
   Actual selection count = min(select-count, candidate-count).
   When select-count > 1, :selection/exact? is true (must select exactly that many)."
  [game-db player-id object-id tutor-effect effects-after]
  (let [criteria (:effect/criteria tutor-effect)
        target-zone (or (:effect/target-zone tutor-effect) :hand)
        matching-objs (queries/query-library-by-criteria game-db player-id criteria)
        candidate-ids (set (map :object/id matching-objs))
        ;; Multi-select support: default to 1 for backwards compat
        effect-select-count (max 1 (or (:effect/select-count tutor-effect) 1))
        ;; Actual count is min of requested and available candidates
        actual-select-count (min effect-select-count (count candidate-ids))]
    {:selection/zone :library
     :selection/select-count actual-select-count
     :selection/exact? true  ; Must select exactly this many (or fail-to-find)
     :selection/player-id player-id
     :selection/selected #{}
     :selection/spell-id object-id
     :selection/remaining-effects effects-after
     :selection/type :tutor
     :selection/target-zone target-zone
     :selection/allow-fail-to-find? true  ; Always allow fail-to-find (anti-pattern: NO auto-select)
     :selection/candidates candidate-ids
     :selection/shuffle? (get tutor-effect :effect/shuffle? true)
     :selection/enters-tapped (:effect/enters-tapped tutor-effect)
     :selection/pile-choice (:effect/pile-choice tutor-effect)}))


(defn build-pile-choice-selection
  "Build pending selection state for pile choice (Intuition-style).
   After multi-select tutor, player chooses which cards go to hand vs graveyard.

   Arguments:
     card-ids - Set of object-ids selected from tutor
     pile-choice - Config map with :hand (count for hand) and :graveyard :rest
     player-id - Player making the choice
     spell-id - The spell that triggered this selection
     remaining-effects - Effects to execute after pile choice

   Returns selection state with:
     :selection/type :pile-choice
     :selection/candidates - Set of card IDs to distribute
     :selection/hand-count - How many go to hand
     :selection/selected - Pre-selected cards (auto-select if only 1 card)
     :selection/allow-random - true (Random button available)"
  [card-ids pile-choice player-id spell-id remaining-effects]
  (let [hand-count (:hand pile-choice)
        candidates (set card-ids)
        ;; Auto-select if only 1 card (per epic open question: still show UI)
        auto-selected (if (= 1 (count candidates))
                        candidates
                        #{})]
    {:selection/type :pile-choice
     :selection/candidates candidates
     :selection/hand-count hand-count
     :selection/selected auto-selected
     :selection/player-id player-id
     :selection/spell-id spell-id
     :selection/remaining-effects remaining-effects
     :selection/allow-random true}))


(defn select-random-pile-choice
  "Randomly select hand-count cards from pile-choice candidates.
   Pure function: (app-db) -> app-db

   Updates :game/pending-selection with random selection.
   Does NOT confirm - user can review and change before confirming."
  [app-db]
  (let [selection (:game/pending-selection app-db)
        candidates (vec (:selection/candidates selection))
        hand-count (:selection/hand-count selection)
        random-selected (set (take hand-count (shuffle candidates)))]
    (assoc-in app-db [:game/pending-selection :selection/selected] random-selected)))


(defn build-scry-selection
  "Build pending selection state for a scry effect.
   Reveals top N cards from library for player to arrange.
   Returns nil if library is empty or amount <= 0 (no selection needed)."
  [game-db player-id object-id scry-effect effects-after]
  (let [amount (or (:effect/amount scry-effect) 0)]
    (when (pos? amount)
      (let [library-cards (queries/get-top-n-library game-db player-id amount)]
        (when (seq library-cards)
          {:selection/type :scry
           :selection/player-id player-id
           :selection/cards (vec library-cards)
           :selection/top-pile []
           :selection/bottom-pile []
           :selection/spell-id object-id
           :selection/remaining-effects (vec effects-after)})))))


(defn build-peek-selection
  "Build pending selection state for a peek-and-select effect.
   Reveals top N cards from library for player to select some for hand.
   Returns nil if library is empty or count <= 0 (no selection needed).

   Arguments:
     game-db - Datascript game database
     player-id - Player performing the peek
     object-id - Spell-id for cleanup after selection confirmed
     effect - The :peek-and-select effect map with:
       :effect/count - Number of cards to peek (default 0), or :x to use spell's X value
       :effect/select-count - Number to select for hand (default 1)
       :effect/selected-zone - Zone for selected cards (default :hand)
       :effect/remainder-zone - Zone for non-selected (default :bottom-of-library)
       :effect/shuffle-remainder? - Whether to shuffle remainder (default false)
     effects-after - Remaining effects to execute after selection

   Anti-pattern: NO auto-select even with 1 card. Player must choose or fail-to-find."
  [game-db player-id object-id effect effects-after]
  (let [effect-count (:effect/count effect)
        ;; Resolve :x from spell's stored X value
        peek-count (if (= effect-count :x)
                     (or (:object/x-value (queries/get-object game-db object-id)) 0)
                     (or effect-count 0))]
    (when (pos? peek-count)
      (let [library-cards (queries/get-top-n-library game-db player-id peek-count)]
        (when (seq library-cards)
          (let [candidate-ids (set library-cards)
                select-count (or (:effect/select-count effect) 1)
                ;; Actual count is min of requested and available
                actual-select-count (min select-count (count candidate-ids))]
            {:selection/zone :peek  ; Distinct from :library (tutor)
             :selection/type :peek-and-select
             :selection/candidates candidate-ids
             :selection/select-count actual-select-count
             :selection/exact? false  ; Can select fewer (fail-to-find)
             :selection/allow-fail-to-find? true
             :selection/selected #{}
             :selection/player-id player-id
             :selection/spell-id object-id
             :selection/remaining-effects (vec effects-after)
             :selection/selected-zone (or (:effect/selected-zone effect) :hand)
             :selection/remainder-zone (or (:effect/remainder-zone effect) :bottom-of-library)
             :selection/shuffle-remainder? (:effect/shuffle-remainder? effect)}))))))


(defn build-exile-cards-selection
  "Build pending selection for exile-cards additional cost with :x count.
   Player selects which cards to exile (1 or more), and the count becomes X.

   Arguments:
     game-db - Datascript game database
     player-id - Player casting the spell
     object-id - Spell being cast
     mode - Casting mode with :exile-cards cost
     exile-cost - The :exile-cards cost map

   Returns selection state for choosing cards to exile."
  [game-db player-id object-id mode exile-cost]
  (let [zone (:cost/zone exile-cost)
        criteria (:cost/criteria exile-cost)
        ;; Get available cards matching criteria
        available (queries/query-zone-by-criteria game-db player-id zone criteria)
        ;; Exclude the spell being cast (can't exile itself)
        candidates (filterv #(not= object-id (:object/id %)) available)
        candidate-ids (set (map :object/id candidates))]
    (when (seq candidate-ids)
      {:selection/zone zone
       :selection/type :exile-cards-cost
       :selection/candidates candidate-ids
       :selection/select-count 1  ; Minimum 1, but can select more
       :selection/exact? false    ; Can select any number >= 1
       :selection/allow-fail-to-find? false  ; Must select at least 1
       :selection/selected #{}
       :selection/player-id player-id
       :selection/spell-id object-id
       :selection/mode mode       ; Store mode for casting after selection
       :selection/exile-cost exile-cost})))


(defn build-x-mana-selection
  "Build pending selection for X mana cost.
   Player selects how much to pay for X.

   Arguments:
     game-db - Datascript game database
     player-id - Player casting the spell
     object-id - Spell being cast
     mode - Casting mode with X in mana cost

   Returns selection state for choosing X value."
  [game-db player-id object-id mode]
  (let [pool (queries/get-mana-pool game-db player-id)
        mana-cost (:mode/mana-cost mode)
        ;; Fixed costs (non-X portion)
        fixed-colorless (get mana-cost :colorless 0)
        fixed-colored (dissoc mana-cost :colorless :x)
        ;; Calculate remaining mana after paying fixed costs
        pool-after-colored (merge-with - pool fixed-colored)
        total-remaining (max 0 (- (reduce + 0 (vals pool-after-colored)) fixed-colorless))
        ;; Max X is what's left after paying fixed costs
        max-x total-remaining]
    {:selection/zone :mana-pool
     :selection/type :x-mana-cost
     :selection/player-id player-id
     :selection/spell-id object-id
     :selection/mode mode
     :selection/max-x max-x
     :selection/selected-x 0}))  ; Default to 0, player increments


(defn- build-discard-selection
  "Build pending selection state for a discard effect."
  [player-id object-id discard-effect effects-after]
  {:selection/zone :hand
   :selection/select-count (:effect/count discard-effect)
   :selection/player-id player-id
   :selection/selected #{}
   :selection/spell-id object-id
   :selection/remaining-effects effects-after
   :selection/type :discard})


(defn build-graveyard-selection
  "Build pending selection state for returning cards from graveyard.
   Unlike tutor (hidden zone), graveyard is public - no fail-to-find option.
   Player can select 0 to :effect/count cards.

   Arguments:
     game-db - Datascript game database
     player-id - Caster's player-id (used to resolve :self target)
     object-id - Spell-id for cleanup after selection confirmed
     effect - The :return-from-graveyard effect map with:
       :effect/target - :self, :opponent, or player-id (defaults to caster)
       :effect/count - Max cards to return (0 to N selection)
       :effect/selection - Should be :player for this to be called
     effects-after - Vector of effects to execute after selection

   Returns selection state map for UI to render graveyard selection."
  [game-db player-id object-id effect effects-after]
  (let [target (get effect :effect/target player-id)
        target-player (cond
                        (= target :opponent) (queries/get-opponent-id game-db player-id)
                        (= target :self) player-id
                        :else target)
        gy-cards (or (queries/get-objects-in-zone game-db target-player :graveyard) [])
        candidate-ids (set (map :object/id gy-cards))]
    {:selection/type :graveyard-return
     :selection/zone :graveyard
     :selection/select-count (:effect/count effect)
     :selection/min-count 0  ; Can select 0 cards (up to N)
     :selection/player-id target-player
     :selection/selected #{}
     :selection/spell-id object-id
     :selection/remaining-effects effects-after
     :selection/candidate-ids candidate-ids}))


(defn- build-player-target-selection
  "Build pending selection state for a player-targeted effect.
   Used when :effect/target is :any-player - player must choose target."
  [player-id object-id target-effect effects-after]
  {:selection/type :player-target
   :selection/player-id player-id
   :selection/selected #{}
   :selection/select-count 1
   :selection/valid-targets #{:player-1 :opponent}
   :selection/spell-id object-id
   :selection/target-effect target-effect  ; The effect needing a target
   :selection/remaining-effects effects-after})


;; =====================================================
;; Execution Functions
;; =====================================================

(defn execute-tutor-selection
  "Execute a tutor selection - move selected cards to target zone and shuffle.
   Pure function: (db, selection) -> db

   Arguments:
     game-db - Datascript game database (not app-db)
     selection - Selection state map with:
       :selection/selected - Set of object-ids (1 or more for multi-select tutor)
       :selection/target-zone - Zone to move cards to (:hand for Merchant Scroll, :battlefield for fetchlands)
       :selection/player-id - Who is tutoring
       :selection/shuffle? - Whether to shuffle after (default true)
       :selection/enters-tapped - If true and target-zone is :battlefield, enters tapped

   Handles:
     - Empty selection (fail-to-find): Just shuffles library
     - Cards selected: Moves ALL to target zone, sets tapped if needed, then shuffles ONCE

   CRITICAL: Move ALL cards BEFORE shuffle (anti-pattern: NO shuffling between moves)"
  [game-db selection]
  (let [selected (:selection/selected selection)
        target-zone (:selection/target-zone selection)
        player-id (:selection/player-id selection)
        should-shuffle? (get selection :selection/shuffle? true)
        enters-tapped? (and (= target-zone :battlefield)
                            (:selection/enters-tapped selection))]
    (if (empty? selected)
      ;; Fail-to-find: just shuffle
      (if should-shuffle?
        (zones/shuffle-library game-db player-id)
        game-db)
      ;; Cards found: move ALL to target zone, set tapped if needed, then shuffle ONCE
      (let [;; Move all selected cards to target zone
            db-after-moves (reduce (fn [d card-id]
                                     (zones/move-to-zone d card-id target-zone))
                                   game-db
                                   selected)
            ;; If entering battlefield tapped, set tapped state for all moved cards
            db-after-tapped (if enters-tapped?
                              (reduce (fn [d card-id]
                                        (let [obj-eid (d/q '[:find ?e .
                                                             :in $ ?oid
                                                             :where [?e :object/id ?oid]]
                                                           d card-id)]
                                          (d/db-with d [[:db/add obj-eid :object/tapped true]])))
                                      db-after-moves
                                      selected)
                              db-after-moves)]
        ;; Shuffle ONCE after all moves (anti-pattern: NO shuffling between moves)
        (if should-shuffle?
          (zones/shuffle-library db-after-tapped player-id)
          db-after-tapped)))))


(defn execute-peek-selection
  "Execute peek selection - move selected to hand, rest to bottom of library.
   Pure function: (db, selection) -> db

   Arguments:
     game-db - Datascript game database (not app-db)
     selection - Peek selection state with:
       :selection/selected - Set of object-ids going to hand
       :selection/candidates - Set of all object-ids that were peeked
       :selection/selected-zone - Zone for selected cards (typically :hand)
       :selection/remainder-zone - Zone for non-selected (typically :bottom-of-library)
       :selection/shuffle-remainder? - Whether to randomize remainder order
       :selection/player-id - Player performing the peek

   Handles:
     - Empty selection (fail-to-find): All candidates go to bottom
     - Cards selected: Selected go to hand, rest go to bottom
     - Remainder shuffling: If shuffle-remainder? true, randomize bottom order"
  [game-db selection]
  (let [selected (:selection/selected selection)
        candidates (:selection/candidates selection)
        remainder (set/difference candidates selected)
        selected-zone (or (:selection/selected-zone selection) :hand)
        player-id (:selection/player-id selection)
        shuffle-remainder? (:selection/shuffle-remainder? selection)
        ;; Move selected cards to hand
        db-after-selected (reduce (fn [d card-id]
                                    (zones/move-to-zone d card-id selected-zone))
                                  game-db
                                  selected)
        ;; Get max position in library to put remainder at bottom
        ;; Cards in library after removing selected ones
        library-objs (queries/get-objects-in-zone db-after-selected player-id :library)
        max-pos (if (seq library-objs)
                  (apply max (map :object/position library-objs))
                  -1)
        ;; Order remainder cards (shuffle if requested, otherwise keep original order)
        remainder-seq (if shuffle-remainder?
                        (shuffle (seq remainder))
                        (seq remainder))
        ;; Assign new positions to remainder cards starting after max-pos
        db-after-remainder (reduce-kv
                             (fn [d idx card-id]
                               (let [obj-eid (d/q '[:find ?e .
                                                    :in $ ?oid
                                                    :where [?e :object/id ?oid]]
                                                  d card-id)
                                     new-pos (+ max-pos 1 idx)]
                                 (d/db-with d [[:db/add obj-eid :object/position new-pos]])))
                             db-after-selected
                             (vec remainder-seq))]
    db-after-remainder))


(defn execute-pile-choice
  "Execute pile choice - move selected cards to hand, rest to graveyard.
   Pure function: (db, selection) -> db

   Arguments:
     game-db - Datascript game database (not app-db)
     selection - Pile choice selection state with:
       :selection/selected - Set of object-ids going to hand
       :selection/candidates - Set of all object-ids in pile choice

   Note: Does NOT shuffle library. Shuffle happens in confirm-pile-choice-selection
   after all pile choice operations are complete."
  [game-db selection]
  (let [hand-selected (:selection/selected selection)
        all-candidates (:selection/candidates selection)
        graveyard-cards (set/difference all-candidates hand-selected)
        ;; Move selected cards to hand
        db-after-hand (reduce (fn [d card-id]
                                (zones/move-to-zone d card-id :hand))
                              game-db
                              hand-selected)
        ;; Move remaining to graveyard
        db-after-graveyard (reduce (fn [d card-id]
                                     (zones/move-to-zone d card-id :graveyard))
                                   db-after-hand
                                   graveyard-cards)]
    db-after-graveyard))


;; =====================================================
;; Spell/Ability Resolution with Selection
;; =====================================================

(defn- resolve-effect-with-stored-target
  "Resolve an effect using stored targets from :object/targets.
   Returns the effect with :any-player replaced by the actual stored target."
  [effect stored-targets]
  (if (= :any-player (:effect/target effect))
    ;; Look up the stored player target and resolve
    (if-let [stored-player (:player stored-targets)]
      (assoc effect :effect/target stored-player)
      effect)
    effect))


(defn resolve-spell-with-selection
  "Resolve a spell, pausing if a selection effect is encountered.

   Returns a map with:
     :db - The updated game-db
     :pending-selection - Selection state if paused for selection, nil otherwise

   When a selection effect is encountered:
   - Effects before the selection are executed
   - The selection effect creates pending selection state
   - Effects after the selection are stored for later execution

   If :object/targets contains stored targets from cast-time targeting:
   - Uses stored targets instead of prompting
   - Resolves effects normally using the pre-selected targets

   Handles two types of selection:
   - :discard with :selection :player - player chooses cards to discard
   - :tutor - player searches library for matching card (with fail-to-find)"
  [game-db player-id object-id]
  (let [obj (queries/get-object game-db object-id)]
    (if (not= :stack (:object/zone obj))
      {:db game-db :pending-selection nil}  ; No-op if spell not on stack
      (let [card (:object/card obj)
            stored-targets (:object/targets obj)
            effects-list (or (rules/get-active-effects game-db player-id card object-id) [])
            selection-idx (find-selection-effect-index effects-list)
            ;; Check if stored targets can satisfy the selection effect
            has-stored-player-target (and stored-targets
                                          (:player stored-targets)
                                          selection-idx
                                          (= :any-player (:effect/target (nth effects-list selection-idx))))
            ;; Check if spell has object targets (for fizzle checking)
            has-stored-object-targets (and stored-targets
                                           (seq stored-targets)
                                           (not (:player stored-targets)))]
        (cond
          ;; Cast-time targeting with player target: check fizzle, use stored targets
          has-stored-player-target
          (let [cast-mode (:object/cast-mode obj)
                destination (or (:mode/on-resolve cast-mode) :graveyard)]
            ;; Fizzle check: if any target is no longer legal, skip effects
            (if (targeting/all-targets-legal? game-db object-id)
              ;; Targets legal - resolve normally with stored targets
              (let [resolved-effects (mapv #(resolve-effect-with-stored-target % stored-targets) effects-list)
                    db-after-effects (reduce (fn [d effect]
                                               (effects/execute-effect d player-id effect))
                                             game-db
                                             resolved-effects)
                    db-final (zones/move-to-zone db-after-effects object-id destination)]
                {:db db-final
                 :pending-selection nil})
              ;; Fizzle: targets invalid, skip effects, move spell off stack
              {:db (zones/move-to-zone game-db object-id destination)
               :pending-selection nil}))

          ;; Cast-time targeting with object target (e.g., Recoup): check fizzle, resolve with object-id
          has-stored-object-targets
          (let [cast-mode (:object/cast-mode obj)
                destination (or (:mode/on-resolve cast-mode) :graveyard)]
            ;; Fizzle check: if any target is no longer legal, skip effects
            (if (targeting/all-targets-legal? game-db object-id)
              ;; Targets legal - resolve with object-id for effects that need stored targets
              {:db (rules/resolve-spell game-db player-id object-id)
               :pending-selection nil}
              ;; Fizzle: targets invalid, skip effects, move spell off stack
              {:db (zones/move-to-zone game-db object-id destination)
               :pending-selection nil}))

          ;; No selection effect and no stored targets - resolve normally
          (nil? selection-idx)
          {:db (rules/resolve-spell game-db player-id object-id)
           :pending-selection nil}

          ;; Has selection effect without stored targets - pause for selection
          :else
          (let [[effects-before selection-effect effects-after]
                (split-effects-around-index effects-list selection-idx)
                effect-type (:effect/type selection-effect)
                ;; Execute effects before selection (pass object-id for effects like :exile-self)
                db-after-before (reduce (fn [d effect]
                                          (effects/execute-effect d player-id effect object-id))
                                        game-db
                                        effects-before)
                ;; Build selection state based on effect type or target type
                pending-selection (cond
                                    ;; Player targeting (e.g., Deep Analysis "target player draws")
                                    (= :any-player (:effect/target selection-effect))
                                    (build-player-target-selection player-id object-id
                                                                   selection-effect effects-after)

                                    ;; Tutor effect
                                    (= effect-type :tutor)
                                    (build-tutor-selection db-after-before player-id object-id
                                                           selection-effect effects-after)

                                    ;; Discard effect
                                    (= effect-type :discard)
                                    (build-discard-selection player-id object-id
                                                             selection-effect effects-after)

                                    ;; Scry effect
                                    (= effect-type :scry)
                                    (build-scry-selection db-after-before player-id object-id
                                                          selection-effect effects-after)

                                    ;; Return from graveyard effect
                                    (= effect-type :return-from-graveyard)
                                    (build-graveyard-selection db-after-before player-id object-id
                                                               selection-effect effects-after)

                                    ;; Peek-and-select effect (Flash of Insight)
                                    (= effect-type :peek-and-select)
                                    (build-peek-selection db-after-before player-id object-id
                                                          selection-effect effects-after)

                                    ;; Default for unknown selection types
                                    :else
                                    {:selection/zone :hand
                                     :selection/select-count (:effect/count selection-effect)
                                     :selection/player-id player-id
                                     :selection/selected #{}
                                     :selection/spell-id object-id
                                     :selection/remaining-effects effects-after
                                     :selection/type effect-type})]
            {:db db-after-before
             :pending-selection pending-selection}))))))


;; =====================================================
;; Source Cleanup Helper
;; =====================================================

(defn- cleanup-selection-source
  "Clean up the source of a resolved selection.
   Handles 2 source types:
   - :stack-item → remove the stack-item entity
   - nil/spell → move spell to destination zone + remove stack-item"
  [game-db selection]
  (let [source-type (:selection/source-type selection)]
    (if (= source-type :stack-item)
      (let [si-eid (:selection/stack-item-eid selection)]
        (stack/remove-stack-item game-db si-eid))
      (let [spell-id (:selection/spell-id selection)
            spell-obj (queries/get-object game-db spell-id)
            current-zone (:object/zone spell-obj)
            db-after-move (if (= current-zone :stack)
                            (let [cast-mode (:object/cast-mode spell-obj)
                                  destination (or (:mode/on-resolve cast-mode) :graveyard)]
                              (zones/move-to-zone game-db spell-id destination))
                            game-db)]
        (remove-spell-stack-item db-after-move spell-id)))))


;; =====================================================
;; Stack-Item Ability Resolution
;; =====================================================

(defn resolve-stack-item-ability-with-selection
  "Resolve a stack-item for an activated ability, pausing if a selection effect is encountered.
   Uses resolve-effect-target for all target resolution.

   Arguments:
     game-db - Datascript database
     stack-item - Stack-item entity map with :stack-item/* attributes

   Returns {:db db :pending-selection selection-or-nil}"
  [game-db stack-item]
  (let [controller (:stack-item/controller stack-item)
        source-id (:stack-item/source stack-item)
        stack-item-eid (:db/id stack-item)
        effects-list (or (:stack-item/effects stack-item) [])
        stored-targets (:stack-item/targets stack-item)
        selection-idx (find-selection-effect-index effects-list)
        stored-player (:player stored-targets)
        has-stored-player-target (and stored-player
                                      selection-idx
                                      (= :any-player (:effect/target (nth effects-list selection-idx))))]
    (cond
      ;; Stored targets for player targeting
      has-stored-player-target
      (let [[effects-before player-target-effect effects-after]
            (split-effects-around-index effects-list selection-idx)
            resolved-player-effect (stack/resolve-effect-target player-target-effect source-id controller stored-targets)
            db-after-before (reduce (fn [d effect]
                                      (effects/execute-effect d controller
                                                              (stack/resolve-effect-target effect source-id controller nil)))
                                    game-db effects-before)
            db-after-player-effect (effects/execute-effect db-after-before controller resolved-player-effect)
            next-selection-idx (find-selection-effect-index effects-after)]
        (if next-selection-idx
          (let [[effects-before-next next-selection-effect effects-after-next]
                (split-effects-around-index effects-after next-selection-idx)
                db-after-before-next (reduce (fn [d effect]
                                               (effects/execute-effect d controller effect))
                                             db-after-player-effect effects-before-next)
                pending-selection (when (= :player (:effect/selection next-selection-effect))
                                    {:selection/type :discard
                                     :selection/player-id stored-player
                                     :selection/select-count (:effect/count next-selection-effect)
                                     :selection/selected #{}
                                     :selection/stack-item-eid stack-item-eid
                                     :selection/source-type :stack-item
                                     :selection/remaining-effects effects-after-next})]
            {:db db-after-before-next :pending-selection pending-selection})
          (let [db-after-all (reduce (fn [d effect]
                                       (effects/execute-effect d controller effect))
                                     db-after-player-effect effects-after)]
            {:db db-after-all :pending-selection nil})))

      ;; No selection effect
      (nil? selection-idx)
      (let [db-after-effects (reduce
                               (fn [d effect]
                                 (let [resolved (stack/resolve-effect-target effect source-id controller stored-targets)]
                                   (effects/execute-effect d controller resolved source-id)))
                               game-db effects-list)]
        {:db db-after-effects :pending-selection nil})

      ;; Has selection effect without stored targets
      :else
      (let [[effects-before selection-effect effects-after]
            (split-effects-around-index effects-list selection-idx)
            db-after-before (reduce (fn [d effect]
                                      (effects/execute-effect d controller
                                                              (stack/resolve-effect-target effect source-id controller nil)))
                                    game-db effects-before)
            pending-selection (cond
                                (= :any-player (:effect/target selection-effect))
                                (-> (build-player-target-selection controller stack-item-eid
                                                                   selection-effect effects-after)
                                    (dissoc :selection/spell-id)
                                    (assoc :selection/stack-item-eid stack-item-eid
                                           :selection/source-type :stack-item))

                                (= :tutor (:effect/type selection-effect))
                                (-> (build-tutor-selection db-after-before controller stack-item-eid
                                                           selection-effect effects-after)
                                    (dissoc :selection/spell-id)
                                    (assoc :selection/stack-item-eid stack-item-eid
                                           :selection/source-type :stack-item))

                                (= :player (:effect/selection selection-effect))
                                {:selection/type :discard
                                 :selection/player-id controller
                                 :selection/select-count (:effect/count selection-effect)
                                 :selection/selected #{}
                                 :selection/stack-item-eid stack-item-eid
                                 :selection/source-type :stack-item
                                 :selection/remaining-effects effects-after}

                                :else nil)]
        {:db db-after-before :pending-selection pending-selection}))))


;; =====================================================
;; Scry Helpers
;; =====================================================

(defn- reorder-library-for-scry
  "Reorder library after scry confirmation.
   top-pile cards go to positions 0, 1, 2... (click order)
   bottom-pile cards go to last positions (click order)
   Unassigned cards from selection stay at original relative positions.
   Cards not in scry selection are unaffected."
  [game-db player-id selection]
  (let [scry-card-ids (set (:selection/cards selection))
        top-pile (vec (or (:selection/top-pile selection) []))
        bottom-pile (vec (or (:selection/bottom-pile selection) []))
        assigned-ids (into (set top-pile) bottom-pile)
        ;; Get all library objects sorted by current position
        library-objs (->> (queries/get-objects-in-zone game-db player-id :library)
                          (sort-by :object/position))
        ;; Separate scry cards from non-scry cards
        non-scry-objs (remove #(scry-card-ids (:object/id %)) library-objs)
        ;; Cards in selection but not assigned to either pile (keep at top)
        unassigned (filterv #(and (scry-card-ids %)
                                  (not (assigned-ids %)))
                            (map :object/id library-objs))
        ;; Build new position order:
        ;; 1. Top pile (click order)
        ;; 2. Unassigned scry cards (original relative order)
        ;; 3. Non-scry cards (original positions preserved)
        ;; 4. Bottom pile (click order)
        new-order (vec (concat top-pile
                               unassigned
                               (map :object/id non-scry-objs)
                               bottom-pile))
        ;; Build position update transactions
        position-txs (map-indexed
                       (fn [idx obj-id]
                         (let [obj-eid (d/q '[:find ?e .
                                              :in $ ?oid
                                              :where [?e :object/id ?oid]]
                                            game-db obj-id)]
                           (when obj-eid
                             [:db/add obj-eid :object/position idx])))
                       new-order)]
    (d/db-with game-db (filterv some? position-txs))))


(defn confirm-scry-selection
  "Handle scry selection confirmation.
   Reorders library, executes remaining effects, moves spell to graveyard.
   Exported for testing."
  [app-db]
  (let [selection (:game/pending-selection app-db)
        spell-id (:selection/spell-id selection)
        remaining-effects (:selection/remaining-effects selection)
        player-id (:selection/player-id selection)
        game-db (:game/db app-db)]
    (if (= :scry (:selection/type selection))
      (let [;; Reorder library based on pile assignments
            db-after-reorder (reorder-library-for-scry game-db player-id selection)
            ;; Execute remaining effects (e.g., draw for Opt)
            db-after-remaining (reduce (fn [d effect]
                                         (effects/execute-effect d player-id effect))
                                       db-after-reorder
                                       (or remaining-effects []))
            ;; Move spell to graveyard
            db-after-move (zones/move-to-zone db-after-remaining spell-id :graveyard)
            ;; Remove stack-item for spell
            db-final (remove-spell-stack-item db-after-move spell-id)]
        (-> app-db
            (assoc :game/db db-final)
            (dissoc :game/pending-selection)))
      ;; Not a scry selection - do nothing
      app-db)))


;; =====================================================
;; Cast-Time Targeting
;; =====================================================

(defn build-cast-time-target-selection
  "Build pending selection state for cast-time targeting.
   Used when a spell has :card/targeting requirements.

   Arguments:
     game-db - Datascript database
     player-id - Casting player
     object-id - Spell being cast
     mode - Casting mode being used
     target-req - First targeting requirement (player target for now)

   Returns selection state map."
  [game-db player-id object-id mode target-req]
  (let [valid-targets (targeting/find-valid-targets game-db player-id target-req)]
    {:selection/type :cast-time-targeting
     :selection/player-id player-id
     :selection/object-id object-id
     :selection/mode mode
     :selection/target-requirement target-req
     :selection/valid-targets valid-targets
     :selection/selected #{}
     :selection/select-count 1}))


(defn cast-spell-with-targeting
  "Cast a spell, pausing for target selection if needed.

   For spells with :card/targeting:
   - Returns {:db db :pending-target-selection selection-state}
   - Spell does NOT go to stack yet
   - After target confirmed, call confirm-cast-time-target to complete

   For spells without targeting:
   - Returns {:db db :pending-target-selection nil}
   - Spell immediately goes to stack via rules/cast-spell

   Arguments:
     game-db - Datascript database
     player-id - Casting player
     object-id - Object to cast"
  [game-db player-id object-id]
  (let [obj (queries/get-object game-db object-id)
        card (:object/card obj)
        targeting-reqs (targeting/get-targeting-requirements card)
        modes (rules/get-casting-modes game-db player-id object-id)
        ;; Pick best mode (primary if available, else first)
        primary (first (filter #(= :primary (:mode/id %)) modes))
        mode (or primary (first modes))]
    (if (and (seq targeting-reqs)
             (rules/can-cast-mode? game-db player-id object-id mode))
      ;; Has targeting - pause for target selection
      (let [first-req (first targeting-reqs)
            selection (build-cast-time-target-selection game-db player-id object-id mode first-req)]
        {:db game-db
         :pending-target-selection selection})
      ;; No targeting - cast normally
      {:db (if (rules/can-cast? game-db player-id object-id)
             (rules/cast-spell game-db player-id object-id)
             game-db)
       :pending-target-selection nil})))


(defn confirm-cast-time-target
  "Complete casting a spell after target selection.

   1. Pays mana and additional costs
   2. Moves spell to stack
   3. Stores the selected target on the object as :object/targets

   Arguments:
     game-db - Datascript database
     selection - Cast-time target selection state with :selection/selected set

   Returns updated db with spell on stack and target stored."
  [game-db selection]
  (let [player-id (:selection/player-id selection)
        object-id (:selection/object-id selection)
        mode (:selection/mode selection)
        target-req (:selection/target-requirement selection)
        selected-target (first (:selection/selected selection))
        target-id (:target/id target-req)]
    (if selected-target
      ;; Cast spell and store target
      (let [;; Cast via rules/cast-spell-mode (pays costs, moves to stack)
            db-after-cast (rules/cast-spell-mode game-db player-id object-id mode)
            ;; Store the target on the object
            obj-eid (d/q '[:find ?e .
                           :in $ ?oid
                           :where [?e :object/id ?oid]]
                         db-after-cast object-id)
            db-with-target (d/db-with db-after-cast
                                      [[:db/add obj-eid :object/targets {target-id selected-target}]])]
        db-with-target)
      ;; No target selected - return unchanged
      game-db)))


;; =====================================================
;; Confirm Selection Multimethod
;; =====================================================

(defmulti execute-confirmed-selection
  "Execute the type-specific logic for a confirmed selection.
   Dispatches on :selection/type.

   Arguments:
     game-db - Datascript database
     selection - Selection state map

   Returns one of:
     {:db game-db} — standard, wrapper handles remaining-effects + cleanup
     {:db game-db :pending-selection next-sel} — chain to next selection
     {:db game-db :finalized? true} — fully handled (pre-cast, ability)"
  (fn [_game-db selection] (:selection/type selection)))


(defmethod execute-confirmed-selection :discard
  [game-db selection]
  (let [selected (:selection/selected selection)]
    {:db (reduce (fn [gdb obj-id]
                   (zones/move-to-zone gdb obj-id :graveyard))
                 game-db
                 selected)}))


(defmethod execute-confirmed-selection :cleanup-discard
  [game-db selection]
  (let [selected (:selection/selected selection)
        db-after-discard (reduce (fn [d obj-id]
                                   (zones/move-to-zone d obj-id :graveyard))
                                 game-db
                                 selected)
        game-state (queries/get-game-state db-after-discard)
        current-turn (:game/turn game-state)
        db-final (grants/expire-grants db-after-discard current-turn :cleanup)]
    {:db db-final :finalized? true}))


(defmethod execute-confirmed-selection :tutor
  [game-db selection]
  (let [selected (:selection/selected selection)
        pile-choice (:selection/pile-choice selection)
        player-id (:selection/player-id selection)
        spell-id (:selection/spell-id selection)
        remaining-effects (:selection/remaining-effects selection)]
    (if (and pile-choice (seq selected))
      ;; Chain to pile-choice phase
      {:db game-db
       :pending-selection (build-pile-choice-selection
                            selected pile-choice player-id spell-id remaining-effects)}
      ;; Normal tutor flow
      {:db (execute-tutor-selection game-db selection)})))


(defmethod execute-confirmed-selection :peek-and-select
  [game-db selection]
  {:db (execute-peek-selection game-db selection)})


(defmethod execute-confirmed-selection :graveyard-return
  [game-db selection]
  (let [selected (:selection/selected selection)]
    {:db (reduce (fn [gdb obj-id]
                   (zones/move-to-zone gdb obj-id :hand))
                 game-db
                 selected)}))


(defmethod execute-confirmed-selection :player-target
  [game-db selection]
  (let [selected-target (first (:selection/selected selection))
        target-effect (:selection/target-effect selection)
        remaining-effects (vec (or (:selection/remaining-effects selection) []))
        player-id (:selection/player-id selection)
        source-type (:selection/source-type selection)
        spell-id (:selection/spell-id selection)
        ;; Replace :any-player with selected target
        resolved-effect (assoc target-effect :effect/target selected-target)
        db-after-effect (effects/execute-effect game-db player-id resolved-effect)
        ;; Check if remaining effects need selection
        next-selection-idx (find-selection-effect-index remaining-effects)]
    (if next-selection-idx
      ;; Chain to next selection (e.g., discard after draw)
      (let [[effects-before-next next-selection-effect effects-after-next]
            (split-effects-around-index remaining-effects next-selection-idx)
            db-after-before (reduce (fn [d effect]
                                      (effects/execute-effect d player-id effect))
                                    db-after-effect
                                    effects-before-next)
            next-selection (when (= :player (:effect/selection next-selection-effect))
                             (cond-> {:selection/type :discard
                                      :selection/player-id selected-target
                                      :selection/select-count (:effect/count next-selection-effect)
                                      :selection/selected #{}
                                      :selection/source-type source-type
                                      :selection/remaining-effects effects-after-next}
                               spell-id (assoc :selection/spell-id spell-id)
                               (:selection/stack-item-eid selection)
                               (assoc :selection/stack-item-eid (:selection/stack-item-eid selection))))]
        {:db db-after-before :pending-selection next-selection})
      ;; No more selections - execute all remaining (wrapper will handle cleanup)
      {:db (reduce (fn [d effect]
                     (effects/execute-effect d player-id effect))
                   db-after-effect
                   remaining-effects)})))


(defmethod execute-confirmed-selection :cast-time-targeting
  [game-db selection]
  {:db (confirm-cast-time-target game-db selection)
   :finalized? true})


(defmethod execute-confirmed-selection :exile-cards-cost
  [game-db selection]
  (let [selected (:selection/selected selection)
        selected-count (count selected)
        player-id (:selection/player-id selection)
        object-id (:selection/spell-id selection)
        mode (:selection/mode selection)
        db-after-exile (reduce (fn [d card-id]
                                 (zones/move-to-zone d card-id :exile))
                               game-db
                               selected)
        obj-eid (d/q '[:find ?e .
                       :in $ ?oid
                       :where [?e :object/id ?oid]]
                     db-after-exile object-id)
        db-with-x (d/db-with db-after-exile
                             [[:db/add obj-eid :object/x-value selected-count]])
        db-after-cast (rules/cast-spell-mode db-with-x player-id object-id mode)]
    {:db db-after-cast :finalized? true :clear-selected-card? true}))


(defmethod execute-confirmed-selection :x-mana-cost
  [game-db selection]
  (let [x-value (:selection/selected-x selection)
        player-id (:selection/player-id selection)
        object-id (:selection/spell-id selection)
        mode (:selection/mode selection)
        obj-eid (d/q '[:find ?e .
                       :in $ ?oid
                       :where [?e :object/id ?oid]]
                     game-db object-id)
        db-with-x (d/db-with game-db
                             [[:db/add obj-eid :object/x-value x-value]])
        resolved-mana-cost (mana/resolve-x-cost (:mode/mana-cost mode) x-value)
        mode-with-resolved-cost (assoc mode :mode/mana-cost resolved-mana-cost)
        db-after-cast (rules/cast-spell-mode db-with-x player-id object-id mode-with-resolved-cost)]
    {:db db-after-cast :finalized? true :clear-selected-card? true}))


(defmethod execute-confirmed-selection :pile-choice
  [game-db selection]
  (let [player-id (:selection/player-id selection)
        spell-id (:selection/spell-id selection)
        db-after-pile (execute-pile-choice game-db selection)
        db-after-shuffle (zones/shuffle-library db-after-pile player-id)
        remaining-effects (:selection/remaining-effects selection)
        db-after-effects (reduce (fn [d effect]
                                   (effects/execute-effect d player-id effect))
                                 db-after-shuffle
                                 (or remaining-effects []))
        spell-obj (queries/get-object db-after-effects spell-id)
        current-zone (:object/zone spell-obj)
        db-after-move (if (= current-zone :stack)
                        (let [cast-mode (:object/cast-mode spell-obj)
                              destination (or (:mode/on-resolve cast-mode) :graveyard)]
                          (zones/move-to-zone db-after-effects spell-id destination))
                        db-after-effects)
        db-final (remove-spell-stack-item db-after-move spell-id)]
    {:db db-final :finalized? true}))


(defmethod execute-confirmed-selection :scry
  [game-db selection]
  (let [spell-id (:selection/spell-id selection)
        remaining-effects (:selection/remaining-effects selection)
        player-id (:selection/player-id selection)
        db-after-reorder (reorder-library-for-scry game-db player-id selection)
        db-after-remaining (reduce (fn [d effect]
                                     (effects/execute-effect d player-id effect))
                                   db-after-reorder
                                   (or remaining-effects []))
        db-after-move (zones/move-to-zone db-after-remaining spell-id :graveyard)
        db-final (remove-spell-stack-item db-after-move spell-id)]
    {:db db-final :finalized? true}))


;; =====================================================
;; Confirm Selection Wrapper
;; =====================================================

(defn confirm-selection-impl
  "Shared wrapper for all selection confirmations.
   1. Gets game-db and selection from app-db
   2. Calls execute-confirmed-selection multimethod
   3. Handles remaining-effects, cleanup, and chaining based on result

   Returns updated app-db."
  [app-db]
  (let [selection (:game/pending-selection app-db)
        game-db (:game/db app-db)
        result (execute-confirmed-selection game-db selection)]
    (cond
      ;; Chain to next selection
      (:pending-selection result)
      (-> app-db
          (assoc :game/db (:db result))
          (assoc :game/pending-selection (:pending-selection result)))

      ;; Fully handled (pre-cast, ability, pile-choice, scry, cleanup-discard)
      (:finalized? result)
      (cond-> app-db
        true (assoc :game/db (:db result))
        true (dissoc :game/pending-selection)
        (:clear-selected-card? result) (dissoc :game/selected-card))

      ;; Standard: execute remaining-effects and cleanup
      :else
      (let [remaining-effects (:selection/remaining-effects selection)
            player-id (:selection/player-id selection)
            db-after-remaining (reduce (fn [d effect]
                                         (effects/execute-effect d player-id effect))
                                       (:db result)
                                       (or remaining-effects []))
            db-final (cleanup-selection-source db-after-remaining selection)]
        (-> app-db
            (assoc :game/db db-final)
            (dissoc :game/pending-selection))))))


;; =====================================================
;; Re-frame Event Handlers
;; =====================================================

(rf/reg-event-db
  ::set-pending-selection
  (fn [db [_ selection-state]]
    (assoc db :game/pending-selection selection-state)))


(rf/reg-event-db
  ::toggle-selection
  (fn [db [_ id]]
    (let [selection (get db :game/pending-selection)
          selected (get selection :selection/selected #{})
          valid-targets (:selection/valid-targets selection)
          select-count (get selection :selection/select-count 0)
          ;; Pile-choice uses :selection/hand-count for max
          max-count (if (= (:selection/type selection) :pile-choice)
                      (get selection :selection/hand-count 1)
                      select-count)
          currently-selected? (contains? selected id)]
      (cond
        ;; Reject invalid targets when valid-targets set exists
        (and valid-targets (not (contains? (set valid-targets) id)))
        db

        ;; Deselect: remove from set
        currently-selected?
        (assoc-in db [:game/pending-selection :selection/selected]
                  (disj selected id))

        ;; Single-select (select-count=1): replace current selection
        (= max-count 1)
        (assoc-in db [:game/pending-selection :selection/selected]
                  #{id})

        ;; Unlimited select (exact?=false, e.g. exile-cards): always add
        (false? (:selection/exact? selection))
        (assoc-in db [:game/pending-selection :selection/selected]
                  (conj selected id))

        ;; Multi-select under limit: add
        (< (count selected) max-count)
        (assoc-in db [:game/pending-selection :selection/selected]
                  (conj selected id))

        ;; At limit: ignore
        :else db))))


(rf/reg-event-db
  ::confirm-selection
  (fn [db _]
    (let [selection (:game/pending-selection db)
          selection-type (:selection/type selection)]
      ;; Validation depends on selection type
      (if (case selection-type
            ;; Types with flexible selection counts
            (:peek-and-select :graveyard-return)
            (let [selected (:selection/selected selection)
                  candidates (or (:selection/candidates selection)
                                 (:selection/candidate-ids selection))
                  max-count (:selection/select-count selection)]
              (and (<= (count selected) max-count)
                   (every? #(contains? candidates %) selected)))

            ;; Tutor: empty (fail-to-find) or exact count
            :tutor
            (let [selected (:selection/selected selection)
                  candidates (:selection/candidates selection)
                  select-count (or (:selection/select-count selection) 1)]
              (or (empty? selected)
                  (and (= (count selected) select-count)
                       (every? #(contains? candidates %) selected))))

            ;; Pile choice: exact hand-count
            :pile-choice
            (let [selected (:selection/selected selection)
                  hand-count (:selection/hand-count selection)
                  candidates (:selection/candidates selection)]
              (and (= hand-count (count selected))
                   (every? #(contains? candidates %) selected)))

            ;; Exile cards: at least 1
            :exile-cards-cost
            (pos? (count (:selection/selected selection)))

            ;; Scry: always valid
            :scry true

            ;; X mana: always valid
            :x-mana-cost true

            ;; Player target: need a target
            :player-target
            (some? (first (:selection/selected selection)))

            ;; Cast-time and ability targeting: need a target
            (:cast-time-targeting :ability-targeting)
            (= 1 (count (:selection/selected selection)))

            ;; Cleanup-discard and discard: exact count
            (= (count (:selection/selected selection))
               (:selection/select-count selection)))
        ;; Valid - delegate to multimethod wrapper
        (confirm-selection-impl db)
        ;; Invalid - do nothing
        db))))


(rf/reg-event-db
  ::cancel-selection
  (fn [db _]
    ;; Cancel clears selection but keeps the pending state
    ;; Player must still make a valid selection
    (assoc-in db [:game/pending-selection :selection/selected] #{})))


;; === Pile Choice Selection ===
;; For Intuition-style effects where player distributes cards to hand/graveyard

(rf/reg-event-db
  ::select-random-pile-choice
  (fn [db _]
    (select-random-pile-choice db)))


;; === Scry Pile Assignment Events ===
;; Events for assigning cards to top/bottom piles during scry selection

(rf/reg-event-db
  ::scry-assign-top
  (fn [db [_ obj-id]]
    (-> db
        (update-in [:game/pending-selection :selection/top-pile] conj obj-id)
        (update-in [:game/pending-selection :selection/bottom-pile]
                   (fn [pile] (vec (remove #{obj-id} pile)))))))


(rf/reg-event-db
  ::scry-assign-bottom
  (fn [db [_ obj-id]]
    (-> db
        (update-in [:game/pending-selection :selection/bottom-pile] conj obj-id)
        (update-in [:game/pending-selection :selection/top-pile]
                   (fn [pile] (vec (remove #{obj-id} pile)))))))


(rf/reg-event-db
  ::scry-unassign
  (fn [db [_ obj-id]]
    (-> db
        (update-in [:game/pending-selection :selection/top-pile]
                   (fn [pile] (vec (remove #{obj-id} pile))))
        (update-in [:game/pending-selection :selection/bottom-pile]
                   (fn [pile] (vec (remove #{obj-id} pile)))))))


;; === X Cost Selection System ===
;; For spells with X in mana cost or exile-cards additional cost with :x count


(rf/reg-event-db
  ::cancel-exile-cards-selection
  (fn [db _]
    (dissoc db :game/pending-selection)))


(rf/reg-event-db
  ::increment-x-value
  (fn [db _]
    (let [selection (:game/pending-selection db)
          current-x (or (:selection/selected-x selection) 0)
          max-x (or (:selection/max-x selection) 0)]
      (if (< current-x max-x)
        (assoc-in db [:game/pending-selection :selection/selected-x] (inc current-x))
        db))))


(rf/reg-event-db
  ::decrement-x-value
  (fn [db _]
    (let [selection (:game/pending-selection db)
          current-x (or (:selection/selected-x selection) 0)]
      (if (pos? current-x)
        (assoc-in db [:game/pending-selection :selection/selected-x] (dec current-x))
        db))))


(rf/reg-event-db
  ::cancel-x-mana-selection
  (fn [db _]
    (dissoc db :game/pending-selection)))
