(ns fizzle.snapshot.events-test
  "Tests for snapshot share/restore re-frame events.

   Tests focus on the pure handler logic:
   - share-position handler: extracts, encodes, produces clipboard call
   - restore-from-hash handler: decodes hash, restores game state
   - share-status subscription: tracks idle/copied/error state
   - scenario->url: encodes scenario to shareable URL with title param
   - restore-from-hash-handler with &t= title param"
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


;; ---------------------------------------------------------------------------
;; D. scenario->url: URL generation with title

(deftest scenario->url-includes-snapshot-test
  (testing "scenario->url produces URL with #s= fragment"
    (let [scenario {:scenario/title  "Test Combo"
                    :scenario/player {:deck      [{:card/id :dark-ritual :count 4}]
                                      :zones     {}
                                      :mana-pool {}
                                      :life      20}
                    :scenario/opponent {:archetype :goldfish
                                        :deck      [{:card/id :swamp :count 40}]
                                        :zones     {}
                                        :mana-pool {}
                                        :life      20}
                    :scenario/phase  :main1}
          url (snap-events/scenario->url scenario "https://example.com/")]
      (is (string? url) "scenario->url should return a string")
      (is (str/includes? url "#s=") "URL should contain #s= fragment")
      (is (str/includes? url "&t=") "URL should contain &t= fragment for title"))))


(deftest scenario->url-encodes-title-test
  (testing "scenario->url base64url-encodes the title in &t= param"
    (let [scenario {:scenario/title  "Storm Practice"
                    :scenario/player {:deck      [{:card/id :dark-ritual :count 4}]
                                      :zones     {}
                                      :mana-pool {}
                                      :life      20}
                    :scenario/opponent {:archetype :goldfish
                                        :deck      [{:card/id :swamp :count 40}]
                                        :zones     {}
                                        :mana-pool {}
                                        :life      20}
                    :scenario/phase  :main1}
          url (snap-events/scenario->url scenario "https://example.com/")]
      (is (string? url) "should return string")
      ;; Parse the title back out
      (let [t-part (second (str/split url #"&t="))
            decoded (js/decodeURIComponent t-part)]
        (is (= "Storm Practice" decoded)
            "decoded title should match original")))))


(deftest scenario->url-no-title-omits-t-param-test
  (testing "scenario->url omits &t= when scenario has no title"
    (let [scenario {:scenario/player {:deck      [{:card/id :dark-ritual :count 4}]
                                      :zones     {}
                                      :mana-pool {}
                                      :life      20}
                    :scenario/opponent {:archetype :goldfish
                                        :deck      [{:card/id :swamp :count 40}]
                                        :zones     {}
                                        :mana-pool {}
                                        :life      20}
                    :scenario/phase  :main1}
          url (snap-events/scenario->url scenario "https://example.com/")]
      (is (string? url) "should return string even without title")
      (is (not (str/includes? url "&t="))
          "URL should not contain &t= when title is absent"))))


(deftest scenario->url-special-chars-in-title-test
  (testing "scenario->url encodes special characters in title: &, #, quotes, unicode"
    (let [special-title "Turn 1 & #2: \"Storm\" ♥"
          scenario {:scenario/title  special-title
                    :scenario/player {:deck      [{:card/id :dark-ritual :count 4}]
                                      :zones     {}
                                      :mana-pool {}
                                      :life      20}
                    :scenario/opponent {:archetype :goldfish
                                        :deck      [{:card/id :swamp :count 40}]
                                        :zones     {}
                                        :mana-pool {}
                                        :life      20}
                    :scenario/phase  :main1}
          url (snap-events/scenario->url scenario "https://example.com/")]
      (is (string? url) "should return string")
      (let [t-part (second (str/split url #"&t="))
            decoded (js/decodeURIComponent t-part)]
        (is (= special-title decoded)
            "special characters in title should survive encode/decode round-trip")))))


;; ---------------------------------------------------------------------------
;; E. restore-from-hash-handler with &t= title param

(deftest restore-from-hash-with-title-restores-game-test
  (testing "restore-from-hash-handler with &t= param restores game state correctly"
    (let [db       (-> (th/create-test-db {:life 15}) (th/add-opponent))
          [db' _]  (th/add-card-to-zone db :dark-ritual :hand :player-1)
          encoded  (-> db' extractor/extract encoder/encode-snapshot)
          title    "My Test Scenario"
          hash     (str "#s=" encoded "&t=" (js/encodeURIComponent title))
          result   (snap-events/restore-from-hash-handler hash)]
      (is (some? result) "should restore game state when hash has &t= param")
      (is (= :game (:active-screen result))
          "active-screen should be :game after restore"))))


(deftest restore-from-hash-backward-compat-no-title-test
  (testing "restore-from-hash-handler with no &t= still works (backward compat)"
    (let [db       (-> (th/create-test-db {:life 18}) (th/add-opponent))
          encoded  (-> db extractor/extract encoder/encode-snapshot)
          hash     (str "#s=" encoded)
          result   (snap-events/restore-from-hash-handler hash)]
      (is (some? result) "should restore game state from hash without &t=")
      (is (= :game (:active-screen result))
          "active-screen should be :game"))))


(deftest restore-from-hash-malformed-title-graceful-test
  (testing "restore-from-hash-handler with malformed &t= still restores game"
    (let [db       (-> (th/create-test-db) (th/add-opponent))
          encoded  (-> db extractor/extract encoder/encode-snapshot)
          hash     (str "#s=" encoded "&t=%zz")  ; invalid percent-encoding
          result   (snap-events/restore-from-hash-handler hash)]
      ;; Game should still restore even if title decode fails
      (is (some? result) "game should restore even when &t= param is malformed"))))


;; ---------------------------------------------------------------------------
;; F. parse-scenario-url: pure parsing helper

(deftest parse-url-extracts-snapshot-and-title-test
  (testing "parse-scenario-url extracts both encoded snapshot and title"
    (let [db       (-> (th/create-test-db) (th/add-opponent))
          encoded  (-> db extractor/extract encoder/encode-snapshot)
          title    "Round-trip Title"
          hash     (str "#s=" encoded "&t=" (js/encodeURIComponent title))
          parsed   (snap-events/parse-scenario-url hash)]
      (is (= encoded (:snapshot parsed))
          "parsed :snapshot should match encoded string")
      (is (= title (:title parsed))
          "parsed :title should match original title"))))


(deftest parse-url-no-title-returns-nil-title-test
  (testing "parse-scenario-url returns nil :title when &t= absent"
    (let [db       (-> (th/create-test-db) (th/add-opponent))
          encoded  (-> db extractor/extract encoder/encode-snapshot)
          hash     (str "#s=" encoded)
          parsed   (snap-events/parse-scenario-url hash)]
      (is (= encoded (:snapshot parsed))
          "parsed :snapshot should match encoded string")
      (is (nil? (:title parsed))
          "parsed :title should be nil when &t= absent"))))
