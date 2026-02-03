(ns fizzle.engine.rules
  "Core game rules for Fizzle.

   Orchestrates mana, zones, and effects into casting operations.
   All functions are pure: (db, args) -> db"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.conditions :as conditions]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.triggers :as triggers]
    [fizzle.engine.zones :as zones]))


;; =====================================================
;; Alternate Casting Modes
;; =====================================================

(defn- get-primary-mode
  "Returns the primary casting mode for a card.
   Primary mode is: cast from hand with the card's mana cost."
  [card]
  {:mode/id :primary
   :mode/zone :hand
   :mode/mana-cost (or (:card/mana-cost card) {})
   :mode/additional-costs []
   :mode/on-resolve (if (some #{:instant :sorcery} (:card/types card))
                      :graveyard
                      :battlefield)})


(defn- alternate-to-mode
  "Converts an alternate-cost definition to a mode map."
  [alternate]
  {:mode/id (:alternate/id alternate)
   :mode/zone (:alternate/zone alternate)
   :mode/mana-cost (or (:alternate/mana-cost alternate) {})
   :mode/additional-costs (or (:alternate/additional-costs alternate) [])
   :mode/on-resolve (or (:alternate/on-resolve alternate) :graveyard)})


(defn- get-alternate-modes
  "Returns alternate casting modes that are valid for the card's current zone."
  [card current-zone]
  (let [alternates (or (:card/alternate-costs card) [])]
    (->> alternates
         (filter #(= current-zone (:alternate/zone %)))
         (map alternate-to-mode))))


(defn get-casting-modes
  "Returns all valid casting modes for a card based on its current zone.
   Each mode is a map with :mode/id, :mode/mana-cost, :mode/additional-costs, :mode/on-resolve.

   - Card in hand: returns primary mode + any alternates with :alternate/zone :hand
   - Card in graveyard: returns only alternates with :alternate/zone :graveyard (e.g. flashback)
   - Card elsewhere: returns empty (can't cast)"
  [db _player-id object-id]
  (if-let [obj (q/get-object db object-id)]
    (let [card (:object/card obj)
          current-zone (:object/zone obj)]
      (case current-zone
        :hand (let [primary (get-primary-mode card)
                    alternates (get-alternate-modes card :hand)]
                (into [primary] alternates))
        :graveyard (vec (get-alternate-modes card :graveyard))
        ;; Other zones: can't cast
        []))
    []))


(defn- can-pay-additional-cost?
  "Check if an additional cost can be paid.
   Uses costs/can-pay? for recognized cost types."
  [db _player-id object-id cost]
  (case (:cost/type cost)
    :pay-life (let [obj (q/get-object db object-id)
                    controller-eid (:db/id (:object/controller obj))
                    current-life (d/q '[:find ?life .
                                        :in $ ?p
                                        :where [?p :player/life ?life]]
                                      db controller-eid)]
                (>= (or current-life 0) (:cost/amount cost)))
    ;; For unimplemented cost types, return false
    ;; This ensures we don't accidentally allow casting when costs can't be validated
    false))


(defn- pay-additional-cost
  "Pay a single additional cost. Returns updated db.
   Precondition: cost has been validated with can-pay-additional-cost?"
  [db _player-id object-id cost]
  (case (:cost/type cost)
    :pay-life (let [obj (q/get-object db object-id)
                    controller-eid (:db/id (:object/controller obj))
                    current-life (d/q '[:find ?life .
                                        :in $ ?p
                                        :where [?p :player/life ?life]]
                                      db controller-eid)
                    new-life (- current-life (:cost/amount cost))]
                (d/db-with db [[:db/add controller-eid :player/life new-life]]))
    ;; Unknown cost type - return db unchanged (validated in can-pay)
    db))


(defn- pay-additional-costs
  "Pay all additional costs for a mode. Returns updated db."
  [db player-id object-id costs]
  (reduce (fn [d cost] (pay-additional-cost d player-id object-id cost))
          db
          (or costs [])))


(defn can-cast-mode?
  "Check if player can pay all costs for a specific casting mode.
   Checks mana cost AND all additional costs."
  [db player-id object-id mode]
  (let [mana-payable (mana/can-pay? db player-id (:mode/mana-cost mode))
        additional-costs (:mode/additional-costs mode)
        additional-payable (every? #(can-pay-additional-cost? db player-id object-id %)
                                   additional-costs)]
    (and mana-payable additional-payable)))


(defn can-cast?
  "Check if a player can cast a card.

   Checks if ANY valid casting mode is castable.
   A mode is valid if:
   - The card is in the mode's required zone
   - Player can pay the mode's mana cost
   - Player can pay all additional costs

   Returns false if object doesn't exist or no modes are castable."
  [db player-id object-id]
  (let [modes (get-casting-modes db player-id object-id)]
    (boolean (some #(can-cast-mode? db player-id object-id %) modes))))


(defn get-castable-cards
  "Returns all cards (from hand and graveyard) with at least one valid casting mode.
   Returns vector of objects with card data."
  [db player-id]
  (let [hand-objs (q/get-objects-in-zone db player-id :hand)
        gy-objs (q/get-objects-in-zone db player-id :graveyard)
        all-candidates (concat hand-objs gy-objs)]
    (->> all-candidates
         (filter #(can-cast? db player-id (:object/id %)))
         vec)))


(defn- increment-storm
  "Increment a player's storm count."
  [db player-id]
  (let [player-eid (q/get-player-eid db player-id)
        current (q/get-storm-count db player-id)]
    (d/db-with db [[:db/add player-eid :player/storm-count (inc current)]])))


(defn- maybe-create-storm-trigger
  "Create storm trigger if spell has :storm keyword and is not a copy.

   Storm count for copies = current storm count - 1 (spells BEFORE this one).
   Copies (:object/is-copy true) do not trigger storm."
  [db player-id object-id]
  (let [obj (q/get-object db object-id)]
    (if-let [card (:object/card obj)]
      (let [keywords (:card/keywords card)
            is-copy (:object/is-copy obj)
            ;; Check if :storm is in keywords (handle both sets and vectors)
            has-storm (cond
                        (set? keywords) (contains? keywords :storm)
                        (sequential? keywords) (some #{:storm} keywords)
                        :else false)]
        (if (and has-storm (not is-copy))
          ;; Create storm trigger - count = spells cast before this one
          (let [storm-count (q/get-storm-count db player-id)
                copy-count (dec storm-count)  ; Already incremented, so dec for "before"
                trigger (triggers/create-trigger :storm object-id player-id {:count copy-count})]
            (triggers/add-trigger-to-stack db trigger))
          db))
      db)))


(defn- set-stack-order
  "Set stack position on an object so stack resolves LIFO."
  [db object-id]
  (let [stack-order (q/get-next-stack-order db)
        obj-eid (d/q '[:find ?e .
                       :in $ ?oid
                       :where [?e :object/id ?oid]]
                     db object-id)]
    (d/db-with db [[:db/add obj-eid :object/position stack-order]])))


(defn- set-cast-mode
  "Store the casting mode on an object for use during resolution."
  [db object-id mode]
  (let [obj-eid (d/q '[:find ?e .
                       :in $ ?oid
                       :where [?e :object/id ?oid]]
                     db object-id)]
    (d/db-with db [[:db/add obj-eid :object/cast-mode mode]])))


(defn cast-spell-mode
  "Cast a spell using a specific mode.

   - Pays mana cost from mode
   - Pays additional costs (life, etc.)
   - Moves card to stack (with stack order for LIFO resolution)
   - Stores casting mode on object for resolution
   - Increments storm count
   - Creates storm trigger if spell has :storm keyword

   Precondition: Caller should verify can-cast-mode? first."
  [db player-id object-id mode]
  (-> db
      (mana/pay-mana player-id (:mode/mana-cost mode))
      (pay-additional-costs player-id object-id (:mode/additional-costs mode))
      (zones/move-to-zone object-id :stack)
      (set-stack-order object-id)
      (set-cast-mode object-id mode)
      (increment-storm player-id)
      (maybe-create-storm-trigger player-id object-id)))


(defn cast-spell
  "Cast a spell from hand using the primary mode.

   - Pays mana cost
   - Moves card to stack (with stack order for LIFO resolution)
   - Increments storm count
   - Creates storm trigger if spell has :storm keyword

   Caller should verify can-cast? first.

   Note: For cards with alternate costs, use cast-spell-mode with the
   desired mode from get-casting-modes."
  [db player-id object-id]
  (let [modes (get-casting-modes db player-id object-id)
        primary (first (filter #(= :primary (:mode/id %)) modes))]
    (if primary
      (cast-spell-mode db player-id object-id primary)
      ;; Fallback for cards in graveyard with only flashback
      ;; In normal usage, caller uses cast-spell-mode directly for non-primary
      (let [obj (q/get-object db object-id)
            card (:object/card obj)
            cost (:card/mana-cost card)]
        (-> db
            (mana/pay-mana player-id cost)
            (zones/move-to-zone object-id :stack)
            (set-stack-order object-id)
            (increment-storm player-id)
            (maybe-create-storm-trigger player-id object-id))))))


(defn get-active-effects
  "Select which effects to use based on card conditions.

   Cards may have:
   - :card/effects - Default effects (always used if no condition matches)
   - :card/conditional-effects - Map of condition keyword to effects list
     e.g., {:threshold [{:effect/type :add-mana :effect/mana {:black 5}}]}

   Condition checking is done at resolution time (not cast time)."
  [db player-id card]
  (let [default-effects (:card/effects card)
        conditional-effects (:card/conditional-effects card)]
    (cond
      ;; Check threshold condition if card has it
      (and (:threshold conditional-effects)
           (conditions/threshold? db player-id))
      (:threshold conditional-effects)

      ;; Fall back to default effects
      :else
      default-effects)))


(defn permanent-type?
  "Check if card types include a permanent type (artifact, creature, enchantment, planeswalker)."
  [card-types]
  (let [types-set (set card-types)]
    (boolean (some types-set [:artifact :creature :enchantment :planeswalker]))))


(defn resolve-spell
  "Resolve a spell on the stack.

   - Verifies spell is on stack (no-op if not)
   - Checks conditions and selects appropriate effects
   - Executes all selected effects
   - Uses mode's :mode/on-resolve if present (e.g., :exile for flashback)
   - Otherwise: permanents to battlefield, instants/sorceries to graveyard"
  [db player-id object-id]
  (let [obj (q/get-object db object-id)]
    (if (not= :stack (:object/zone obj))
      db  ; No-op if spell not on stack
      (let [card (:object/card obj)
            card-types (:card/types card)
            effects-list (get-active-effects db player-id card)
            cast-mode (:object/cast-mode obj)
            mode-destination (:mode/on-resolve cast-mode)
            destination (cond
                          ;; Use mode's destination if specified
                          mode-destination mode-destination
                          ;; Permanents go to battlefield
                          (permanent-type? card-types) :battlefield
                          ;; Default: graveyard
                          :else :graveyard)]
        (as-> db db'
              (reduce (fn [d effect] (effects/execute-effect d player-id effect))
                      db'
                      (or effects-list []))
              (zones/move-to-zone db' object-id destination))))))


(defn move-spell-off-stack
  "Remove a spell from stack without resolving (counter, fizzle).

   Uses cast mode's :mode/on-resolve for destination if present.
   Otherwise sends to graveyard.

   This ensures flashback spells exile when countered, per MTG rules:
   'If the flashback cost was paid, exile this card instead of putting
   it anywhere else any time it would leave the stack.'"
  [db _player-id object-id]
  (let [obj (q/get-object db object-id)]
    (if (not= :stack (:object/zone obj))
      db  ; No-op if not on stack
      (let [cast-mode (:object/cast-mode obj)
            mode-destination (:mode/on-resolve cast-mode)
            destination (or mode-destination :graveyard)]
        (zones/move-to-zone db object-id destination)))))
