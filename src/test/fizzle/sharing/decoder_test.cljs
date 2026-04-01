(ns fizzle.sharing.decoder-test
  "Tests for snapshot decoder: base64url → binary → portable map.

   Primary verification: encode→decode round-trip produces identical portable map.
   Secondary: error handling for invalid/truncated/unknown-version input."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [clojure.string :as string]
    [fizzle.engine.grants :as grants]
    [fizzle.engine.mana :as mana]
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
      (is (contains? decoded :players)))))


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
;; E. Round-trip: player grants

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
;; E2. Round-trip: object grants (non-battlefield zones)

(deftest roundtrip-graveyard-grants-test
  (testing "grants on graveyard objects survive encode→decode (e.g. Recoup flashback)"
    (let [db    (-> (th/create-test-db) (th/add-opponent))
          [db' _] (th/add-card-to-zone db :ill-gotten-gains :graveyard :player-1)
          state (extractor/extract db')
          grant {:grant/id :granted-flashback
                 :grant/type :alternate-cost
                 :grant/data {:alternate/id :granted-flashback
                              :alternate/zone :graveyard
                              :alternate/cost {:black 1 :red 1}}}
          state-with-grant (update-in state [:players :player-1 :graveyard 0]
                                      assoc :object/grants [grant])
          rt    (decoder/decode-snapshot (encoder/encode-snapshot state-with-grant))
          gy    (get-in rt [:players :player-1 :graveyard])]
      (is (= 1 (count gy)))
      (is (= :ill-gotten-gains (:card/id (first gy))))
      (is (= [grant] (:object/grants (first gy)))
          "graveyard object grants should survive round-trip"))))


(deftest roundtrip-hand-grants-test
  (testing "grants on hand objects survive encode→decode"
    (let [db    (-> (th/create-test-db) (th/add-opponent))
          [db' _] (th/add-card-to-zone db :dark-ritual :hand :player-1)
          state (extractor/extract db')
          grant {:grant/id :test :grant/type :test-type}
          state-with-grant (update-in state [:players :player-1 :hand 0]
                                      assoc :object/grants [grant])
          rt    (decoder/decode-snapshot (encoder/encode-snapshot state-with-grant))
          hand  (get-in rt [:players :player-1 :hand])]
      (is (= [grant] (:object/grants (first hand)))
          "hand object grants should survive round-trip"))))


(deftest roundtrip-no-grants-omits-key-test
  (testing "objects without grants decode without :object/grants key"
    (let [db    (-> (th/create-test-db) (th/add-opponent))
          [db' _] (th/add-card-to-zone db :dark-ritual :graveyard :player-1)
          state (extractor/extract db')
          rt    (decoder/decode-snapshot (encoder/encode-snapshot state))
          gy    (get-in rt [:players :player-1 :graveyard])]
      (is (not (contains? (first gy) :object/grants))
          "objects without grants should not have :object/grants key"))))


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


;; ---------------------------------------------------------------------------
;; H. Round-trip: player metadata (bot-archetype, is-opponent, max-hand-size)

(deftest roundtrip-bot-archetype-goldfish-test
  (testing "player/bot-archetype :goldfish survives encode→decode on player-2"
    (let [db    (-> (th/create-test-db) (th/add-opponent {:bot-archetype :goldfish}))
          state (extractor/extract db)
          rt    (decoder/decode-snapshot (encoder/encode-snapshot state))]
      (is (= :goldfish (get-in rt [:players :player-2 :player/bot-archetype]))
          "bot-archetype :goldfish should survive round-trip on player-2"))))


(deftest roundtrip-bot-archetype-burn-test
  (testing "player/bot-archetype :burn survives encode→decode on player-2"
    (let [db    (-> (th/create-test-db) (th/add-opponent {:bot-archetype :burn}))
          state (extractor/extract db)
          rt    (decoder/decode-snapshot (encoder/encode-snapshot state))]
      (is (= :burn (get-in rt [:players :player-2 :player/bot-archetype]))
          "bot-archetype :burn should survive round-trip on player-2"))))


(deftest roundtrip-is-opponent-test
  (testing "player/is-opponent true survives encode→decode on player-2, absent on player-1"
    (let [db    (-> (th/create-test-db) (th/add-opponent {:bot-archetype :goldfish}))
          state (extractor/extract db)
          rt    (decoder/decode-snapshot (encoder/encode-snapshot state))]
      (is (= true (get-in rt [:players :player-2 :player/is-opponent]))
          "player-2 should have :player/is-opponent true after round-trip")
      (is (not (contains? (get-in rt [:players :player-1]) :player/is-opponent))
          "player-1 should NOT have :player/is-opponent key (absent, not nil)"))))


(deftest roundtrip-max-hand-size-test
  (testing "player/max-hand-size survives encode→decode on both players"
    (let [db    (-> (th/create-test-db) (th/add-opponent {:bot-archetype :goldfish}))
          state (extractor/extract db)
          rt    (decoder/decode-snapshot (encoder/encode-snapshot state))]
      (is (= 7 (get-in rt [:players :player-1 :player/max-hand-size]))
          "player-1 max-hand-size should be 7 after round-trip")
      (is (= 7 (get-in rt [:players :player-2 :player/max-hand-size]))
          "player-2 max-hand-size should be 7 after round-trip"))))


(deftest roundtrip-player1-no-bot-archetype-test
  (testing "player-1 has no :player/bot-archetype key after round-trip (absent, not nil)"
    (let [db    (-> (th/create-test-db) (th/add-opponent {:bot-archetype :goldfish}))
          state (extractor/extract db)
          rt    (decoder/decode-snapshot (encoder/encode-snapshot state))]
      (is (not (contains? (get-in rt [:players :player-1]) :player/bot-archetype))
          "player-1 should NOT have :player/bot-archetype key"))))


(deftest roundtrip-both-grants-and-metadata-test
  (testing "encode/decode correctly handles both player grants AND metadata flags"
    (let [db      (-> (th/create-test-db) (th/add-opponent {:bot-archetype :goldfish}))
          grant   {:grant/id :test-grant :grant/type :add-restriction
                   :grant/data {:restriction/type :no-land-play}}
          db-with-grant (grants/add-player-grant db :player-1 grant)
          state   (extractor/extract db-with-grant)
          rt      (decoder/decode-snapshot (encoder/encode-snapshot state))]
      (is (= 1 (count (get-in rt [:players :player-1 :player/grants])))
          "player-1 grant should survive round-trip when metadata also present")
      (is (= :goldfish (get-in rt [:players :player-2 :player/bot-archetype]))
          "player-2 bot-archetype should survive round-trip when grants also present"))))


(deftest backward-compat-v1-snapshot-no-metadata-test
  (testing "v1 snapshot encoded without metadata flag decodes without error and uses defaults"
    ;; Simulate a v1 snapshot without flag bit 2 by encoding with a state
    ;; that currently produces no metadata (plain add-opponent with no bot-archetype).
    ;; After the implementation lands, we verify that snapshots encoded before this
    ;; change still decode fine. We test by building a snapshot state map that has
    ;; no :player/is-opponent or :player/bot-archetype and verifying decode still works.
    ;; We do this by manually crafting the state map to have no metadata-capable fields.
    (let [db    (-> (th/create-test-db) (th/add-opponent))
          state (extractor/extract db)
          ;; Remove any metadata fields to simulate a state that would produce
          ;; no metadata (or an old v1 snapshot with no flag bit 2)
          state-no-meta (-> state
                            (update-in [:players :player-2] dissoc
                                       :player/bot-archetype :player/is-opponent))
          encoded (encoder/encode-snapshot state-no-meta)
          rt      (decoder/decode-snapshot encoded)]
      (is (map? rt) "decode should return a map, not an error")
      (is (not (contains? rt :error)) "decode should not return an error")
      (is (= 7 (get-in rt [:players :player-1 :player/max-hand-size]))
          "player-1 max-hand-size defaults to 7")
      (is (= 7 (get-in rt [:players :player-2 :player/max-hand-size]))
          "player-2 max-hand-size defaults to 7"))))
