(ns fizzle.snapshot.events-test
  "Tests for snapshot share/restore re-frame events.

   Tests focus on the pure handler logic:
   - share-position handler: extracts, encodes, produces clipboard call
   - restore-from-hash handler: decodes hash, restores game state
   - share-status subscription: tracks idle/copied/error state
   - scenario->url: encodes scenario config to shareable #sc= URL with title param
   - restore-from-hash-handler with &t= title param
   - parse-scenario-url: handles #sc= and #s= prefixes"
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [clojure.string :as str]
    [fizzle.sharing.encoder :as encoder]
    [fizzle.sharing.extractor :as extractor]
    [fizzle.snapshot.events :as snap-events]
    [fizzle.test-helpers :as th]))


;; ---------------------------------------------------------------------------
;; Mock localStorage (required for scenario auto-save path)

(defn- create-mock-storage
  []
  (let [store (atom {})]
    #js {:getItem    (fn [key] (get @store key nil))
         :setItem    (fn [key value] (swap! store assoc key value) nil)
         :removeItem (fn [key] (swap! store dissoc key) nil)}))


(set! js/localStorage (create-mock-storage))


;; ---------------------------------------------------------------------------
;; A. restore-from-hash handler (pure function tests) — #s= position snapshots

(deftest restore-from-hash-valid-test
  (testing "restore-from-hash-handler replaces game state from valid #s= hash"
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
  (testing "restore-from-hash-handler returns nil when prefix is not recognized"
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
;; D. scenario->url: URL generation with #sc= format

(deftest scenario->url-includes-sc-fragment-test
  (testing "scenario->url produces URL with #sc= fragment (not #s=)"
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
      (is (str/starts-with? (second (str/split url #"#")) "sc=")
          "URL fragment should start with sc=")
      (is (str/includes? url "&t=") "URL should contain &t= fragment for title"))))


(deftest scenario->url-does-not-include-s-prefix-test
  (testing "scenario->url uses #sc= not #s= (scenario config, not position snapshot)"
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
      (is (str/includes? url "#sc=")
          "URL should use #sc= prefix")
      (is (not (str/includes? url "#s="))
          "URL should NOT use legacy #s= prefix"))))


(deftest scenario->url-encodes-title-test
  (testing "scenario->url percent-encodes the title in &t= param"
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
;; D2. scenario->url roundtrip and random-draw tests

(def ^:private base-scenario
  {:scenario/player   {:deck      [{:card/id :dark-ritual :count 4}]
                       :zones     {}
                       :mana-pool {}
                       :life      20}
   :scenario/opponent {:archetype :goldfish
                       :deck      [{:card/id :swamp :count 40}]
                       :zones     {}
                       :mana-pool {}
                       :life      20}
   :scenario/phase    :main1})


(deftest scenario->url-roundtrip-config-test
  (testing "scenario config survives scenario->url → parse → decode round-trip"
    (let [scenario (assoc base-scenario :scenario/title "Roundtrip")
          url      (snap-events/scenario->url scenario "https://example.com/")
          parsed   (snap-events/parse-scenario-url (str "#" (second (str/split url #"#"))))
          decoded  (snap-events/decode-scenario-config (:encoded parsed))]
      (is (= :scenario-config (:type parsed))
          "parsed type should be :scenario-config")
      (is (map? decoded)
          "decoded result should be a map")
      ;; :scenario/id is dissoc'd before encoding
      (is (= (dissoc scenario :scenario/id) decoded)
          "decoded config should match original scenario minus :scenario/id"))))


(deftest scenario->url-with-random-draw-produces-sc-url-test
  (testing "scenario->url with :random-draw produces valid #sc= URL"
    (let [scenario (assoc base-scenario :scenario/draw :random-draw)
          url      (snap-events/scenario->url scenario "https://example.com/")]
      (is (string? url) "should return a string")
      (is (str/includes? url "#sc=")
          "URL should use #sc= prefix for random-draw scenario"))))


(deftest scenario->url-title-preserved-in-roundtrip-test
  (testing "title is preserved through #sc= encode/decode round-trip"
    (let [scenario (assoc base-scenario :scenario/title "My Combo Practice")
          url      (snap-events/scenario->url scenario "https://example.com/")
          ;; Extract just the hash portion
          hash     (str "#" (second (str/split url #"#")))
          parsed   (snap-events/parse-scenario-url hash)]
      (is (= "My Combo Practice" (:title parsed))
          "title should survive the round-trip through URL encoding"))))


;; ---------------------------------------------------------------------------
;; E. restore-from-hash-handler with &t= title param — #s= snapshot format

(deftest restore-from-hash-with-title-restores-game-test
  (testing "restore-from-hash-handler with #s= &t= param restores game state correctly"
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
  (testing "restore-from-hash-handler with #s= and no &t= still works (backward compat)"
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
;; E2. restore-from-hash-handler with #sc= scenario config format

(deftest restore-from-hash-sc-prefix-produces-game-test
  (testing "restore-from-hash-handler with #sc= URL produces valid game state"
    (let [scenario (assoc base-scenario :scenario/title "SC Restore Test")
          url      (snap-events/scenario->url scenario "https://example.com/")
          hash     (str "#" (second (str/split url #"#")))
          result   (snap-events/restore-from-hash-handler hash)]
      (is (some? result)
          "should produce a game state from #sc= URL")
      (is (some? (:game/db result))
          "game/db should be present after restore"))))


(deftest restore-from-hash-sc-with-random-draw-produces-hand-test
  (testing "restore-from-hash-handler with #sc= and :random-draw produces hand cards"
    (let [scenario (-> base-scenario
                       (assoc :scenario/draw :random-draw))
          url      (snap-events/scenario->url scenario "https://example.com/")
          hash     (str "#" (second (str/split url #"#")))
          result   (snap-events/restore-from-hash-handler hash)]
      (is (some? result)
          "should produce a game state from #sc= :random-draw URL")
      (is (some? (:game/db result))
          "game/db should be present"))))


(deftest restore-from-hash-sc-title-preserved-test
  (testing "restore-from-hash-handler with #sc= preserves title in restored app-db"
    (let [scenario (assoc base-scenario :scenario/title "Title Round-Trip")
          url      (snap-events/scenario->url scenario "https://example.com/")
          hash     (str "#" (second (str/split url #"#")))
          result   (snap-events/restore-from-hash-handler hash)]
      (is (= "Title Round-Trip" (:snapshot/restored-title result))
          "restored title should match original scenario title"))))


;; ---------------------------------------------------------------------------
;; F. parse-scenario-url: pure parsing helper

(deftest parse-url-sc-extracts-encoded-and-title-test
  (testing "parse-scenario-url extracts :encoded and :title for #sc= URLs"
    (let [scenario (assoc base-scenario :scenario/title "Round-trip Title")
          url      (snap-events/scenario->url scenario "https://example.com/")
          hash     (str "#" (second (str/split url #"#")))
          parsed   (snap-events/parse-scenario-url hash)]
      (is (= :scenario-config (:type parsed))
          "parsed :type should be :scenario-config")
      (is (string? (:encoded parsed))
          "parsed :encoded should be a string")
      (is (= "Round-trip Title" (:title parsed))
          "parsed :title should match original title"))))


(deftest parse-url-sc-no-title-returns-nil-title-test
  (testing "parse-scenario-url returns nil :title when &t= absent for #sc= URLs"
    (let [scenario base-scenario
          url      (snap-events/scenario->url scenario "https://example.com/")
          hash     (str "#" (second (str/split url #"#")))
          parsed   (snap-events/parse-scenario-url hash)]
      (is (= :scenario-config (:type parsed))
          "parsed :type should be :scenario-config")
      (is (nil? (:title parsed))
          "parsed :title should be nil when &t= absent"))))


(deftest parse-url-s-prefix-returns-snapshot-type-test
  (testing "parse-scenario-url returns {:type :snapshot ...} for #s= URLs"
    (let [db       (-> (th/create-test-db) (th/add-opponent))
          encoded  (-> db extractor/extract encoder/encode-snapshot)
          title    "Snapshot Title"
          hash     (str "#s=" encoded "&t=" (js/encodeURIComponent title))
          parsed   (snap-events/parse-scenario-url hash)]
      (is (= :snapshot (:type parsed))
          "parsed :type should be :snapshot for #s= URL")
      (is (= encoded (:snapshot parsed))
          "parsed :snapshot should match encoded string")
      (is (= title (:title parsed))
          "parsed :title should match original title"))))


(deftest parse-url-s-prefix-no-title-test
  (testing "parse-scenario-url returns nil :title when &t= absent for #s= URLs"
    (let [db       (-> (th/create-test-db) (th/add-opponent))
          encoded  (-> db extractor/extract encoder/encode-snapshot)
          hash     (str "#s=" encoded)
          parsed   (snap-events/parse-scenario-url hash)]
      (is (= :snapshot (:type parsed))
          "parsed :type should be :snapshot")
      (is (= encoded (:snapshot parsed))
          "parsed :snapshot should match encoded string")
      (is (nil? (:title parsed))
          "parsed :title should be nil when &t= absent"))))


(deftest parse-url-unknown-prefix-returns-nil-test
  (testing "parse-scenario-url returns nil for unknown prefix"
    (is (nil? (snap-events/parse-scenario-url "#x=foo"))
        "should return nil for unknown prefix")
    (is (nil? (snap-events/parse-scenario-url ""))
        "should return nil for empty string")
    (is (nil? (snap-events/parse-scenario-url nil))
        "should return nil for nil input")))


;; ---------------------------------------------------------------------------
;; G. restore-from-snapshot-handler: auto-save to scenario library

(deftest restore-from-snapshot-with-title-saves-to-library-test
  (testing "restoring a URL with &t= param auto-saves a new entry to :scenario/library"
    (let [db       (-> (th/create-test-db {:life 17}) (th/add-opponent))
          [db' _]  (th/add-card-to-zone db :dark-ritual :hand :player-1)
          encoded  (-> db' extractor/extract encoder/encode-snapshot)
          title    "My Auto-Saved Scenario"
          hash     (str "#s=" encoded "&t=" (js/encodeURIComponent title))
          restored (snap-events/restore-from-hash-handler hash)
          result   (snap-events/restore-from-snapshot-handler {} [nil restored])]
      (is (some? result) "handler should return a result")
      (is (pos? (count (:scenario/library result)))
          ":scenario/library should have at least one entry after restore with title")
      (let [saved-scenario (first (vals (:scenario/library result)))]
        (is (= title (:scenario/title saved-scenario))
            "saved scenario title should match the &t= param")))))


(deftest restore-from-snapshot-without-title-does-not-save-test
  (testing "restoring a URL without &t= does NOT add an entry to :scenario/library"
    (let [db       (-> (th/create-test-db) (th/add-opponent))
          encoded  (-> db extractor/extract encoder/encode-snapshot)
          hash     (str "#s=" encoded)
          restored (snap-events/restore-from-hash-handler hash)
          result   (snap-events/restore-from-snapshot-handler {} [nil restored])]
      (is (some? result) "handler should return a result")
      (is (empty? (:scenario/library result))
          ":scenario/library should remain empty when URL has no title"))))
