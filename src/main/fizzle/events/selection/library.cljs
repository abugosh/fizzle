(ns fizzle.events.selection.library
  "Library domain: tutor, scry, peek-and-select, and pile-choice selection
   builders, execution functions, defmethods, and event handlers.

   Registers methods on core/execute-confirmed-selection and
   core/build-selection-for-effect for library-related selection types."
  (:require
    [clojure.set :as set]
    [datascript.core :as d]
    [fizzle.db.queries :as queries]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.zones :as zones]
    [fizzle.events.selection.core :as core]
    [re-frame.core :as rf]))


;; Forward declarations for builder-declared chains
(declare build-order-bottom-selection)
(declare build-pile-choice-selection)


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
    (let [pile-choice-cfg (:effect/pile-choice tutor-effect)]
      (cond->
        {:selection/zone :library
         :selection/card-source :library
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
         :selection/pile-choice pile-choice-cfg
         :selection/validation :exact-or-zero
         :selection/auto-confirm? true}
        ;; Lifecycle: chaining if pile-choice needed, otherwise standard
        pile-choice-cfg
        (assoc :selection/lifecycle :chaining
               :selection/chain-builder
               (fn [_db sel]
                 (let [selected (:selection/selected sel)]
                   (when (seq selected)
                     (build-pile-choice-selection
                       selected pile-choice-cfg
                       (:selection/player-id sel)
                       (:selection/spell-id sel)
                       (:selection/remaining-effects sel))))))
        (not pile-choice-cfg)
        (assoc :selection/lifecycle :chaining)))))


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
     :selection/lifecycle :finalized
     :selection/card-source :candidates
     :selection/candidates candidates
     :selection/hand-count hand-count
     :selection/select-count hand-count  ; Normalized for toggle-selection-impl
     :selection/selected auto-selected
     :selection/player-id player-id
     :selection/spell-id spell-id
     :selection/remaining-effects remaining-effects
     :selection/allow-random true
     :selection/validation :exact
     :selection/auto-confirm? false}))


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
           :selection/pattern :reorder
           :selection/lifecycle :finalized
           :selection/player-id player-id
           :selection/cards (vec library-cards)
           :selection/top-pile []
           :selection/bottom-pile []
           :selection/spell-id object-id
           :selection/remaining-effects (vec effects-after)
           :selection/validation :always
           :selection/auto-confirm? false})))))


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
       :effect/order-remainder? - Whether to chain to order-bottom selection
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
            (cond->
              {:selection/zone :peek  ; Distinct from :library (tutor)
               :selection/type :peek-and-select
               :selection/lifecycle :chaining
               :selection/card-source :candidates
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
               :selection/validation :at-most
               :selection/auto-confirm? false}
              (:effect/shuffle-remainder? effect)
              (assoc :selection/shuffle-remainder? true)
              (:effect/order-remainder? effect)
              (assoc :selection/order-remainder? true
                     :selection/chain-builder
                     (fn [_db sel]
                       (let [selected (:selection/selected sel)
                             cands (:selection/candidates sel)
                             remainder (set/difference cands selected)]
                         (when (>= (count remainder) 2)
                           (build-order-bottom-selection
                             remainder
                             (:selection/player-id sel)
                             (:selection/spell-id sel)
                             (:selection/remaining-effects sel)))))))))))))


;; =====================================================
;; Order-Bottom Selection (player-ordered bottom placement)
;; =====================================================

(defn build-order-bottom-selection
  "Build pending selection state for ordering cards on bottom of library.
   Used when peek-and-select chains to let player choose bottom order.

   Arguments:
     remainder-ids - Seq of object-ids to order
     player-id - Player performing the ordering
     spell-id - Spell that triggered this selection (for cleanup)
     remaining-effects - Effects to execute after ordering (optional)"
  ([remainder-ids player-id spell-id]
   (build-order-bottom-selection remainder-ids player-id spell-id nil))
  ([remainder-ids player-id spell-id remaining-effects]
   {:selection/type :order-bottom
    :selection/candidates (set remainder-ids)
    :selection/ordered []
    :selection/player-id player-id
    :selection/spell-id spell-id
    :selection/remaining-effects (or remaining-effects [])
    :selection/validation :always
    :selection/auto-confirm? false}))


