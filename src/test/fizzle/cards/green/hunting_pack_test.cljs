(ns fizzle.cards.green.hunting-pack-test
  "Tests for Hunting Pack card.

   Hunting Pack: {5}{G}{G} - Instant
   Create a 4/4 green Beast creature token. Storm.

   Key behaviors:
   - Creates a 4/4 green Beast creature token on resolve
   - Storm creates copies, each creating their own 4/4 token
   - Storm with 0 prior spells creates exactly 1 token (no copies)
   - Non-targeted storm: copies created automatically without storm-split selection"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [fizzle.cards.green.hunting-pack :as hunting-pack]
    [fizzle.db.queries :as q]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.test-helpers :as th]))


;; === A. Card Definition Tests ===

(deftest hunting-pack-card-definition-test
  (testing "card has correct oracle properties"
    (let [card hunting-pack/card]
      (is (= :hunting-pack (:card/id card))
          "Card ID should be :hunting-pack")
      (is (= "Hunting Pack" (:card/name card))
          "Card name should match oracle")
      (is (= 7 (:card/cmc card))
          "CMC should be 7")
      (is (= {:colorless 5 :green 2} (:card/mana-cost card))
          "Mana cost should be {5}{G}{G}")
      (is (= #{:green} (:card/colors card))
          "Card should be green")
      (is (= #{:instant} (:card/types card))
          "Card should be an instant")
      (is (= #{:storm} (:card/keywords card))
          "Card should have :storm keyword")
      (is (= "Create a 4/4 green Beast creature token. Storm." (:card/text card))
          "Card text should match oracle")))

  (testing "card has no targeting"
    (is (nil? (:card/targeting hunting-pack/card))
        "Hunting Pack should have no cast-time targeting"))

  (testing "card has correct create-token effect"
    (let [effects (:card/effects hunting-pack/card)]
      (is (= 1 (count effects))
          "Should have exactly one effect")
      (let [effect (first effects)]
        (is (= :create-token (:effect/type effect))
            "Effect type should be :create-token")
        (let [token-def (:effect/token effect)]
          (is (= "Beast" (:token/name token-def))
              "Token name should be Beast")
          (is (= #{:creature} (:token/types token-def))
              "Token types should be #{:creature}")
          (is (= #{:beast} (:token/subtypes token-def))
              "Token subtypes should be #{:beast}")
          (is (= #{:green} (:token/colors token-def))
              "Token colors should be #{:green}")
          (is (= 4 (:token/power token-def))
              "Token power should be 4")
          (is (= 4 (:token/toughness token-def))
              "Token toughness should be 4"))))))


;; === B. Cast-Resolve Happy Path ===

(deftest hunting-pack-creates-beast-token-on-resolve-test
  (testing "Hunting Pack creates a 4/4 green Beast token on resolve"
    (let [db (th/create-test-db {:mana {:colorless 5 :green 2}})
          [db obj-id] (th/add-card-to-zone db :hunting-pack :hand :player-1)
          ;; Verify can cast
          _ (is (rules/can-cast? db :player-1 obj-id)
                "Precondition: can cast with {5}{G}{G}")
          ;; For storm spells, we need to resolve the storm trigger too
          db-cast (rules/cast-spell db :player-1 obj-id)
          ;; First resolve the storm trigger (creates copies — 0 here since first spell)
          storm-items (filter #(= :storm (:stack-item/type %))
                              (q/get-all-stack-items db-cast))
          db-after-storm (:db (th/resolve-top db-cast))
          ;; Now resolve the original spell
          db-resolved (:db (th/resolve-top db-after-storm))
          ;; Count Beast tokens on battlefield for player-1
          battlefield-objects (q/get-objects-in-zone db-resolved :player-1 :battlefield)
          beast-tokens (filter (fn [obj]
                                 (and (:object/is-token obj)
                                      (= "Beast" (:card/name (:object/card obj)))))
                               battlefield-objects)]
      (is (= 1 (count storm-items))
          "Should have a storm trigger on stack")
      (is (= 1 (count beast-tokens))
          "Should have exactly 1 Beast token on battlefield"))))


(deftest hunting-pack-token-has-correct-stats-test
  (testing "Created Beast token has 4/4 stats"
    (let [db (th/create-test-db {:mana {:colorless 5 :green 2}})
          [db obj-id] (th/add-card-to-zone db :hunting-pack :hand :player-1)
          db-cast (rules/cast-spell db :player-1 obj-id)
          db-after-storm (:db (th/resolve-top db-cast))
          db-resolved (:db (th/resolve-top db-after-storm))
          battlefield-objects (q/get-objects-in-zone db-resolved :player-1 :battlefield)
          beast-token (first (filter (fn [obj]
                                       (and (:object/is-token obj)
                                            (= "Beast" (:card/name (:object/card obj)))))
                                     battlefield-objects))]
      (is (= 4 (:object/power beast-token))
          "Beast token should have power 4")
      (is (= 4 (:object/toughness beast-token))
          "Beast token should have toughness 4")
      (is (= #{:creature} (set (:card/types (:object/card beast-token))))
          "Beast token card types should be #{:creature}")
      (is (= #{:beast} (set (:card/subtypes (:object/card beast-token))))
          "Beast token card subtypes should be #{:beast}")
      (is (= #{:green} (set (:card/colors (:object/card beast-token))))
          "Beast token should be green"))))


;; === C. Cannot-Cast Guards ===

(deftest hunting-pack-cannot-cast-without-mana-test
  (testing "Cannot cast Hunting Pack without {5}{G}{G} mana"
    (let [db (th/create-test-db)
          [db obj-id] (th/add-card-to-zone db :hunting-pack :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without mana"))))


(deftest hunting-pack-cannot-cast-without-green-mana-test
  (testing "Cannot cast Hunting Pack with only colorless mana"
    (let [db (th/create-test-db {:mana {:colorless 7}})
          [db obj-id] (th/add-card-to-zone db :hunting-pack :hand :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable without green mana"))))


(deftest hunting-pack-cannot-cast-from-graveyard-test
  (testing "Cannot cast Hunting Pack from graveyard (no flashback)"
    (let [db (th/create-test-db {:mana {:colorless 5 :green 2}})
          [db obj-id] (th/add-card-to-zone db :hunting-pack :graveyard :player-1)]
      (is (false? (rules/can-cast? db :player-1 obj-id))
          "Should not be castable from graveyard"))))


;; === D. Storm Count ===

(deftest hunting-pack-increments-storm-count-test
  (testing "Casting Hunting Pack increments storm count"
    (let [db (th/create-test-db {:mana {:colorless 5 :green 2}})
          [db obj-id] (th/add-card-to-zone db :hunting-pack :hand :player-1)
          storm-before (q/get-storm-count db :player-1)
          db-cast (rules/cast-spell db :player-1 obj-id)]
      (is (= (inc storm-before) (q/get-storm-count db-cast :player-1))
          "Storm count should increment by 1"))))


;; === E. Storm Tests ===

(deftest hunting-pack-storm-zero-previous-spells-creates-one-token-test
  (testing "Hunting Pack as first spell (storm count 0 before cast) creates exactly 1 token"
    (let [db (th/create-test-db {:mana {:colorless 5 :green 2}})
          [db obj-id] (th/add-card-to-zone db :hunting-pack :hand :player-1)
          _ (is (= 0 (q/get-storm-count db :player-1))
                "Precondition: storm count is 0 before cast")
          db-cast (rules/cast-spell db :player-1 obj-id)
          ;; Storm count after cast = 1, so storm trigger copies = 0
          _ (is (= 1 (q/get-storm-count db-cast :player-1))
                "Storm count should be 1 after casting")
          storm-items (filter #(= :storm (:stack-item/type %))
                              (q/get-all-stack-items db-cast))
          storm-trigger (first storm-items)
          _ (is (= 0 (get-in storm-trigger [:stack-item/effects 0 :effect/count]))
                "Storm trigger count should be 0 (no copies)")
          ;; Resolve storm trigger first (0 copies created)
          db-after-storm (:db (th/resolve-top db-cast))
          stack-after-storm (q/get-objects-in-zone db-after-storm :player-1 :stack)
          copies (filter :object/is-copy stack-after-storm)
          _ (is (= 0 (count copies))
                "No copies should be created with storm count 0")
          ;; Resolve original spell
          db-resolved (:db (th/resolve-top db-after-storm))
          battlefield-objects (q/get-objects-in-zone db-resolved :player-1 :battlefield)
          beast-tokens (filter (fn [obj]
                                 (and (:object/is-token obj)
                                      (= "Beast" (:card/name (:object/card obj)))))
                               battlefield-objects)]
      (is (= 1 (count beast-tokens))
          "Should have exactly 1 Beast token (original only, no storm copies)"))))


(deftest hunting-pack-storm-creates-copies-that-each-make-token-test
  (testing "Storm with 2 prior spells creates 2 copies, each making a token (3 total)"
    (let [db (th/create-test-db)
          ;; Cast 2 Dark Rituals to build storm count
          [db dr1-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db-m1 (mana/add-mana db :player-1 {:black 1})
          db1r (th/cast-and-resolve db-m1 :player-1 dr1-id)
          [db2 dr2-id] (th/add-card-to-zone db1r :dark-ritual :hand :player-1)
          db2r (th/cast-and-resolve db2 :player-1 dr2-id)
          _ (is (= 2 (q/get-storm-count db2r :player-1))
                "Precondition: storm count is 2 after 2 rituals")
          ;; Add mana for Hunting Pack and cast it (storm count becomes 3)
          [db3 hp-id] (th/add-card-to-zone db2r :hunting-pack :hand :player-1)
          db3m (mana/add-mana db3 :player-1 {:colorless 5 :green 2})
          db3c (rules/cast-spell db3m :player-1 hp-id)
          _ (is (= 3 (q/get-storm-count db3c :player-1))
                "Storm count should be 3 after casting Hunting Pack")
          ;; Verify storm trigger has count = 2 (spells cast before Hunting Pack)
          storm-items (filter #(= :storm (:stack-item/type %))
                              (q/get-all-stack-items db3c))
          storm-trigger (first storm-items)
          _ (is (= 2 (get-in storm-trigger [:stack-item/effects 0 :effect/count]))
                "Storm trigger count should be 2")
          ;; Resolve storm trigger — non-targeted storm, creates 2 copies directly
          db-after-storm (:db (th/resolve-top db3c))
          stack-objects (q/get-objects-in-zone db-after-storm :player-1 :stack)
          copies (filter :object/is-copy stack-objects)
          _ (is (= 2 (count copies))
                "Should have 2 storm copies on stack")
          ;; Resolve all copies then original
          db-resolve-copies (reduce (fn [d _copy]
                                      (:db (th/resolve-top d)))
                                    db-after-storm
                                    copies)
          db-resolved (:db (th/resolve-top db-resolve-copies))
          ;; Count Beast tokens
          battlefield-objects (q/get-objects-in-zone db-resolved :player-1 :battlefield)
          beast-tokens (filter (fn [obj]
                                 (and (:object/is-token obj)
                                      (= "Beast" (:card/name (:object/card obj)))))
                               battlefield-objects)]
      (is (= 3 (count beast-tokens))
          "Should have 3 Beast tokens (1 from original + 2 from storm copies)"))))


(deftest hunting-pack-storm-copies-cease-to-exist-test
  (testing "Storm copies of Hunting Pack cease to exist after resolution"
    (let [db (th/create-test-db)
          ;; Build storm count = 1
          [db dr-id] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db-m1 (mana/add-mana db :player-1 {:black 1})
          db1r (th/cast-and-resolve db-m1 :player-1 dr-id)
          ;; Cast Hunting Pack (storm count becomes 2)
          [db2 hp-id] (th/add-card-to-zone db1r :hunting-pack :hand :player-1)
          db2m (mana/add-mana db2 :player-1 {:colorless 5 :green 2})
          db2c (rules/cast-spell db2m :player-1 hp-id)
          ;; Resolve storm trigger (creates 1 copy)
          db-after-storm (:db (th/resolve-top db2c))
          stack-objects (q/get-objects-in-zone db-after-storm :player-1 :stack)
          copies (filter :object/is-copy stack-objects)
          copy-id (:object/id (first copies))
          _ (is (= 1 (count copies))
                "Precondition: 1 copy on stack")
          ;; Resolve copy
          db-after-copy (:db (th/resolve-top db-after-storm))]
      ;; Copy should cease to exist (removed from db entirely)
      (is (nil? (q/get-object db-after-copy copy-id))
          "Storm copy should cease to exist after resolution (removed from db)"))))


;; === F. Edge Cases ===

(deftest hunting-pack-token-is-marked-as-token-test
  (testing "Beast token created by Hunting Pack has :object/is-token true"
    (let [db (th/create-test-db {:mana {:colorless 5 :green 2}})
          [db obj-id] (th/add-card-to-zone db :hunting-pack :hand :player-1)
          db-cast (rules/cast-spell db :player-1 obj-id)
          db-after-storm (:db (th/resolve-top db-cast))
          db-resolved (:db (th/resolve-top db-after-storm))
          battlefield-objects (q/get-objects-in-zone db-resolved :player-1 :battlefield)
          beast-token (first (filter (fn [obj]
                                       (and (:object/is-token obj)
                                            (= "Beast" (:card/name (:object/card obj)))))
                                     battlefield-objects))]
      (is (true? (:object/is-token beast-token))
          "Beast token should have :object/is-token true"))))


(deftest hunting-pack-goes-to-graveyard-after-resolve-test
  (testing "Hunting Pack (original) goes to graveyard after resolution"
    (let [db (th/create-test-db {:mana {:colorless 5 :green 2}})
          [db obj-id] (th/add-card-to-zone db :hunting-pack :hand :player-1)
          db-cast (rules/cast-spell db :player-1 obj-id)
          db-after-storm (:db (th/resolve-top db-cast))
          db-resolved (:db (th/resolve-top db-after-storm))]
      (is (= :graveyard (:object/zone (q/get-object db-resolved obj-id)))
          "Hunting Pack should be in graveyard after resolution"))))
