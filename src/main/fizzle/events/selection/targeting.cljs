(ns fizzle.events.selection.targeting
  "Targeting domain: cast-time targeting and player-target selection types.

   Selection Types:
   - :cast-time-targeting — spell has :card/targeting, player picks target before cast
   - :player-target — effect targets :any-player, player picks which player

   Registers defmethods on:
   - core/execute-confirmed-selection for :cast-time-targeting, :player-target
   - core/build-selection-for-effect for :player-target"
  (:require
    [datascript.core :as d]
    [fizzle.db.game-state :as game-state]
    [fizzle.db.queries :as queries]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.targeting :as targeting]
    [fizzle.events.selection.core :as core]
    [fizzle.events.selection.costs :as sel-costs]))


;; =====================================================
;; Selection Builders
;; =====================================================

(defn- build-player-target-selection
  "Build pending selection state for a player-targeted effect.
   Used when :effect/target is :any-player - player must choose target."
  [player-id object-id target-effect effects-after]
  {:selection/mechanism :n-slot-targeting
   :selection/domain    :player-target
   :selection/lifecycle :finalized
   :selection/player-id player-id
   :selection/selected #{}
   :selection/select-count 1
   :selection/valid-targets #{game-state/human-player-id game-state/opponent-player-id}
   :selection/spell-id object-id
   :selection/target-effect target-effect  ; The effect needing a target
   :selection/remaining-effects effects-after
   :selection/validation :exact
   :selection/auto-confirm? true})


(defn build-cast-time-target-selection
  "Build pending selection state for cast-time targeting.
   Accepts N target-reqs as a vector. Single-req (N=1) and multi-req (N>1)
   both produce an :n-slot-targeting selection.

   Distinctness is enforced by default when two or more reqs share the same
   :target/type, :target/options, and :target/criteria. Opt out by setting
   :target/allow-duplicate true on at least one of the reqs that share criteria.

   valid-targets is computed from the first req (for v1, assumes all reqs with
   shared criteria have identical valid-target sets).

   Arguments:
     game-db - Datascript database
     player-id - Casting player
     object-id - Spell being cast
     mode - Casting mode being used
     target-reqs - Vector of targeting requirement maps

   Returns selection state map."
  [game-db player-id object-id mode target-reqs]
  (let [n (count target-reqs)
        first-req (first target-reqs)
        target-type (:target/type first-req)
        valid-targets (targeting/find-valid-targets game-db player-id first-req)
        has-generic? (sel-costs/has-generic-mana-cost? (:mode/mana-cost mode))
        ;; Distinctness: enforced by default when any two reqs share criteria.
        ;; Opt-out: if at least one of the reqs matching another has :target/allow-duplicate true.
        criteria-keys [:target/type :target/options :target/criteria]
        any-shared? (when (> n 1)
                      (some (fn [[req-a req-b]]
                              (= (select-keys req-a criteria-keys)
                                 (select-keys req-b criteria-keys)))
                            (for [i (range n) j (range n) :when (< i j)]
                              [(nth target-reqs i) (nth target-reqs j)])))
        any-allow-dup? (some :target/allow-duplicate target-reqs)
        enforce-distinctness? (boolean (and any-shared? (not any-allow-dup?)))]
    (cond->
      {:selection/mechanism :n-slot-targeting
       :selection/domain    (if (= :ability target-type) :ability-cast-targeting :cast-time-targeting)
       :selection/player-id player-id
       :selection/object-id object-id
       :selection/mode mode
       :selection/target-requirements target-reqs
       :selection/valid-targets valid-targets
       :selection/selected []
       :selection/select-count n
       :selection/validation :exact
       :selection/auto-confirm? (= 1 n)
       :selection/enforce-distinctness enforce-distinctness?}
      (#{:object :any} target-type)
      (assoc :selection/card-source :valid-targets)
      has-generic?
      (assoc :selection/lifecycle :chaining)
      (not has-generic?)
      (assoc :selection/lifecycle :finalized
             :selection/clear-selected-card? true))))


;; =====================================================
;; Cast-Time Targeting Helpers
;; =====================================================

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
      (let [selection (build-cast-time-target-selection game-db player-id object-id mode targeting-reqs)]
        {:db game-db
         :pending-target-selection selection})
      ;; No targeting - cast normally
      {:db (if (rules/can-cast? game-db player-id object-id)
             (rules/cast-spell game-db player-id object-id)
             game-db)
       :pending-target-selection nil})))


(defn- build-targets-map
  "Build the :stack-item/targets map from selection state.
   Supports both multi-req (new) and single-req (legacy) selections.
   - :selection/target-requirements (vector of reqs): zip with :selection/selected items.
     selected may be a vector (new) or set/seq (legacy confirm). Elements are zipped in order.
   - :selection/target-requirement (singular, legacy): use first of :selection/selected.
   Returns a map {target-id -> selected-target-id}."
  [selection]
  (let [target-reqs (:selection/target-requirements selection)
        selected (:selection/selected selection)]
    (cond
      ;; Multi-req path (new): zip target-reqs vector with selected (any collection)
      (seq target-reqs)
      (into {} (map (fn [req t] [(:target/id req) t]) target-reqs (seq selected)))

      ;; Legacy single-req path: :selection/target-requirement (singular)
      :else
      (let [target-req (:selection/target-requirement selection)
            selected-target (first (seq selected))
            target-id (:target/id target-req)]
        (when (and target-id selected-target)
          {target-id selected-target})))))


