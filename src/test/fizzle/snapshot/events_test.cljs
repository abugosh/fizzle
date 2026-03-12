(ns fizzle.snapshot.events-test
  "Tests for snapshot share/restore re-frame events.

   Tests focus on the pure handler logic:
   - share-position handler: extracts, encodes, produces clipboard call
   - restore-from-hash handler: decodes hash, restores game state
   - share-status subscription: tracks idle/copied/error state"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [clojure.string :as str]
    [fizzle.sharing.encoder :as encoder]
    [fizzle.sharing.extractor :as extractor]
    [fizzle.snapshot.events :as snap-events]
    [fizzle.test-helpers :as th]))


;; ---------------------------------------------------------------------------
;; A. restore-from-hash handler (pure function tests)

(deftest restore-from-hash-valid-test
  (testing "restore-from-hash-handler replaces game state from valid hash"
    (let [db       (-> (th/create-test-db {:life 13}) (th/add-opponent))
          [db' _]  (th/add-card-to-zone db :dark-ritual :hand :player-1)
          encoded  (-> db' extractor/extract encoder/encode-snapshot)
          result   (snap-events/restore-from-hash-handler (str "#s=" encoded))]
      (is (= :game (:active-screen result))
          "active-screen should be :game after restore")
      (is (some? (:game/db result))
          "game/db should be present after restore"))))


(deftest restore-from-hash-returns-nil-on-invalid-test
  (testing "restore-from-hash-handler returns nil for malformed hash"
    (let [result (snap-events/restore-from-hash-handler "#s=notvalidbase64!!!")]
      (is (nil? result)
          "should return nil for invalid hash so caller falls back to normal init"))))


(deftest restore-from-hash-returns-nil-on-empty-test
  (testing "restore-from-hash-handler returns nil for empty hash"
    (let [result (snap-events/restore-from-hash-handler "")]
      (is (nil? result)
          "should return nil for empty hash"))))


(deftest restore-from-hash-returns-nil-on-wrong-prefix-test
  (testing "restore-from-hash-handler returns nil when prefix is not #s="
    (let [db       (-> (th/create-test-db) (th/add-opponent))
          encoded  (-> db extractor/extract encoder/encode-snapshot)
          result   (snap-events/restore-from-hash-handler (str "#x=" encoded))]
      (is (nil? result)
          "should return nil when prefix is wrong"))))


(deftest restore-from-hash-sets-empty-history-test
  (testing "restore-from-hash-handler initializes empty history"
    (let [db      (-> (th/create-test-db) (th/add-opponent))
          encoded (-> db extractor/extract encoder/encode-snapshot)
          result  (snap-events/restore-from-hash-handler (str "#s=" encoded))]
      (when (some? result)
        (is (= [] (:history/main result))
            ":history/main should be []")
        (is (= {} (:history/forks result))
            ":history/forks should be {}")))))


;; ---------------------------------------------------------------------------
;; B. share-position-handler (encode + build URL)

(deftest share-position-builds-url-test
  (testing "encode-for-share produces a URL with #s= fragment"
    (let [db      (-> (th/create-test-db) (th/add-opponent))
          app-db  {:game/db db}
          url     (snap-events/encode-for-share app-db "https://example.com/")]
      (is (string? url)
          "encode-for-share should return a string")
      (is (str/includes? url "#s=")
          "URL should contain #s= fragment"))))


(deftest share-position-returns-nil-on-no-game-db-test
  (testing "encode-for-share returns nil when no game/db in app-db"
    (let [result (snap-events/encode-for-share {} "https://example.com/")]
      (is (nil? result)
          "should return nil when game/db is absent"))))


;; ---------------------------------------------------------------------------
;; C. share-status in app-db

(deftest share-status-default-idle-test
  (testing "share status defaults to :idle"
    (let [app-db {}
          status (snap-events/get-share-status app-db)]
      (is (= :idle status)
          "default share status should be :idle"))))


(deftest share-status-can-be-set-test
  (testing "share status can be set to :copied"
    (let [app-db (snap-events/set-share-status {} :copied)
          status (snap-events/get-share-status app-db)]
      (is (= :copied status)
          "share status should be :copied after setting"))))


(deftest share-status-can-be-set-to-error-too-large-test
  (testing "share status can be set to :error-too-large"
    (let [app-db (snap-events/set-share-status {} :error-too-large)
          status (snap-events/get-share-status app-db)]
      (is (= :error-too-large status)
          "share status should be :error-too-large after setting"))))


(deftest share-status-can-be-set-to-error-clipboard-test
  (testing "share status can be set to :error-clipboard"
    (let [app-db (snap-events/set-share-status {} :error-clipboard)
          status (snap-events/get-share-status app-db)]
      (is (= :error-clipboard status)
          "share status should be :error-clipboard after setting"))))
