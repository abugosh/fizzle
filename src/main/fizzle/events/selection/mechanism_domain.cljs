(ns fizzle.events.selection.mechanism-domain
  "Canonical mapping from every live :selection/type value to its
   (mechanism, domain) tuple as defined by ADR-030.

   This namespace is pure data — no runtime logic. It is consumed by:
     - Task 2's compat adapter (set-pending-selection enrichment)
     - Task 4's dispatch switch (execute-confirmed-selection and views)

   Mechanism alphabet (six confirmed, two new — see ADR-030 addendum):
     :pick-from-zone    Select N cards/objects from a zone and move/act on them.
                        Replaces the :zone-pick hierarchy parent.
     :reorder           Assign cards into ordered positions (piles, top/bottom).
     :accumulate        Distribute/increment a numeric value via stepper controls.
     :allocate-resource Assign mana from a pool to typed cost slots.
     :n-slot-targeting  Fill N target slots from a valid-targets set (objects/players).
     :pick-mode         Choose one named option from a finite list of non-card options.
                        NEW: covers spell modes and land-type choices. Justified below.
     :binary-choice     Choose one action from a small fixed set of action keywords.
                        NEW: covers unless-pay (pay/decline) and replacement-choice
                        (proceed/redirect). Distinguished from :pick-from-zone because
                        the candidates are action keywords, not card/object identifiers.

   :zone-pick resolution: :zone-pick was the hierarchy parent keyword and the value
   of :selection/pattern on zone-pick selections (retired in task 5, fizzle-nayb).
   It is NOT a mechanism in the new system — the mechanism is :pick-from-zone.")


(def type->mechanism-domain
  "Locked mapping from every live :selection/type value to its
   {:mechanism :selection/mechanism-kw :domain :selection/domain-kw} tuple.

   33 entries — one per selection-type-spec defmethod in events/selection/spec.cljs.
   Add new entries here when adding new :selection/type values (per ADR-030 policy:
   all new work uses mechanism + domain; never add bare :selection/type values)."

  {;; ======================================================
   ;; :pick-from-zone — select N cards from a zone and act on them
   ;; Replaces the :zone-pick hierarchy parent (retired :selection/pattern in fizzle-nayb).
   ;; ======================================================

   :discard
   {:mechanism :pick-from-zone
    :domain    :discard}

   :graveyard-return
   {:mechanism :pick-from-zone
    :domain    :graveyard-return}

   :shuffle-from-graveyard-to-library
   {:mechanism :pick-from-zone
    :domain    :shuffle-to-library}

   :hand-reveal-discard
   {:mechanism :pick-from-zone
    :domain    :revealed-hand-discard}

   :chain-bounce
   {:mechanism :pick-from-zone
    :domain    :chain-bounce}

   :chain-bounce-target
   {:mechanism :pick-from-zone
    :domain    :chain-bounce-target}

   :untap-lands
   {:mechanism :pick-from-zone
    :domain    :untap-lands}

   ;; Pre-cast cost zone-picks — pick from a zone as payment
   :discard-specific-cost
   {:mechanism :pick-from-zone
    :domain    :discard-cost}

   :return-land-cost
   {:mechanism :pick-from-zone
    :domain    :return-land-cost}

   :sacrifice-permanent-cost
   {:mechanism :pick-from-zone
    :domain    :sacrifice-cost}

   :exile-cards-cost
   {:mechanism :pick-from-zone
    :domain    :exile-cost}

   ;; Library interactions — also pick-from-zone
   :tutor
   {:mechanism :pick-from-zone
    :domain    :tutor}

   :peek-and-select
   {:mechanism :pick-from-zone
    :domain    :peek-and-select}

   :pile-choice
   {:mechanism :pick-from-zone
    :domain    :pile-choice}


   ;; ======================================================
   ;; :reorder — sort/assign cards into ordered positions
   ;; ======================================================

   :scry
   {:mechanism :reorder
    :domain    :scry}

   :peek-and-reorder
   {:mechanism :reorder
    :domain    :peek-and-reorder}

   :order-bottom
   {:mechanism :reorder
    :domain    :order-bottom}

   :order-top
   {:mechanism :reorder
    :domain    :order-top}


   ;; ======================================================
   ;; :accumulate — distribute/increment a numeric value via stepper
   ;; ======================================================

   :storm-split
   {:mechanism :accumulate
    :domain    :storm-split}

   :x-mana-cost
   {:mechanism :accumulate
    :domain    :x-mana-cost}

   :pay-x-life
   {:mechanism :accumulate
    :domain    :pay-x-life}


   ;; ======================================================
   ;; :allocate-resource — assign mana from pool to typed cost slots
   ;; Distinguished from :accumulate: mana-allocation distributes
   ;; across color-keyed slots simultaneously, not a single numeric stepper.
   ;; ======================================================

   :mana-allocation
   {:mechanism :allocate-resource
    :domain    :mana-allocation}


   ;; ======================================================
   ;; :n-slot-targeting — fill N target slots from a valid-targets set
   ;; Targets are card/object UUIDs or player-ids, not action keywords.
   ;; ======================================================

   :player-target
   {:mechanism :n-slot-targeting
    :domain    :player-target}

   :cast-time-targeting
   {:mechanism :n-slot-targeting
    :domain    :cast-time-targeting}

   :ability-cast-targeting
   {:mechanism :n-slot-targeting
    :domain    :ability-cast-targeting}

   :ability-targeting
   {:mechanism :n-slot-targeting
    :domain    :ability-targeting}

   :select-attackers
   {:mechanism :n-slot-targeting
    :domain    :select-attackers}

   :assign-blockers
   {:mechanism :n-slot-targeting
    :domain    :assign-blockers}


   ;; ======================================================
   ;; :pick-mode — choose one named option from a finite non-card list
   ;; NEW mechanism. Options are mode descriptor maps or type keywords,
   ;; not card/object IDs from a zone. View component is always a
   ;; button-list modal, never a card-picker zone-pick modal.
   ;; ======================================================

   :spell-mode
   {:mechanism :pick-mode
    :domain    :spell-mode}

   :land-type-source
   {:mechanism :pick-mode
    :domain    :land-type-source}

   :land-type-target
   {:mechanism :pick-mode
    :domain    :land-type-target}


   ;; ======================================================
   ;; :binary-choice — choose one action from a small fixed action-keyword set
   ;; NEW mechanism. valid-targets holds action keywords (:pay/:decline,
   ;; :proceed/:redirect), NOT card/object IDs. The confirming player
   ;; picks an intent, not an object. Domain executor interprets the
   ;; chosen action keyword and routes accordingly.
   ;; ======================================================

   :unless-pay
   {:mechanism :binary-choice
    :domain    :unless-pay}

   :replacement-choice
   {:mechanism :binary-choice
    :domain    :replacement-choice}})