(defn confirm-cast-time-target
  "Complete casting a spell after target selection.

   Supports N-slot (multi-req) and legacy single-req selections.

   For multi-req: expects :selection/selected to be a vector ordered by
   :selection/target-requirements. Zips them to build :stack-item/targets map.

   For single-req (legacy): expects :selection/target-requirement (singular)
   and :selection/selected (set or vector with one element).

   Distinctness guard: if :selection/enforce-distinctness is true and the
   selected targets contain duplicates, returns game-db unchanged (no-op).

   1. Pays mana and additional costs
   2. Moves spell to stack
   3. Stores target map on the stack-item as :stack-item/targets

   Arguments:
     game-db - Datascript database
     selection - Cast-time target selection state with :selection/selected set

   Returns updated db with spell on stack and target stored."
  [game-db selection]
  (let [player-id (:selection/player-id selection)
        object-id (:selection/object-id selection)
        mode (:selection/mode selection)
        selected (:selection/selected selection)
        enforce-distinctness? (:selection/enforce-distinctness selection)
        selected-seq (vec (seq selected))]
    (cond
      ;; Guard: distinctness violation — no-op (player selected duplicate targets)
      (and enforce-distinctness?
           (> (count selected-seq) 1)
           (not= (count selected-seq) (count (set selected-seq))))
      game-db

      ;; Guard: no targets selected — no-op
      (empty? selected-seq)
      game-db

      ;; Proceed with cast
      :else
      (let [targets-map (build-targets-map selection)]
        (if (empty? targets-map)
          game-db
          ;; Cast spell and store targets on stack-item
          (let [;; Read pending-sacrifice-info off spell object BEFORE cast (cast may clear it)
                spell-obj-eid (queries/get-object-eid game-db object-id)
                pending-sacrifice-info (when spell-obj-eid
                                         (d/q '[:find ?info .
                                                :in $ ?e
                                                :where [?e :object/pending-sacrifice-info ?info]]
                                              game-db spell-obj-eid))
                ;; Cast via rules/cast-spell-mode (pays costs, moves to stack)
                db-after-cast (rules/cast-spell-mode game-db player-id object-id mode)
                ;; Find object EID to locate stack-item
                obj-eid (queries/get-object-eid db-after-cast object-id)
                ;; Find the stack-item by object reference
                stack-item (when obj-eid
                             (d/q '[:find (pull ?e [:db/id]) .
                                    :in $ ?obj-eid
                                    :where [?e :stack-item/object-ref ?obj-eid]]
                                  db-after-cast obj-eid))
                stack-item-eid (:db/id stack-item)
                ;; Build txdata: always store targets, optionally store sacrifice-info,
                ;; and retract pending-sacrifice-info from spell object (temporary attribute)
                txdata (cond-> []
                         stack-item-eid (conj [:db/add stack-item-eid :stack-item/targets targets-map])
                         (and stack-item-eid pending-sacrifice-info) (conj [:db/add stack-item-eid :stack-item/sacrifice-info pending-sacrifice-info])
                         (and spell-obj-eid pending-sacrifice-info) (conj [:db/retract spell-obj-eid :object/pending-sacrifice-info pending-sacrifice-info]))]
            (if (seq txdata)
              (d/db-with db-after-cast txdata)
              db-after-cast)))))))


;; =====================================================
;; Builder Multimethod Registration
;; =====================================================

(defmethod core/build-selection-for-effect :player-target
  [_db player-id object-id effect remaining]
  (build-player-target-selection player-id object-id effect remaining))


;; =====================================================
;; Apply Domain Policy Defmethods (ADR-030)
;; =====================================================

(defmethod core/apply-domain-policy :player-target
  [game-db selection]
  (let [selected-target (first (:selection/selected selection))
        target-effect (:selection/target-effect selection)
        remaining-effects (vec (or (:selection/remaining-effects selection) []))
        player-id (:selection/player-id selection)
        resolved-effect (assoc target-effect :effect/target selected-target)
        db-after-effect (effects/execute-effect game-db player-id resolved-effect)
        result (effects/reduce-effects db-after-effect player-id remaining-effects)]
    {:db (:db result)}))


(defmethod core/apply-domain-policy :cast-time-targeting
  [game-db selection]
  (if (= :chaining (:selection/lifecycle selection))
    {:db game-db}
    {:db (confirm-cast-time-target game-db selection)}))


(defmethod core/apply-domain-policy :ability-cast-targeting
  [game-db selection]
  (if (= :chaining (:selection/lifecycle selection))
    {:db game-db}
    {:db (confirm-cast-time-target game-db selection)}))


;; =====================================================
;; Confirm Selection Multimethod
;; =====================================================


;; Generic chain: targeting selections that chain to mana-allocation.
;; Dispatches for :cast-time-targeting and :ability-cast-targeting via hierarchy.
(defmethod core/build-chain-selection :targeting-to-mana-allocation
  [db selection]
  (let [mode (:selection/mode selection)
        cost (:mode/mana-cost mode)
        player-id (:selection/player-id selection)
        object-id (:selection/object-id selection)
        ;; Support both plural :selection/target-requirements (new) and singular
        ;; :selection/target-requirement (legacy).
        pending-targets (build-targets-map selection)]
    (when-let [sel (sel-costs/build-mana-allocation-selection db player-id object-id mode cost)]
      (assoc sel :selection/pending-targets pending-targets))))
