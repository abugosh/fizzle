(ns fizzle.sharing.replacement-snapshot-test
  "Tests for snapshot round-trip with replacement-effect objects.

   Verifies two things:
   A. The extractor strips :object/replacement-effects refs (non-portable, like :object/triggers)
      so that extract → encode → decode → restore completes without error when a card
      with replacement-effects (e.g. Mox Diamond) is in a zone.

   B. The :object/replacement-pending boolean marker does not break extraction.
      (Note: the binary encoder does not encode this field — it is lost in the
       sharing-URL snapshot — but it must not cause an error during extraction.)

   The full sharing-URL round-trip test verifies that Mox Diamond on the battlefield
   can be shared and restored correctly."
  (:require
    [cljs.test :refer-macros [deftest testing is]]
    [datascript.core :as d]
    [fizzle.db.queries :as q]
    [fizzle.sharing.decoder :as decoder]
    [fizzle.sharing.encoder :as encoder]
    [fizzle.sharing.extractor :as extractor]
    [fizzle.sharing.restorer :as restorer]
    [fizzle.test-helpers :as th]))


;; ---------------------------------------------------------------------------
;; Helpers

(defn- make-snapshot
  "Extract + encode + decode a live DB to get a portable snapshot map."
  [db]
  (-> db extractor/extract encoder/encode-snapshot decoder/decode-snapshot))


(defn- full-round-trip-db
  "Full round-trip: DB → snapshot → restored DB."
  [db]
  (-> db make-snapshot restorer/restore-game-state :game/db))


;; ---------------------------------------------------------------------------
;; A. Extractor strips :object/replacement-effects refs

(deftest extract-mox-diamond-in-hand-does-not-include-replacement-effects-test
  (testing "extracting a hand with Mox Diamond does not include :object/replacement-effects refs"
    (let [[db _] (th/add-card-to-zone (-> (th/create-test-db) (th/add-opponent))
                                      :mox-diamond :hand :player-1)
          out    (extractor/extract db)
          hand   (get-in out [:players :player-1 :hand])]
      (is (= 1 (count hand))
          "hand should have 1 card")
      (is (= :mox-diamond (:card/id (first hand)))
          "card should be mox-diamond")
      (is (not (contains? (first hand) :object/replacement-effects))
          "extracted object must NOT contain :object/replacement-effects (non-portable refs)"))))


(deftest extract-mox-diamond-on-battlefield-does-not-include-replacement-effects-test
  (testing "extracting a battlefield with Mox Diamond does not include :object/replacement-effects refs"
    (let [[db _] (th/add-card-to-zone (-> (th/create-test-db) (th/add-opponent))
                                      :mox-diamond :battlefield :player-1)
          out    (extractor/extract db)
          bf     (get-in out [:players :player-1 :battlefield])]
      (is (= 1 (count bf))
          "battlefield should have 1 permanent")
      (is (= :mox-diamond (:card/id (first bf)))
          "permanent should be mox-diamond")
      (is (not (contains? (first bf) :object/replacement-effects))
          "extracted object must NOT contain :object/replacement-effects (non-portable refs)"))))


;; ---------------------------------------------------------------------------
;; B. Full round-trip with Mox Diamond survives (no errors, card present)

(deftest mox-diamond-hand-survives-snapshot-round-trip-test
  (testing "Mox Diamond in hand survives share snapshot round-trip"
    (let [[db _]   (th/add-card-to-zone (-> (th/create-test-db) (th/add-opponent))
                                        :mox-diamond :hand :player-1)
          restored (full-round-trip-db db)
          hand     (q/get-objects-in-zone restored :player-1 :hand)]
      (is (= 1 (count hand))
          "hand should have 1 card after round-trip")
      (is (= :mox-diamond (get-in (first hand) [:object/card :card/id]))
          "restored hand card should be mox-diamond"))))


(deftest mox-diamond-battlefield-survives-snapshot-round-trip-test
  (testing "Mox Diamond on battlefield survives share snapshot round-trip"
    (let [[db _]   (th/add-card-to-zone (-> (th/create-test-db) (th/add-opponent))
                                        :mox-diamond :battlefield :player-1)
          restored (full-round-trip-db db)
          bf       (q/get-objects-in-zone restored :player-1 :battlefield)]
      (is (= 1 (count bf))
          "battlefield should have 1 permanent after round-trip")
      (is (= :mox-diamond (get-in (first bf) [:object/card :card/id]))
          "restored battlefield card should be mox-diamond"))))


;; ---------------------------------------------------------------------------
;; C. :object/replacement-pending does not break extraction

(deftest replacement-pending-marker-does-not-break-extraction-test
  (testing ":object/replacement-pending on an object does not cause extraction errors"
    (let [[db obj-id] (th/add-card-to-zone (-> (th/create-test-db) (th/add-opponent))
                                           :mox-diamond :hand :player-1)
          obj-eid     (q/get-object-eid db obj-id)
          db-marked   (d/db-with db [[:db/add obj-eid :object/replacement-pending true]])
          out         (extractor/extract db-marked)]
      (is (map? out)
          "extract should succeed (return a map) even with :object/replacement-pending set")
      (is (= 1 (count (get-in out [:players :player-1 :hand])))
          "hand should still have 1 card"))))