(defn order-card-in-selection
  "Add a card to the ordered sequence. Pure function on selection map.
   No-op if card is not in candidates or already in ordered."
  [selection object-id]
  (let [candidates (:selection/candidates selection)
        ordered (:selection/ordered selection)]
    (if (and (contains? candidates object-id)
             (not (some #{object-id} ordered)))
      (update selection :selection/ordered conj object-id)
      selection)))


(defn unorder-card-in-selection
  "Remove a card from the ordered sequence. Pure function on selection map.
   No-op if card is not in ordered. Preserves relative order of remaining."
  [selection object-id]
  (update selection :selection/ordered
          (fn [v] (filterv #(not= % object-id) v))))


(defn any-order-selection
  "Fill remaining unordered cards with random order after already-ordered cards.
   Pure function on selection map. If all cards already ordered, identity."
  [selection]
  (let [candidates (:selection/candidates selection)
        ordered (:selection/ordered selection)
        ordered-set (set ordered)
        remaining (remove ordered-set candidates)
        shuffled-remaining (shuffle (vec remaining))]
    (assoc selection :selection/ordered
           (into (vec ordered) shuffled-remaining))))


;; =====================================================
;; Peek-and-Reorder Selection (Portent mechanic)
;; =====================================================

(defn build-peek-and-reorder-selection
  "Build pending selection state for a peek-and-reorder effect.
   Look at top N cards of target player's library, put all back in any order.
   Returns nil if library is empty or count <= 0 (no selection needed).

   Arguments:
     game-db - Datascript game database
     player-id - Player performing the effect (caster)
     object-id - Spell-id for cleanup after selection confirmed
     effect - The :peek-and-reorder effect map with:
       :effect/count - Number of cards to peek (default 0)
       :effect/target - Target player-id (pre-resolved from :effect/target-ref)
     effects-after - Remaining effects to execute after selection"
  [game-db player-id object-id effect effects-after]
  (let [peek-count (or (:effect/count effect) 0)
        target-player (or (:effect/target effect) player-id)]
    (when (pos? peek-count)
      (let [library-cards (queries/get-top-n-library game-db target-player peek-count)]
        (when (seq library-cards)
          (cond->
            {:selection/type :peek-and-reorder
             :selection/pattern :reorder
             :selection/lifecycle :finalized
             :selection/candidates (set library-cards)
             :selection/ordered []
             :selection/player-id player-id
             :selection/target-player target-player
             :selection/spell-id object-id
             :selection/remaining-effects (vec effects-after)
             :selection/validation :always
             :selection/auto-confirm? false}
            (:effect/may-shuffle? effect)
            (assoc :selection/may-shuffle? true)))))))


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
                                        (let [obj-eid (queries/get-object-eid d card-id)]
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
                               (let [obj-eid (queries/get-object-eid d card-id)
                                     new-pos (+ max-pos 1 idx)]
                                 (d/db-with d [[:db/add obj-eid :object/position new-pos]])))
                             db-after-selected
                             (vec remainder-seq))]
    db-after-remainder))


(defn execute-order-bottom-selection
  "Execute order-bottom selection — assign positions to ordered cards at bottom.
   Pure function: (db, selection) -> db

   First card in ordered vector gets lowest new position (top of bottom pile).
   Calculates max position of non-candidate library cards, then assigns
   sequential positions starting at max-pos + 1."
  [game-db selection]
  (let [ordered (:selection/ordered selection)
        candidates (:selection/candidates selection)
        player-id (:selection/player-id selection)
        ;; Belt and suspenders: include any candidates not yet in ordered
        ordered-set (set ordered)
        unordered (remove ordered-set candidates)
        final-ordered (into (vec ordered) (shuffle (vec unordered)))
        ;; Max position of library cards NOT in our candidates set
        library-objs (queries/get-objects-in-zone game-db player-id :library)
        non-candidate-objs (remove #(contains? candidates (:object/id %))
                                   library-objs)
        max-pos (if (seq non-candidate-objs)
                  (apply max (map :object/position non-candidate-objs))
                  -1)]
    (reduce-kv
      (fn [d idx card-id]
        (let [obj-eid (queries/get-object-eid d card-id)
              new-pos (+ max-pos 1 idx)]
          (d/db-with d [[:db/add obj-eid :object/position new-pos]])))
      game-db
      (vec final-ordered))))


(defn execute-peek-and-reorder-selection
  "Execute peek-and-reorder selection — reorder library cards to player's chosen order.
   Pure function: (db, selection) -> db

   Cards in :selection/ordered are placed at positions 0, 1, 2... (click order).
   Unordered cards from candidates stay at their original relative positions.
   Cards not in selection are unaffected."
  [game-db selection]
  (let [ordered (:selection/ordered selection)
        candidates (:selection/candidates selection)
        target-player (or (:selection/target-player selection)
                          (:selection/player-id selection))
        ;; Belt and suspenders: include any candidates not yet in ordered
        ordered-set (set ordered)
        ;; Get all library objects for target player, sorted by current position
        library-objs (->> (queries/get-objects-in-zone game-db target-player :library)
                          (sort-by :object/position))
        ;; Separate candidate cards from non-candidate cards
        non-candidate-objs (remove #(contains? candidates (:object/id %))
                                   library-objs)
        ;; Cards in selection but not in ordered list (keep at original relative order after ordered)
        unordered (filterv #(and (contains? candidates %)
                                 (not (contains? ordered-set %)))
                           (map :object/id library-objs))
        ;; Build new position order:
        ;; 1. Ordered cards (click order)
        ;; 2. Unordered cards from candidates (original relative order)
        ;; 3. Non-candidate cards (original positions preserved)
        new-order (vec (concat ordered
                               unordered
                               (map :object/id non-candidate-objs)))]
    ;; Build position update transactions
    (reduce-kv
      (fn [d idx card-id]
        (let [obj-eid (queries/get-object-eid d card-id)]
          (d/db-with d [[:db/add obj-eid :object/position idx]])))
      game-db
      new-order)))


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
                         (let [obj-eid (queries/get-object-eid game-db obj-id)]
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
            db-final (core/remove-spell-stack-item db-after-move spell-id)]
        (-> app-db
            (assoc :game/db db-final)
            (dissoc :game/pending-selection)))
      ;; Not a scry selection - do nothing
      app-db)))


