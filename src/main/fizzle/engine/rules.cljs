(ns fizzle.engine.rules
  "Core game rules for Fizzle.

   Orchestrates mana, zones, and effects into casting operations.
   All functions are pure: (db, args) -> db"
  (:require
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.engine.conditions :as conditions]
    [fizzle.engine.costs :as costs]
    [fizzle.engine.effects :as effects]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.priority :as priority]
    [fizzle.engine.stack :as stack]
    [fizzle.engine.targeting :as targeting]
    [fizzle.engine.zones :as zones]))


;; =====================================================
;; Alternate Casting Modes
;; =====================================================

(defn- merge-mana-costs
  "Merge two mana costs by adding values for each color.
   Used for combining base cost with kicker cost."
  [cost1 cost2]
  (merge-with + (or cost1 {}) (or cost2 {})))


(defn- get-primary-mode
  "Returns the primary casting mode for a card.
   Primary mode is: cast from hand with the card's mana cost."
  [card]
  {:mode/id :primary
   :mode/zone :hand
   :mode/mana-cost (or (:card/mana-cost card) {})
   :mode/additional-costs []
   :mode/on-resolve (if (some #{:instant :sorcery} (set (:card/types card)))
                      :graveyard
                      :battlefield)})


(defn- get-kicked-mode
  "Returns the kicked casting mode for a card with kicker, or nil if no kicker.
   Kicked mode combines base cost with kicker cost and uses kicked effects."
  [card]
  (when (:card/kicker card)
    {:mode/id :kicked
     :mode/zone :hand
     :mode/mana-cost (merge-mana-costs (:card/mana-cost card) (:card/kicker card))
     :mode/additional-costs []
     :mode/effects (or (:card/kicked-effects card) [])
     :mode/on-resolve (if (some #{:instant :sorcery} (set (:card/types card)))
                        :graveyard
                        :battlefield)}))


(defn- alternate-to-mode
  "Converts an alternate-cost definition to a mode map."
  [alternate]
  {:mode/id (:alternate/id alternate)
   :mode/zone (:alternate/zone alternate)
   :mode/mana-cost (or (:alternate/mana-cost alternate) {})
   :mode/additional-costs (or (:alternate/additional-costs alternate) [])
   :mode/on-resolve (or (:alternate/on-resolve alternate) :graveyard)})


(defn- get-alternate-modes
  "Returns alternate casting modes that are valid for the card's current zone.
   Includes both card-defined alternate costs and granted alternate costs."
  [card current-zone object-grants]
  (let [;; Card-level alternate costs
        card-alternates (or (:card/alternate-costs card) [])
        ;; Granted alternate costs (from :object/grants)
        granted-alternates (->> object-grants
                                (filter #(= :alternate-cost (:grant/type %)))
                                (map :grant/data))
        ;; Combine both
        all-alternates (concat card-alternates granted-alternates)]
    (->> all-alternates
         (filter #(= current-zone (:alternate/zone %)))
         (map alternate-to-mode))))


(defn get-casting-modes
  "Returns all valid casting modes for a card based on its current zone.
   Each mode is a map with :mode/id, :mode/mana-cost, :mode/additional-costs, :mode/on-resolve.

   Includes both card-defined alternate costs and granted alternate costs.

   - Card in hand: returns primary mode + any alternates with :alternate/zone :hand
   - Card in graveyard: returns only alternates with :alternate/zone :graveyard (e.g. flashback)
   - Card elsewhere: returns empty (can't cast)"
  [db _player-id object-id]
  (if-let [obj (q/get-object db object-id)]
    (let [card (:object/card obj)
          current-zone (:object/zone obj)
          object-grants (q/get-grants db object-id)]
      (case current-zone
        :hand (let [primary (get-primary-mode card)
                    kicked (get-kicked-mode card)
                    alternates (get-alternate-modes card :hand object-grants)]
                (cond-> [primary]
                  kicked (conj kicked)
                  true (into alternates)))
        :graveyard (vec (get-alternate-modes card :graveyard object-grants))
        ;; Other zones: can't cast
        []))
    []))


(defn- additional-cost->cost-map
  "Convert a namespaced additional cost to costs.cljs multimethod format.
   {:cost/type :pay-life :cost/amount 3} -> {:pay-life 3}
   {:cost/type :exile-cards :cost/zone z ...} -> {:exile-cards {:zone z ...}}"
  [cost]
  (case (:cost/type cost)
    :pay-life {:pay-life (:cost/amount cost)}
    :exile-cards {:exile-cards {:zone (:cost/zone cost)
                                :criteria (:cost/criteria cost)
                                :count (:cost/count cost)}}))


(defn- can-pay-additional-cost?
  "Check if an additional cost can be paid.
   Delegates to costs/can-pay? multimethod."
  [db _player-id object-id cost]
  (costs/can-pay? db object-id (additional-cost->cost-map cost)))


(defn- pay-additional-cost
  "Pay a single additional cost. Returns updated db.
   Delegates to costs/pay-cost multimethod.
   Precondition: cost has been validated with can-pay-additional-cost?

   Note: Some costs like :exile-cards require player selection and are
   handled at the event layer. costs/pay-cost returns db unchanged for those,
   and the actual payment happens after player confirms selection."
  [db _player-id object-id cost]
  (or (costs/pay-cost db object-id (additional-cost->cost-map cost))
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


(defn has-restriction?
  "Check if a player has an active restriction of the given type.
   Returns true if restriction exists, false otherwise.

   Used by can-cast? to enforce player restrictions like 'cannot cast spells'.

   Edge cases:
   - Non-existent player: returns false (no grants)
   - Multiple matching grants: returns true (finds first match)
   - Different restriction type: returns false (specific matching)"
  [db player-id restriction-type]
  (let [restriction-grants (grants/get-player-grants-by-type db player-id :restriction)]
    (boolean
      (some #(= restriction-type (get-in % [:grant/data :restriction/type]))
            restriction-grants))))


(defn- instant-speed?
  "Check if a card can be cast at instant speed.
   Returns true for instants and cards with flash keyword."
  [card]
  (let [types (:card/types card)
        keywords (:card/keywords card)]
    (or (contains? (set types) :instant)
        (and (set? keywords) (contains? keywords :flash))
        (and (sequential? keywords) (some #{:flash} keywords)))))


(defn- sorcery-speed-ok?
  "Check if sorcery-speed timing requirements are met.
   Sorcery speed requires: main phase (main1 or main2) and empty stack."
  [db]
  (let [game-state (q/get-game-state db)
        phase (:game/phase game-state)]
    (boolean
      (and (#{:main1 :main2} phase)
           (q/stack-empty? db)))))


(defn can-cast?
  "Check if a player can cast a card.

   Checks if ANY valid casting mode is castable.
   A mode is valid if:
   - Player does not have :cannot-cast-spells restriction
   - Timing is correct (sorcery-speed cards need main phase + empty stack)
   - The card is in the mode's required zone
   - Player can pay the mode's mana cost
   - Player can pay all additional costs
   - Valid targets exist for all required targeting

   Returns false if object doesn't exist or no modes are castable."
  [db player-id object-id]
  ;; Check priority phase FIRST (no actions during untap/cleanup)
  (if-not (priority/in-priority-phase? (:game/phase (q/get-game-state db)))
    false
    ;; Check player restrictions (before any other checks)
    (if (has-restriction? db player-id :cannot-cast-spells)
      false
      (if-let [obj (q/get-object db object-id)]
        (let [card (:object/card obj)
              card-types (set (:card/types card))
              ;; Check type-specific restriction: cannot-cast-instants-sorceries
              ;; Only blocks instants and sorceries, not lands/artifacts/etc.
              type-restricted (and (has-restriction? db player-id :cannot-cast-instants-sorceries)
                                   (or (contains? card-types :instant)
                                       (contains? card-types :sorcery)))]
          (if type-restricted
            false
            (let [;; Check timing: non-instant cards require sorcery speed
                  timing-ok (or (instant-speed? card)
                                (sorcery-speed-ok? db))
                  modes (get-casting-modes db player-id object-id)
                  has-mode (boolean (some #(can-cast-mode? db player-id object-id %) modes))
                  has-targets (targeting/has-valid-targets? db player-id card)]
              (and timing-ok has-mode has-targets))))
        false))))


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
          (let [storm-count (q/get-storm-count db player-id)
                copy-count (dec storm-count)]
            (stack/create-stack-item db
                                     {:stack-item/type :storm
                                      :stack-item/controller player-id
                                      :stack-item/source object-id
                                      :stack-item/effects [{:effect/type :storm-copies
                                                            :effect/count copy-count}]
                                      :stack-item/description (str "Storm - create " copy-count " copies")}))
          db))
      db)))


(defn- create-spell-stack-item
  "Create a stack-item for a spell being cast.
   Also sets object/position to the same value for backward compatibility."
  [db player-id object-id]
  (let [obj-eid (q/get-object-eid db object-id)
        db-with-item (stack/create-stack-item db
                                              {:stack-item/type :spell
                                               :stack-item/controller player-id
                                               :stack-item/source object-id
                                               :stack-item/object-ref obj-eid})
        ;; Get the position that was auto-assigned to the stack-item
        stack-item (stack/get-stack-item-by-object-ref db-with-item obj-eid)
        position (:stack-item/position stack-item)]
    ;; Set object/position to match stack-item for backward compat
    (d/db-with db-with-item [[:db/add obj-eid :object/position position]])))


(defn- set-cast-mode
  "Store the casting mode on an object for use during resolution."
  [db object-id mode]
  (let [obj-eid (q/get-object-eid db object-id)]
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
      (create-spell-stack-item player-id object-id)
      (set-cast-mode object-id mode)
      (increment-storm player-id)
      (maybe-create-storm-trigger player-id object-id)))


(defn cast-spell-mode-with-allocation
  "Cast a spell using explicit mana allocation for generic costs.

   Like cast-spell-mode but uses pay-mana-with-allocation instead of
   the greedy pay-mana. The allocation map specifies how to distribute
   generic mana payment across colors.

   Precondition: Caller should verify can-cast-mode? first."
  [db player-id object-id mode allocation]
  (-> db
      (mana/pay-mana-with-allocation player-id (:mode/mana-cost mode) allocation)
      (pay-additional-costs player-id object-id (:mode/additional-costs mode))
      (zones/move-to-zone object-id :stack)
      (create-spell-stack-item player-id object-id)
      (set-cast-mode object-id mode)
      (increment-storm player-id)
      (maybe-create-storm-trigger player-id object-id)))


(defn cast-spell
  "Cast a spell using the best available mode.

   - Prefers primary mode (from hand with normal cost)
   - Falls back to first available mode (e.g., flashback from graveyard)
   - Pays mana cost and any additional costs
   - Moves card to stack (with stack order for LIFO resolution)
   - Increments storm count
   - Creates storm trigger if spell has :storm keyword

   Caller should verify can-cast? first."
  [db player-id object-id]
  (let [modes (get-casting-modes db player-id object-id)
        primary (first (filter #(= :primary (:mode/id %)) modes))
        ;; Use primary if available, otherwise first available mode (e.g., flashback)
        mode (or primary (first modes))]
    (if mode
      (cast-spell-mode db player-id object-id mode)
      ;; No modes available - shouldn't happen if can-cast? was checked
      db)))


(defn get-active-effects
  "Select which effects to use based on cast mode and card conditions.

   Priority order:
   1. Mode effects (:mode/effects on cast mode) - used for kicked spells
   2. Conditional effects (e.g., threshold) - checked at resolution time
   3. Default effects (:card/effects)

   Cards may have:
   - :card/effects - Default effects (always used if no condition matches)
   - :card/conditional-effects - Map of condition keyword to effects list
     e.g., {:threshold [{:effect/type :add-mana :effect/mana {:black 5}}]}
   - :card/kicked-effects - Effects when cast with kicker (stored in mode)"
  [db player-id card object-id]
  (let [obj (q/get-object db object-id)
        cast-mode (:object/cast-mode obj)
        mode-effects (:mode/effects cast-mode)
        default-effects (:card/effects card)
        conditional-effects (:card/conditional-effects card)]
    (cond
      ;; Use mode effects if present (kicked spells)
      (seq mode-effects)
      mode-effects

      ;; Check conditional effects via unified condition multimethod
      (seq conditional-effects)
      (or (some (fn [[condition-key effects]]
                  (when (conditions/check-condition db player-id
                                                    {:condition/type condition-key})
                    effects))
                conditional-effects)
          default-effects)

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
   - Copies cease to exist when leaving the stack (removed from db)
   - Uses mode's :mode/on-resolve if present (e.g., :exile for flashback)
   - Otherwise: permanents to battlefield, instants/sorceries to graveyard"
  [db player-id object-id]
  (let [obj (q/get-object db object-id)]
    (if (not= :stack (:object/zone obj))
      db  ; No-op if spell not on stack
      (let [card (:object/card obj)
            card-types (:card/types card)
            is-copy (:object/is-copy obj)
            effects-list (get-active-effects db player-id card object-id)
            db-after-effects (reduce (fn [d effect] (effects/execute-effect d player-id effect object-id))
                                     db
                                     (or effects-list []))]
        (if is-copy
          ;; Copies cease to exist when leaving the stack (per MTG rules)
          (zones/remove-object db-after-effects object-id)
          ;; Non-copies go to their destination zone
          (let [cast-mode (:object/cast-mode obj)
                mode-destination (:mode/on-resolve cast-mode)
                destination (cond
                              mode-destination mode-destination
                              (permanent-type? card-types) :battlefield
                              :else :graveyard)]
            (zones/move-to-zone db-after-effects object-id destination)))))))


(defn move-spell-off-stack
  "Remove a spell from stack without resolving (counter, fizzle).

   Copies cease to exist when leaving the stack (removed from db).
   Flashback spells go to exile (:mode/on-resolve :exile).
   All other spells go to graveyard — including permanents whose
   :mode/on-resolve is :battlefield.

   This ensures flashback spells exile when countered, per MTG rules:
   'If the flashback cost was paid, exile this card instead of putting
   it anywhere else any time it would leave the stack.'"
  [db _player-id object-id]
  (let [obj (q/get-object db object-id)]
    (if (not= :stack (:object/zone obj))
      db  ; No-op if not on stack
      (if (:object/is-copy obj)
        ;; Copies cease to exist when leaving the stack
        (zones/remove-object db object-id)
        (let [cast-mode (:object/cast-mode obj)
              mode-destination (:mode/on-resolve cast-mode)
              ;; Only :exile overrides graveyard (flashback rule).
              ;; :battlefield (permanents) goes to graveyard when countered/fizzled.
              destination (if (= :exile mode-destination) :exile :graveyard)]
          (zones/move-to-zone db object-id destination))))))


;; =====================================================
;; Turn Phases
;; =====================================================

(def phases
  "MTG turn phases in order: untap → upkeep → draw → main1 → combat → main2 → end → cleanup"
  [:untap :upkeep :draw :main1 :combat :main2 :end :cleanup])


;; =====================================================
;; Land Plays
;; =====================================================

(defn land-card?
  "Check if an object's card has :land in its types.
   Returns false if object or card not found."
  [db object-id]
  (let [obj (q/get-object db object-id)]
    (when obj
      (let [card (:object/card obj)
            types (:card/types card)]
        (contains? (set types) :land)))))


(defn can-play-land?
  "Check if a player can play a land from their hand.
   Requires:
   - Object is a land card
   - Object is in player's hand
   - Player has land plays remaining
   - Current phase is main1 or main2
   - Stack is empty
   Returns true or false."
  [db player-id object-id]
  (let [game-state (q/get-game-state db)
        phase (:game/phase game-state)
        player-eid (q/get-player-eid db player-id)
        land-plays (d/q '[:find ?plays .
                          :in $ ?pid
                          :where [?e :player/id ?pid]
                          [?e :player/land-plays-left ?plays]]
                        db player-id)
        obj (q/get-object db object-id)
        owner-eid (:db/id (:object/owner obj))]
    (boolean
      (and player-eid
           obj
           (pos? (or land-plays 0))
           (= (:object/zone obj) :hand)
           (= owner-eid player-eid)
           (land-card? db object-id)
           (#{:main1 :main2} phase)
           (q/stack-empty? db)))))
