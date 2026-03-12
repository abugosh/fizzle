(ns fizzle.sharing.decoder-test
  "Tests for snapshot decoder: base64url → binary → portable map.

   Primary verification: encode→decode round-trip produces identical portable map.
   Secondary: error handling for invalid/truncated/unknown-version input."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [clojure.string :as string]
    [fizzle.db.queries :as q]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.mana :as mana]
    [fizzle.engine.rules :as rules]
    [fizzle.sharing.bits :as bits]
    [fizzle.sharing.decoder :as decoder]
    [fizzle.sharing.encoder :as encoder]
    [fizzle.sharing.extractor :as extractor]
    [fizzle.test-helpers :as th]))


;; ---------------------------------------------------------------------------
;; Helpers

(defn- encode-then-decode
  [db]
  (let [state   (extractor/extract db)
        encoded (encoder/encode-snapshot state)]
    {:state   state
     :encoded encoded
     :decoded (decoder/decode-snapshot encoded)}))


;; ---------------------------------------------------------------------------
;; A. Output shape

(deftest decode-returns-map-test
  (testing "decode-snapshot returns a map for valid input"
    (let [{:keys [decoded]} (encode-then-decode (-> (th/create-test-db) (th/add-opponent)))]
      (is (map? decoded)
          "decode-snapshot should return a map"))))


(deftest decode-has-required-keys-test
  (testing "decoded map has all top-level keys"
    (let [{:keys [decoded]} (encode-then-decode (-> (th/create-test-db) (th/add-opponent)))]
      (is (contains? decoded :game/turn))
      (is (contains? decoded :game/phase))
      (is (contains? decoded :game/active-player))
      (is (contains? decoded :game/priority))
      (is (contains? decoded :players))
      (is (contains? decoded :stack)))))


;; ---------------------------------------------------------------------------
;; B. Round-trip: game state fields

(deftest roundtrip-game-turn-test
  (testing "game/turn survives encode→decode"
    (let [{:keys [state decoded]} (encode-then-decode (-> (th/create-test-db) (th/add-opponent)))]
      (is (= (:game/turn state) (:game/turn decoded))))))


(deftest roundtrip-game-phase-test
  (testing "game/phase survives encode→decode"
    (let [{:keys [state decoded]} (encode-then-decode (-> (th/create-test-db) (th/add-opponent)))]
      (is (= (:game/phase state) (:game/phase decoded))))))


(deftest roundtrip-game-step-nil-test
  (testing "nil game/step survives encode→decode as nil"
    (let [{:keys [state decoded]} (encode-then-decode (-> (th/create-test-db) (th/add-opponent)))]
      (is (= (:game/step state) (:game/step decoded))))))


(deftest roundtrip-active-player-test
  (testing "game/active-player survives encode→decode"
    (let [{:keys [state decoded]} (encode-then-decode (-> (th/create-test-db) (th/add-opponent)))]
      (is (= (:game/active-player state) (:game/active-player decoded))))))


(deftest roundtrip-priority-test
  (testing "game/priority survives encode→decode"
    (let [{:keys [state decoded]} (encode-then-decode (-> (th/create-test-db) (th/add-opponent)))]
      (is (= (:game/priority state) (:game/priority decoded))))))


;; ---------------------------------------------------------------------------
;; C. Round-trip: player fields

(deftest roundtrip-player-life-test
  (testing "player/life survives encode→decode"
    (let [db    (-> (th/create-test-db {:life 13}) (th/add-opponent))
          state (extractor/extract db)
          rt    (decoder/decode-snapshot (encoder/encode-snapshot state))]
      (is (= 13 (get-in rt [:players :player-1 :player/life]))))))


(deftest roundtrip-player-storm-count-test
  (testing "player/storm-count survives encode→decode"
    (let [db    (-> (th/create-test-db {:storm-count 7}) (th/add-opponent))
          state (extractor/extract db)
          rt    (decoder/decode-snapshot (encoder/encode-snapshot state))]
      (is (= 7 (get-in rt [:players :player-1 :player/storm-count]))))))


(deftest roundtrip-player-mana-pool-test
  (testing "player/mana-pool survives encode→decode"
    (let [db    (-> (mana/add-mana (th/create-test-db) :player-1 {:black 3 :blue 1})
                    (th/add-opponent))
          state (extractor/extract db)
          rt    (decoder/decode-snapshot (encoder/encode-snapshot state))
          pool  (get-in rt [:players :player-1 :player/mana-pool])]
      (is (= 3 (:black pool)))
      (is (= 1 (:blue pool))))))


(deftest roundtrip-player-land-plays-test
  (testing "player/land-plays-left survives encode→decode"
    (let [{:keys [state decoded]} (encode-then-decode (-> (th/create-test-db) (th/add-opponent)))]
      (is (= (get-in state  [:players :player-1 :player/land-plays-left])
             (get-in decoded [:players :player-1 :player/land-plays-left]))))))