;; =====================================================
;; Builder Multimethod Registrations
;; =====================================================

(defmethod core/build-selection-for-effect :tutor
  [db player-id object-id effect remaining]
  (build-tutor-selection db player-id object-id effect remaining))


(defmethod core/build-selection-for-effect :scry
  [db player-id object-id effect remaining]
  (build-scry-selection db player-id object-id effect remaining))


(defmethod core/build-selection-for-effect :peek-and-select
  [db player-id object-id effect remaining]
  (build-peek-selection db player-id object-id effect remaining))


(defmethod core/build-selection-for-effect :peek-and-reorder
  [db player-id object-id effect remaining]
  (build-peek-and-reorder-selection db player-id object-id effect remaining))


;; =====================================================
;; Execute Confirmed Selection Defmethods
;; =====================================================

(defmethod core/execute-confirmed-selection :tutor
  [game-db selection]
  (let [pile-choice (:selection/pile-choice selection)
        selected (:selection/selected selection)]
    (if (and pile-choice (seq selected))
      ;; Pile-choice will handle card distribution — db unchanged
      {:db game-db}
      ;; Normal tutor flow — execute immediately
      {:db (execute-tutor-selection game-db selection)})))


(defmethod core/execute-confirmed-selection :peek-and-select
  [game-db selection]
  (let [order-remainder? (:selection/order-remainder? selection)
        selected (:selection/selected selection)
        candidates (:selection/candidates selection)
        remainder (set/difference candidates selected)]
    (if (and order-remainder? (>= (count remainder) 2))
      ;; Order-bottom will handle remainder — only move selected to hand
      (let [selected-zone (or (:selection/selected-zone selection) :hand)]
        {:db (reduce (fn [d card-id]
                       (zones/move-to-zone d card-id selected-zone))
                     game-db
                     selected)})
      ;; Standard path: execute immediately
      {:db (execute-peek-selection game-db selection)})))


