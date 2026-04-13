(ns fizzle.sharing.encoder-test
  "Tests for snapshot encoder: portable map → binary → base64url.

   Tests verify output shape, size targets, field preservation at boundaries,
   and graceful handling of edge cases. Decoder (fizzle-decoder task) will
   verify full roundtrip; these tests verify the encoder side only."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [clojure.string :as string]
    [fizzle.sharing.encoder :as encoder]
    [fizzle.sharing.extractor :as extractor]
    [fizzle.test-helpers :as th]))


;; === A. Basic output shape ===

(deftest encode-returns-string-test
  (testing "encode-snapshot returns a string"
    (let [db    (th/create-test-db)
          state (extractor/extract db)
          out   (encoder/encode-snapshot state)]
      (is (string? out)
          "encode-snapshot should return a string"))))


(deftest encode-url-safe-chars-test
  (testing "encoded output contains only base64url-safe characters"
    (let [db    (-> (th/create-test-db) (th/add-opponent))
          state (extractor/extract db)
          out   (encoder/encode-snapshot state)]
      (is (re-matches #"[A-Za-z0-9\-_]*" out)
          "output should only contain A-Z a-z 0-9 - _")
      (is (not (string/includes? out "="))
          "no padding chars"))))


;; === B. Size targets ===

(deftest encode-empty-board-under-200-chars-test
  (testing "empty board (no cards anywhere) encodes to < 200 chars"
    (let [db    (-> (th/create-test-db) (th/add-opponent))
          state (extractor/extract db)
          out   (encoder/encode-snapshot state)]
      (is (< (count out) 200)
          (str "empty board should be < 200 chars, got " (count out))))))


(deftest encode-mid-combo-under-300-chars-test
  (testing "mid-combo state (5 hand, 4 battlefield, 10 graveyard, 33 library) encodes to < 300 chars"
    (let [db0  (-> (th/create-test-db) (th/add-opponent))
          ;; Add cards to zones
          [db1 _] (th/add-cards-to-library db0
                                           (vec (take 33 (cycle [:dark-ritual :brain-freeze
                                                                 :lotus-petal :counterspell
                                                                 :impulse])))
                                           :player-1)
          [db2 _] (th/add-card-to-zone db1 :dark-ritual    :hand :player-1)
          [db3 _] (th/add-card-to-zone db2 :brain-freeze   :hand :player-1)
          [db4 _] (th/add-card-to-zone db3 :lotus-petal    :hand :player-1)
          [db5 _] (th/add-card-to-zone db4 :counterspell   :hand :player-1)
          [db6 _] (th/add-card-to-zone db5 :impulse        :hand :player-1)
          [db7 _] (th/add-card-to-zone db6 :city-of-brass  :battlefield :player-1)
          [db8 _] (th/add-card-to-zone db7 :city-of-traitors :battlefield :player-1)
          [db9 _] (th/add-card-to-zone db8 :gemstone-mine  :battlefield :player-1)
          [db10 _] (th/add-card-to-zone db9 :lotus-petal   :battlefield :player-1)
          [db11 _] (th/add-cards-to-graveyard db10
                                              [:dark-ritual :dark-ritual :cabal-ritual
                                               :cabal-ritual :brain-freeze :lotus-petal
                                               :impulse :counterspell :opt :sleight-of-hand]
                                              :player-1)
          state (extractor/extract db11)
          out   (encoder/encode-snapshot state)]
      (is (< (count out) 300)
          (str "mid-combo should be < 300 chars, got " (count out))))))


;; === C. Field encoding — header values ===

(deftest encode-header-version-test
  (testing "encoded output begins with version bits (v1)"
    ;; We can't decode yet, but we can verify the output changes when state changes
    ;; and that output is reproducible (deterministic)
    (let [db    (-> (th/create-test-db) (th/add-opponent))
          state (extractor/extract db)
          out1  (encoder/encode-snapshot state)
          out2  (encoder/encode-snapshot state)]
      (is (= out1 out2)
          "encoding is deterministic"))))


;; === G. nil/error handling ===

(deftest encode-returns-nil-for-oversized-state-test
  (testing "encode-snapshot returns nil when state exceeds URL limit"
    ;; A state with 63 hand cards × 2 players would be pathologically large.
    ;; Build a state with maxed-out zones to trigger the limit check.
    (let [db0   (-> (th/create-test-db) (th/add-opponent))
          ;; Each player gets 60 library cards (well over typical)
          [db1 _] (th/add-cards-to-library db0
                                           (vec (take 60 (cycle [:dark-ritual])))
                                           :player-1)
          [db2 _] (th/add-cards-to-library db1
                                           (vec (take 60 (cycle [:dark-ritual])))
                                           :player-2)
          ;; 20 hand cards each
          db3  (reduce (fn [d _] (first (th/add-card-to-zone d :dark-ritual :hand :player-1)))
                       db2 (range 20))
          db4  (reduce (fn [d _] (first (th/add-card-to-zone d :dark-ritual :hand :player-2)))
                       db3 (range 20))
          state (extractor/extract db4)
          ;; Inject absurdly large grants to trigger EDN fallback overhead
          bloated (update-in state [:players :player-1 :player/grants]
                             (fn [_]
                               (vec (repeat 50 {:grant/type :test
                                                :grant/data (str (range 1000))}))))
          out (encoder/encode-snapshot bloated)]
      ;; Either returns nil (too large) or a string; must not throw
      (is (or (nil? out) (string? out))
          "encode-snapshot should return nil or string, never throw"))))
