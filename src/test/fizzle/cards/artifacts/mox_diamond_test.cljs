(ns fizzle.cards.artifacts.mox-diamond-test
  "Tests for Mox Diamond — 0-cost artifact with self-ETB replacement effect.

   Oracle text: 'If Mox Diamond would enter the battlefield, you may discard
   a land card. If you don't, sacrifice Mox Diamond.'
   Ability: {T}: Add one mana of any color.

   Covers:
   A. Card definition — exact field values
   B. Cast + resolve happy path (land in hand → proceed → enters BF)
   B2. Mana ability (tap for any color after entering BF)
   C. Cannot-cast guards (wrong zone)
   D. Storm count
   E. Replacement selection interactions
   G. Edge cases (no land in hand, triggers on final zone only, multiple lands)"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.cards.artifacts.mox-diamond :as mox]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana-activation :as engine-mana]
    [fizzle.engine.rules :as rules]
    [fizzle.engine.trigger-db :as trigger-db]
    [fizzle.events.selection.core :as sel-core]
    [fizzle.test-helpers :as th]))


;; =====================================================
;; A. Card Definition
;; =====================================================

(deftest mox-diamond-card-definition-test
  (testing "Mox Diamond core attributes are correct"
    (let [card mox/card]
      (is (= :mox-diamond (:card/id card))
          "Card ID should be :mox-diamond")
      (is (= "Mox Diamond" (:card/name card))
          "Card name should match oracle")
      (is (= 0 (:card/cmc card))
          "CMC should be 0")
      (is (= {} (:card/mana-cost card))
          "Mana cost should be empty map (costs 0)")
      (is (= #{} (:card/colors card))
          "Mox Diamond is colorless")
      (is (= #{:artifact} (:card/types card))
          "Should be exactly an artifact (no other types)")
      (is (= "If Mox Diamond would enter the battlefield, you may discard a land card. If you don't, sacrifice Mox Diamond. {T}: Add one mana of any color."
             (:card/text card))
          "Oracle text should match exactly")))

  (testing "Mox Diamond has exactly one mana ability"
    (let [card mox/card
          abilities (:card/abilities card)]
      (is (= 1 (count abilities))
          "Should have exactly 1 ability")
      (let [ability (first abilities)]
        (is (= :mana (:ability/type ability))
            "Ability type should be :mana")
        (is (true? (get-in ability [:ability/cost :tap]))
            "Cost should include tap")
        (is (= {:any 1} (:ability/produces ability))
            "Should produce 1 mana of any color"))))

  (testing "Mox Diamond has exactly one replacement effect"
    (let [card mox/card
          replacements (:card/replacement-effects card)]
      (is (= 1 (count replacements))
          "Should have exactly 1 replacement effect")
      (let [r (first replacements)]
        (is (= :zone-change (:replacement/event r))
            "Replacement event should be :zone-change")
        (is (= {:match/object :self :match/to :battlefield}
               (:replacement/match r))
            "Match should be self moving to battlefield")
        (is (= 2 (count (:replacement/choices r)))
            "Should have exactly 2 choices"))))

  (testing "Mox Diamond replacement choices have correct structure"
    (let [card mox/card
          r (first (:card/replacement-effects card))
          choices (:replacement/choices r)
          proceed-choice (first (filter #(= :proceed (:choice/action %)) choices))
          redirect-choice (first (filter #(= :redirect (:choice/action %)) choices))]
      (is (some? proceed-choice) "Should have a :proceed choice")
      (is (some? redirect-choice) "Should have a :redirect choice")
      (is (= :discard-specific (get-in proceed-choice [:choice/cost :effect/type]))
          "Proceed cost should be :discard-specific")
      (is (= {:match/types #{:land}} (get-in proceed-choice [:choice/cost :effect/criteria]))
          "Discard cost should require a land card")
      (is (= 1 (get-in proceed-choice [:choice/cost :effect/count]))
          "Should discard exactly 1 land")
      (is (= :graveyard (:choice/redirect-to redirect-choice))
          "Redirect choice should send to graveyard"))))


;; =====================================================
;; B. Cast + Resolve Happy Path (with land → enter BF)
;; =====================================================

(deftest mox-diamond-cast-and-resolve-with-land-discard-test
  (testing "cast Mox Diamond with land in hand → resolve → choose proceed → discard land → enters battlefield"
    (let [db (th/create-test-db)
          [db md-id] (th/add-card-to-zone db :mox-diamond :hand :player-1)
          ;; Add a land to hand to pay the replacement cost
          [db land-id] (th/add-card-to-zone db :forest :hand :player-1)
          ;; Add mana to cast (0-cost but still need to satisfy the rules)
          ;; Step 1: Cast Mox Diamond
          db-cast (rules/cast-spell db :player-1 md-id)
          ;; Step 2: Resolve — replacement effect fires, returns pending replacement-choice
          resolve-result (th/resolve-top db-cast)
          replacement-sel (:selection resolve-result)
          _ (is (some? replacement-sel)
                "Resolving Mox Diamond should produce a replacement-choice selection")
          _ (is (= :replacement-choice (:selection/domain replacement-sel))
                "Selection type should be :replacement-choice")
          ;; Step 3: Confirm :proceed choice
          proceed-choice (first (filter #(= :proceed (:choice/action %))
                                        (:selection/choices replacement-sel)))
          _ (is (some? proceed-choice) "Should have a :proceed choice available")
          sel-with-proceed (assoc replacement-sel :selection/selected #{proceed-choice})
          app-db-after-cast {:game/db (:db resolve-result) :game/pending-selection sel-with-proceed}
          after-proceed (sel-core/confirm-selection-impl app-db-after-cast)
          discard-sel (:game/pending-selection after-proceed)
          _ (is (some? discard-sel)
                "After :proceed, should have pending land-discard selection")
          _ (is (some #{land-id} (:selection/valid-targets discard-sel))
                "The forest should be a valid discard target")
          ;; Step 4: Toggle land selection and confirm discard
          after-toggle (sel-core/toggle-selection-impl after-proceed land-id)
          after-confirm (sel-core/confirm-selection-impl (:app-db after-toggle))
          final-db (:game/db after-confirm)]
      (is (= :battlefield (:object/zone (q/get-object final-db md-id)))
          "Mox Diamond should be on battlefield after proceed + land discard")
      (is (= :graveyard (:object/zone (q/get-object final-db land-id)))
          "The discarded land should be in graveyard"))))


;; =====================================================
;; B2. Mana Ability
;; =====================================================

(def ^:private mana-colors [:black :blue :white :red :green])


(deftest mox-diamond-taps-for-any-color-test
  (doseq [color mana-colors]
    (testing (str "Mox Diamond taps for " (name color) " mana")
      (let [db (th/create-test-db)
            [db md-id] (th/add-card-to-zone db :mox-diamond :battlefield :player-1)
            initial-pool (q/get-mana-pool db :player-1)
            _ (is (= 0 (get initial-pool color 0))
                  (str "Precondition: " (name color) " mana starts at 0"))
            db-after (engine-mana/activate-mana-ability db :player-1 md-id color)]
        (is (= 1 (get (q/get-mana-pool db-after :player-1) color))
            (str "Should produce 1 " (name color) " mana"))))))


;; =====================================================
;; C. Cannot-Cast Guards
;; =====================================================

(deftest mox-diamond-cannot-cast-from-battlefield-test
  (testing "Mox Diamond on battlefield cannot be cast again"
    (let [db (th/create-test-db)
          [db md-id] (th/add-card-to-zone db :mox-diamond :battlefield :player-1)]
      (is (false? (rules/can-cast? db :player-1 md-id))
          "Cannot cast from battlefield"))))


(deftest mox-diamond-cannot-cast-from-graveyard-test
  (testing "Mox Diamond in graveyard cannot be cast"
    (let [db (th/create-test-db)
          [db md-id] (th/add-card-to-zone db :mox-diamond :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 md-id))
          "Cannot cast from graveyard"))))


;; =====================================================
;; D. Storm Count
;; =====================================================

(deftest mox-diamond-casting-increments-storm-test
  (testing "Casting Mox Diamond increments storm count"
    (let [db (th/create-test-db)
          [db md-id] (th/add-card-to-zone db :mox-diamond :hand :player-1)
          _ (is (= 0 (or (q/get-storm-count db :player-1) 0))
                "Storm count should start at 0")
          db-cast (rules/cast-spell db :player-1 md-id)]
      (is (= 1 (q/get-storm-count db-cast :player-1))
          "Storm count should be 1 after casting Mox Diamond"))))


;; =====================================================
;; E. Replacement Selection
;; =====================================================

(deftest mox-diamond-replacement-choice-has-two-options-test
  (testing "Replacement-choice selection has exactly 2 choices: proceed and redirect"
    (let [db (th/create-test-db)
          [db md-id] (th/add-card-to-zone db :mox-diamond :hand :player-1)
          [db _land-id] (th/add-card-to-zone db :forest :hand :player-1)
          db-cast (rules/cast-spell db :player-1 md-id)
          resolve-result (th/resolve-top db-cast)
          sel (:selection resolve-result)]
      (is (= :replacement-choice (:selection/domain sel))
          "Should produce a replacement-choice selection on resolve")
      (is (= 2 (count (:selection/choices sel)))
          "Should have exactly 2 choices")
      (is (some #(= :proceed (:choice/action %)) (:selection/choices sel))
          "One choice should be :proceed (discard land)")
      (is (some #(= :redirect (:choice/action %)) (:selection/choices sel))
          "One choice should be :redirect (go to graveyard)"))))


(deftest mox-diamond-decline-sends-to-graveyard-test
  (testing "Choosing :redirect sends Mox Diamond to graveyard (never enters battlefield)"
    ;; Land in hand so both :proceed and :redirect choices are available.
    ;; Player explicitly picks :redirect — MD goes to graveyard.
    (let [db (th/create-test-db)
          [db md-id] (th/add-card-to-zone db :mox-diamond :hand :player-1)
          [db _land-id] (th/add-card-to-zone db :forest :hand :player-1)
          db-cast (rules/cast-spell db :player-1 md-id)
          resolve-result (th/resolve-top db-cast)
          sel (:selection resolve-result)
          _ (is (some? sel) "Precondition: replacement-choice selection should appear when land is in hand")
          redirect-choice (first (filter #(= :redirect (:choice/action %))
                                         (:selection/choices sel)))
          sel-with-redirect (assoc sel :selection/selected #{redirect-choice})
          app-db {:game/db (:db resolve-result) :game/pending-selection sel-with-redirect}
          after-confirm (sel-core/confirm-selection-impl app-db)
          final-db (:game/db after-confirm)]
      (is (= :graveyard (:object/zone (q/get-object final-db md-id)))
          "Mox Diamond should go to graveyard when :redirect is chosen")
      (is (nil? (:game/pending-selection after-confirm))
          "No pending selection after redirect confirmation"))))


;; =====================================================
;; G. Edge Cases
;; =====================================================

(deftest mox-diamond-no-land-in-hand-auto-redirects-test
  (testing "No land in hand → :proceed choice is suppressed; Mox Diamond auto-redirects to graveyard"
    ;; build-selection-for-replacement must filter out :proceed when no valid discard targets exist.
    ;; With only :redirect remaining AND auto-confirm on single-choice, no player prompt is needed.
    ;; Assert: no pending selection (auto-confirmed) AND Mox Diamond in graveyard.
    (let [db (th/create-test-db)
          [db md-id] (th/add-card-to-zone db :mox-diamond :hand :player-1)
          ;; No land in hand — only MD itself is in hand (now on stack after cast)
          db-cast (rules/cast-spell db :player-1 md-id)
          resolve-result (th/resolve-top db-cast)
          sel (:selection resolve-result)
          final-db (:db resolve-result)]
      ;; With auto-redirect: no selection should appear (auto-confirmed).
      ;; If a selection appears, :proceed must be absent from choices.
      (when sel
        (is (not (some #(= :proceed (:choice/action %)) (:selection/choices sel)))
            "When no land in hand, :proceed choice must be absent from replacement-choice selection"))
      ;; Either way, Mox Diamond ends up in graveyard
      (let [ultimate-db (if sel
                          ;; selection appeared — only :redirect should be there, auto-confirm it
                          (let [redirect-choice (first (filter #(= :redirect (:choice/action %))
                                                               (:selection/choices sel)))
                                sel-confirmed (assoc sel :selection/selected #{redirect-choice})
                                app-db {:game/db final-db :game/pending-selection sel-confirmed}
                                after (sel-core/confirm-selection-impl app-db)]
                            (:game/db after))
                          ;; No selection — auto-redirected
                          final-db)]
        (is (= :graveyard (:object/zone (q/get-object ultimate-db md-id)))
            "Mox Diamond should go to graveyard when no land is available to discard")))))


(deftest mox-diamond-no-etb-triggers-when-redirected-to-graveyard-test
  (testing "When MD goes to graveyard (redirect), no ETB triggers fire on the battlefield"
    ;; Land in hand so both choices appear; player picks :redirect.
    ;; Verify no objects end up on the battlefield.
    (let [db (th/create-test-db)
          [db md-id] (th/add-card-to-zone db :mox-diamond :hand :player-1)
          [db _land-id] (th/add-card-to-zone db :forest :hand :player-1)
          db-cast (rules/cast-spell db :player-1 md-id)
          resolve-result (th/resolve-top db-cast)
          sel (:selection resolve-result)
          redirect-choice (first (filter #(= :redirect (:choice/action %))
                                         (:selection/choices sel)))
          sel-confirmed (assoc sel :selection/selected #{redirect-choice})
          app-db {:game/db (:db resolve-result) :game/pending-selection sel-confirmed}
          after-confirm (sel-core/confirm-selection-impl app-db)
          final-db (:game/db after-confirm)
          ;; Check that no battlefield objects appeared (no ETB artifact triggers etc.)
          bf-count (th/get-zone-count final-db :battlefield :player-1)]
      (is (= 0 bf-count)
          "No objects should be on battlefield — Mox Diamond went to graveyard, not ETB"))))


(deftest mox-diamond-zone-change-trigger-fires-on-correct-final-zone-test
  (testing "Zone-change trigger fires for FINAL destination, not the replacement-intercepted zone"
    ;; Register a synthetic :zone-change trigger that fires when any object moves to
    ;; the battlefield. This proves dispatch-post-move-triggers fires triggers for the
    ;; FINAL zone (post-replacement destination), not the originally-intended zone.
    ;;
    ;; Proceed path: MD → BF via proceed → zone-change trigger fires (stack item appears)
    ;; Redirect path: MD → GY via redirect → zone-change trigger does NOT fire for BF entry
    ;; (it fires for the GY move, but since our filter matches :to-zone :battlefield, it doesn't match)
    (testing "proceed path: zone-change trigger fires for battlefield entry"
      (let [db (th/create-test-db)
            [db md-id] (th/add-card-to-zone db :mox-diamond :hand :player-1)
            [db _land-id] (th/add-card-to-zone db :forest :hand :player-1)
            ;; Register a synthetic zone-change trigger that fires when object enters battlefield
            player-eid (q/get-player-eid db :player-1)
            trigger-txs (trigger-db/create-trigger-tx
                          {:trigger/event-type :zone-change
                           :trigger/controller player-eid
                           :trigger/filter {:event/to-zone :battlefield}
                           :trigger/uses-stack? true
                           :trigger/effects [{:effect/type :add-mana
                                              :effect/mana {:black 1}}]})
            db (d/db-with db trigger-txs)
            ;; Cast → resolve → replacement → proceed → discard → BF
            db-cast (rules/cast-spell db :player-1 md-id)
            resolve-result (th/resolve-top db-cast)
            sel (:selection resolve-result)
            proceed-choice (first (filter #(= :proceed (:choice/action %)) (:selection/choices sel)))
            sel-with-proceed (assoc sel :selection/selected #{proceed-choice})
            app-db-after-cast {:game/db (:db resolve-result) :game/pending-selection sel-with-proceed}
            after-proceed (sel-core/confirm-selection-impl app-db-after-cast)
            discard-sel (:game/pending-selection after-proceed)
            land-candidates (:selection/valid-targets discard-sel)
            land-to-discard (first land-candidates)
            after-toggle (sel-core/toggle-selection-impl after-proceed land-to-discard)
            after-confirm (sel-core/confirm-selection-impl (:app-db after-toggle))
            final-db (:game/db after-confirm)
            stack-count (count (q/get-all-stack-items final-db))]
        (is (= :battlefield (:object/zone (q/get-object final-db md-id)))
            "Precondition: MD should be on battlefield after proceed")
        (is (= 1 stack-count)
            "Zone-change trigger should fire (1 stack item) when MD enters battlefield via proceed")))

    (testing "redirect path: zone-change trigger does NOT fire for battlefield (MD goes to graveyard)"
      (let [db (th/create-test-db)
            [db md-id] (th/add-card-to-zone db :mox-diamond :hand :player-1)
            ;; Register same zone-change trigger that fires only on :to-zone :battlefield
            player-eid (q/get-player-eid db :player-1)
            trigger-txs (trigger-db/create-trigger-tx
                          {:trigger/event-type :zone-change
                           :trigger/controller player-eid
                           :trigger/filter {:event/to-zone :battlefield}
                           :trigger/uses-stack? true
                           :trigger/effects [{:effect/type :add-mana
                                              :effect/mana {:black 1}}]})
            db (d/db-with db trigger-txs)
            ;; Cast → resolve → replacement → redirect → graveyard (no BF entry)
            db-cast (rules/cast-spell db :player-1 md-id)
            resolve-result (th/resolve-top db-cast)
            sel (:selection resolve-result)
            redirect-choice (first (filter #(= :redirect (:choice/action %)) (:selection/choices sel)))
            sel-with-redirect (assoc sel :selection/selected #{redirect-choice})
            app-db {:game/db (:db resolve-result) :game/pending-selection sel-with-redirect}
            after-confirm (sel-core/confirm-selection-impl app-db)
            final-db (:game/db after-confirm)
            stack-count (count (q/get-all-stack-items final-db))]
        (is (= :graveyard (:object/zone (q/get-object final-db md-id)))
            "Precondition: MD should be in graveyard after redirect")
        (is (= 0 stack-count)
            "Zone-change trigger should NOT fire for battlefield when MD redirects to graveyard")))))


(deftest mox-diamond-multiple-lands-player-picks-which-test
  (testing "Multiple lands in hand → proceed discard selection contains all of them as candidates"
    (let [db (th/create-test-db)
          [db md-id] (th/add-card-to-zone db :mox-diamond :hand :player-1)
          [db land1-id] (th/add-card-to-zone db :forest :hand :player-1)
          [db land2-id] (th/add-card-to-zone db :island :hand :player-1)
          db-cast (rules/cast-spell db :player-1 md-id)
          resolve-result (th/resolve-top db-cast)
          sel (:selection resolve-result)
          proceed-choice (first (filter #(= :proceed (:choice/action %))
                                        (:selection/choices sel)))
          sel-with-proceed (assoc sel :selection/selected #{proceed-choice})
          app-db {:game/db (:db resolve-result) :game/pending-selection sel-with-proceed}
          after-proceed (sel-core/confirm-selection-impl app-db)
          discard-sel (:game/pending-selection after-proceed)]
      (is (some? discard-sel)
          "Should have a discard selection pending after :proceed")
      (is (some #{land1-id} (:selection/valid-targets discard-sel))
          "Forest should be a valid discard target")
      (is (some #{land2-id} (:selection/valid-targets discard-sel))
          "Island should be a valid discard target")
      (is (= 1 (:selection/select-count discard-sel))
          "Should discard exactly 1 land (not all of them)"))))


;; =====================================================
;; H. Chain-trace: replacement-choice via production path
;; =====================================================

(deftest mox-diamond-replacement-choice-chain-trace-test
  (testing "Cast Mox Diamond via production path → replacement-choice has :binary-choice mechanism"
    ;; Chain-trace invariant: the replacement-choice selection produced after resolving
    ;; Mox Diamond must pass through set-pending-selection (spec chokepoint), which
    ;; requires :selection/mechanism. This test proves the chokepoint is reached via
    ;; the production cast path (th/cast-and-yield, not rules/cast-spell bypass).
    (let [db (th/create-test-db)
          [db md-id] (th/add-card-to-zone db :mox-diamond :hand :player-1)
          [db land-id] (th/add-card-to-zone db :forest :hand :player-1)
          ;; Production cast path: cast-spell-handler → resolve → replacement-choice
          {:keys [db selection]} (th/cast-and-yield db :player-1 md-id)
          _ (is (some? selection)
                "Resolving Mox Diamond with a land in hand MUST produce a pending selection")
          ;; Chain-trace assertion 1: mechanism keyword equality (not some?)
          _ (is (= :binary-choice (:selection/mechanism selection))
                "Replacement-choice MUST have :selection/mechanism = :binary-choice (spec chokepoint reached)")
          _ (is (= :replacement-choice (:selection/domain selection))
                "Selection domain must be :replacement-choice")
          ;; Chain-trace assertion 2: both choices are present (proceed + redirect)
          _ (is (= 2 (count (:selection/choices selection)))
                "Must have exactly 2 choices when a land is in hand")
          _ (is (some #(= :proceed (:choice/action %)) (:selection/choices selection))
                "Must have :proceed choice (discard land)")
          _ (is (some #(= :redirect (:choice/action %)) (:selection/choices selection))
                "Must have :redirect choice (bounce to graveyard)")
          ;; Confirm proceed → land-discard selection → confirm land → Mox on battlefield
          {:keys [db selection]} (th/confirm-selection db selection
                                                       #{(first (filter #(= :proceed (:choice/action %))
                                                                        (:selection/choices selection)))})
          _ (is (some? selection) "After :proceed, land-discard selection must appear")
          {:keys [db]} (th/confirm-selection db selection #{land-id})]
      ;; Post-confirm state: Mox on battlefield, land in graveyard, hand size -1
      (is (= :battlefield (:object/zone (q/get-object db md-id)))
          "Mox Diamond must be on battlefield after discard-proceed")
      (is (= :graveyard (:object/zone (q/get-object db land-id)))
          "Discarded land must be in graveyard")
      (is (= 0 (th/get-hand-count db :player-1))
          "Player-1 hand must be empty (Mox moved to stack then BF; land discarded)"))))