(defmethod core/execute-confirmed-selection :order-bottom
  [game-db selection]
  {:db (execute-order-bottom-selection game-db selection)})


(defmethod core/execute-confirmed-selection :peek-and-reorder
  [game-db selection]
  (let [spell-id (:selection/spell-id selection)
        player-id (:selection/player-id selection)
        remaining-effects (:selection/remaining-effects selection)
        target-player (or (:selection/target-player selection) player-id)
        ;; If player chose shuffle, shuffle the target's library instead of reordering
        db-after-reorder (if (:selection/shuffle? selection)
                           (zones/shuffle-library game-db target-player)
                           (execute-peek-and-reorder-selection game-db selection))
        db-after-remaining (reduce (fn [d effect]
                                     (effects/execute-effect d player-id effect))
                                   db-after-reorder
                                   (or remaining-effects []))
        ;; Only move spell if it exists (test helpers may not create spell objects)
        spell-obj (queries/get-object db-after-remaining spell-id)
        db-after-move (if spell-obj
                        (zones/move-to-zone db-after-remaining spell-id :graveyard)
                        db-after-remaining)
        db-final (core/remove-spell-stack-item db-after-move spell-id)]
    {:db db-final}))


(defmethod core/execute-confirmed-selection :pile-choice
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
        db-final (core/remove-spell-stack-item db-after-move spell-id)]
    {:db db-final}))


(defmethod core/execute-confirmed-selection :scry
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
        db-final (core/remove-spell-stack-item db-after-move spell-id)]
    {:db db-final}))


;; =====================================================
;; Re-frame Event Handlers
;; =====================================================
;; CRITICAL: Use explicit fully-qualified keywords (not :: syntax)
;; to preserve backward compatibility with views/modals.cljs dispatches.

(rf/reg-event-db
  :fizzle.events.selection/select-random-pile-choice
  (fn [db _]
    (select-random-pile-choice db)))


(rf/reg-event-db
  :fizzle.events.selection/scry-assign-top
  (fn [db [_ obj-id]]
    (-> db
        (update-in [:game/pending-selection :selection/top-pile] conj obj-id)
        (update-in [:game/pending-selection :selection/bottom-pile]
                   (fn [pile] (vec (remove #{obj-id} pile)))))))


(rf/reg-event-db
  :fizzle.events.selection/scry-assign-bottom
  (fn [db [_ obj-id]]
    (-> db
        (update-in [:game/pending-selection :selection/bottom-pile] conj obj-id)
        (update-in [:game/pending-selection :selection/top-pile]
                   (fn [pile] (vec (remove #{obj-id} pile)))))))


(rf/reg-event-db
  :fizzle.events.selection/scry-unassign
  (fn [db [_ obj-id]]
    (-> db
        (update-in [:game/pending-selection :selection/top-pile]
                   (fn [pile] (vec (remove #{obj-id} pile))))
        (update-in [:game/pending-selection :selection/bottom-pile]
                   (fn [pile] (vec (remove #{obj-id} pile)))))))


(rf/reg-event-db
  :fizzle.events.selection/order-card
  (fn [db [_ obj-id]]
    (update db :game/pending-selection order-card-in-selection obj-id)))


(rf/reg-event-db
  :fizzle.events.selection/unorder-card
  (fn [db [_ obj-id]]
    (update db :game/pending-selection unorder-card-in-selection obj-id)))


(rf/reg-event-db
  :fizzle.events.selection/any-order
  (fn [db _]
    (update db :game/pending-selection any-order-selection)))


(rf/reg-event-db
  :fizzle.events.selection/shuffle-and-confirm
  (fn [db _]
    (-> db
        (assoc-in [:game/pending-selection :selection/shuffle?] true)
        (core/confirm-selection-handler))))