;; ---------------------------------------------------------------------------
;; D. Round-trip: zones

(deftest roundtrip-empty-zones-test
  (testing "empty zones decode as empty vectors"
    (let [{:keys [decoded]} (encode-then-decode (-> (th/create-test-db) (th/add-opponent)))
          p1 (get-in decoded [:players :player-1])]
      (is (= [] (:hand p1)))
      (is (= [] (:graveyard p1)))
      (is (= [] (:battlefield p1)))
      (is (= [] (:exile p1)))
      (is (= [] (:library p1))))))


(deftest roundtrip-hand-card-ids-test
  (testing "hand card :card/id keywords survive encode→decode"
    (let [db   (-> (th/create-test-db) (th/add-opponent))
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :brain-freeze :hand :player-1)
          state (extractor/extract db'')
          rt    (decoder/decode-snapshot (encoder/encode-snapshot state))
          hand  (get-in rt [:players :player-1 :hand])]
      (is (= 2 (count hand)))
      (is (= #{:dark-ritual :brain-freeze}
             (set (map :card/id hand)))))))


(deftest roundtrip-graveyard-card-ids-test
  (testing "graveyard card :card/id keywords survive encode→decode"
    (let [db   (-> (th/create-test-db) (th/add-opponent))
          [db' _] (th/add-card-to-zone db :lotus-petal :graveyard :player-1)
          state (extractor/extract db')
          rt    (decoder/decode-snapshot (encoder/encode-snapshot state))]
      (is (= [:lotus-petal]
             (map :card/id (get-in rt [:players :player-1 :graveyard])))))))


(deftest roundtrip-library-order-test
  (testing "library card order survives encode→decode"
    (let [db   (-> (th/create-test-db) (th/add-opponent))
          [db' _] (th/add-cards-to-library db [:dark-ritual :brain-freeze :lotus-petal] :player-1)
          state (extractor/extract db')
          rt    (decoder/decode-snapshot (encoder/encode-snapshot state))
          lib   (get-in rt [:players :player-1 :library])]
      (is (= [:dark-ritual :brain-freeze :lotus-petal]
             (map :card/id lib))))))


(deftest roundtrip-battlefield-tapped-test
  (testing "battlefield tapped state survives encode→decode"
    (let [db   (-> (th/create-test-db) (th/add-opponent))
          [db' _] (th/add-card-to-zone db :city-of-brass :battlefield :player-1)
          state (extractor/extract db')
          rt    (decoder/decode-snapshot (encoder/encode-snapshot state))
          bf    (get-in rt [:players :player-1 :battlefield])]
      (is (= 1 (count bf)))
      (is (= :city-of-brass (:card/id (first bf))))
      (is (false? (:object/tapped (first bf)))))))


(deftest roundtrip-battlefield-counters-test
  (testing "battlefield counters survive encode→decode"
    (let [db   (-> (th/create-test-db) (th/add-opponent))
          [db' _] (th/add-card-to-zone db :gemstone-mine :battlefield :player-1)
          ;; Manually inject counters into the extractor's portable map
          state     (extractor/extract db')
          state-ctr (update-in state [:players :player-1 :battlefield 0]
                               assoc :object/counters {:mining 3})
          rt    (decoder/decode-snapshot (encoder/encode-snapshot state-ctr))
          bf    (get-in rt [:players :player-1 :battlefield])]
      (is (= {:mining 3} (:object/counters (first bf)))))))


;; ---------------------------------------------------------------------------
;; E. Round-trip: stack

(deftest roundtrip-empty-stack-test
  (testing "empty stack decodes as empty vector"
    (let [{:keys [decoded]} (encode-then-decode (-> (th/create-test-db) (th/add-opponent)))]
      (is (= [] (:stack decoded))))))


(deftest roundtrip-stack-item-type-test
  (testing "stack item type survives encode→decode after casting"
    (let [db    (-> (th/create-test-db) (th/add-opponent))
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          db-m  (mana/add-mana db' :player-1 {:black 1})
          hand  (q/get-hand db-m :player-1)
          db-c  (rules/cast-spell db-m :player-1 (:object/id (first hand)))
          state (extractor/extract db-c)
          rt    (decoder/decode-snapshot (encoder/encode-snapshot state))
          stk   (:stack rt)]
      (is (pos? (count stk))
          "stack should have at least one item")
      (is (= :spell (:stack-item/type (first stk)))
          "stack item type should be :spell")
      (is (= :player-1 (:stack-item/controller (first stk)))
          "controller should be :player-1"))))


(deftest roundtrip-targeted-spell-targets-test
  (testing "targeted spell targets (EDN blob) survive encode→decode"
    (let [db     (-> (th/create-test-db {:mana {:blue 1 :colorless 1}}) (th/add-opponent))
          [db' _] (th/add-card-to-zone db :brain-freeze :hand :player-1)
          hand   (q/get-hand db' :player-1)
          ;; Brain Freeze targets players by player-id keyword
          db-c   (th/cast-with-target db' :player-1 (:object/id (first hand)) :player-2)
          state  (extractor/extract db-c)
          rt     (decoder/decode-snapshot (encoder/encode-snapshot state))
          stk    (:stack rt)
          ;; The spell item may not be first due to LIFO ordering with storm trigger
          spell-item (first (filter #(= :spell (:stack-item/type %)) stk))]
      (is (pos? (count stk))
          "stack should have items after casting Brain Freeze")
      (is (some? spell-item)
          "stack should contain a :spell type item")
      (is (map? (:stack-item/targets spell-item))
          "targets map should be decoded from EDN blob")
      (is (pos? (count (:stack-item/targets spell-item)))
          "targets map should be non-empty"))))


;; ---------------------------------------------------------------------------
;; E2. Round-trip: player grants

(deftest roundtrip-player-grants-test
  (testing "non-empty player grants survive encode→decode"
    (let [db      (-> (th/create-test-db) (th/add-opponent))
          grant   {:grant/id :test-grant :grant/type :add-restriction
                   :grant/data {:restriction/type :no-land-play}}
          db-with-grant (grants/add-player-grant db :player-1 grant)
          state   (extractor/extract db-with-grant)
          rt      (decoder/decode-snapshot (encoder/encode-snapshot state))
          p1-grants (get-in rt [:players :player-1 :player/grants])]
      (is (= 1 (count p1-grants))
          "player-1 should have 1 grant after round-trip")
      (is (= :test-grant (:grant/id (first p1-grants)))
          "grant id should be preserved"))))


(deftest roundtrip-empty-player-grants-test
  (testing "empty player grants decode as empty vector"
    (let [{:keys [decoded]} (encode-then-decode (-> (th/create-test-db) (th/add-opponent)))]
      (is (= [] (get-in decoded [:players :player-1 :player/grants]))
          "empty grants should decode as []")
      (is (= [] (get-in decoded [:players :player-2 :player/grants]))
          "empty grants for p2 should decode as []"))))


;; ---------------------------------------------------------------------------
;; F. Two-player round-trip

(deftest roundtrip-two-players-test
  (testing "both players survive encode→decode"
    (let [db   (-> (th/create-test-db) (th/add-opponent))
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          [db'' _] (th/add-card-to-zone db' :counterspell :hand :player-2)
          state (extractor/extract db'')
          rt    (decoder/decode-snapshot (encoder/encode-snapshot state))]
      (is (= 2 (count (:players rt))))
      (is (= [:dark-ritual]
             (map :card/id (get-in rt [:players :player-1 :hand]))))
      (is (= [:counterspell]
             (map :card/id (get-in rt [:players :player-2 :hand])))))))


;; ---------------------------------------------------------------------------
;; G. Error handling

(deftest decode-empty-string-returns-error-test
  (testing "empty string returns error map"
    (let [result (decoder/decode-snapshot "")]
      (is (map? result))
      (is (contains? result :error)))))


(deftest decode-invalid-base64url-returns-error-test
  (testing "invalid base64url characters return error map"
    (let [result (decoder/decode-snapshot "!!!invalid!!!")]
      (is (map? result))
      (is (contains? result :error))
      (is (string? (:error result))))))


(deftest decode-unknown-version-returns-error-test
  (testing "unknown version byte returns error map"
    ;; Manually craft a byte with version=15 (unknown)
    (let [;; First byte: version(4 bits)=15, flags(4 bits)=0 → 0xF0
          bad-byte (js/Uint8Array. #js [0xF0])
          encoded  (bits/base64url-encode bad-byte)
          result   (decoder/decode-snapshot encoded)]
      (is (map? result))
      (is (contains? result :error))
      (is (string/includes? (:error result) "version")))))


(deftest decode-truncated-data-returns-error-test
  (testing "truncated payload returns error map"
    ;; Only 1 byte — not enough for the full header
    (let [bad-byte (js/Uint8Array. #js [0x10])  ; version=1, flags=0
          encoded  (bits/base64url-encode bad-byte)
          result   (decoder/decode-snapshot encoded)]
      (is (map? result))
      (is (contains? result :error)))))


(deftest decode-does-not-throw-test
  (testing "decode-snapshot never throws, always returns map"
    (doseq [input ["" "abc" "!!" "AAAA" "____"
                   (bits/base64url-encode (js/Uint8Array. #js [0]))]]
      (let [result (decoder/decode-snapshot input)]
        (is (map? result)
            (str "should return map for input: " input))))))
