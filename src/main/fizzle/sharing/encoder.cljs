(ns fizzle.sharing.encoder
  "Snapshot encoder: portable game-state map → compact binary → base64url string.

   Binary format v1 (MSB-first):

   Header (~14 bytes):
     version       4 bits  (value: 1)
     flags         4 bits  (bit1=has-player-grants, reserved)
     p1 life       8 bits  (stored as life+128, clamped 0-255)
     p2 life       8 bits
     p1 storm      6 bits  (0-63)
     p2 storm      6 bits
     turn          8 bits
     phase         4 bits  (index into PHASES)
     step          4 bits  (index into PHASES, 0xF=nil)
     active-player 1 bit   (0=player-1, 1=other)
     priority      1 bit
     p1 mana       24 bits (6 colors × 4 bits each, clamped 0-15)
     p2 mana       24 bits
     p1 land-plays 2 bits  (0-3)
     p2 land-plays 2 bits

   Zones (P1 then P2):
     Per-object shared trailer: 1-bit has-grants (if set: length-prefixed EDN)
     hand:        6-bit count, N × (7-bit card-index + grants-trailer)
     battlefield: 6-bit count, per-permanent: 7-bit card-index + 1-bit tapped
                  + 1-bit has-counters (if set: 3-bit counter-count,
                    then N × (3-bit type-idx + 8-bit amount))
                  + grants-trailer
     graveyard:   6-bit count, N × (7-bit card-index + grants-trailer)
     exile:       6-bit count, N × (7-bit card-index + grants-trailer)
     library:     7-bit count, N × (7-bit card-index + grants-trailer) (position order, 0=top)

   Complex data: pr-str EDN, 16-bit length prefix (bytes), then UTF-8 bytes.

   URL limit: returns nil when output would exceed 2000 chars."
  (:require
    [fizzle.sharing.bits :as bits]
    [fizzle.sharing.card-index :as card-index]))


;; ---------------------------------------------------------------------------
;; Constants

(def ^:private phases
  [:untap :upkeep :draw :main1 :combat :main2 :end :cleanup])


(def ^:private phase->idx
  (into {} (map-indexed (fn [i p] [p i]) phases)))


(def ^:private mana-colors
  [:white :blue :black :red :green :colorless])


(def ^:private counter-types
  [:+1/+1 :-1/-1 :mining :charge :loyalty :verse :lore :time :age])


(def ^:private counter-type->idx
  (into {} (map-indexed (fn [i t] [t i]) counter-types)))


(def ^:private url-char-limit 2000)


;; ---------------------------------------------------------------------------
;; Helpers

(defn- clamp
  [n lo hi]
  (max lo (min hi n)))


(defn- encode-edn-blob
  "Write a pr-str EDN string as a 16-bit length-prefixed UTF-8 byte sequence.
   Returns updated BitWriter."
  [w data]
  (let [s     (pr-str data)
        bytes (.encode (js/TextEncoder.) s)
        n     (.-length bytes)]
    (let [w1 (bits/write-bits w n 16)]
      (loop [i 0 ww w1]
        (if (= i n)
          ww
          (recur (inc i) (bits/write-bits ww (aget bytes i) 8)))))))


(defn- player-ids
  "Return [p1-id p2-id] where p1 is always the human player."
  [state]
  (let [pids (keys (:players state))
        human (:game/human-player-id state)
        other (first (remove #{human} pids))]
    [human (or other human)]))


;; ---------------------------------------------------------------------------
;; Zone encoders

(defn- write-grants
  "Write 1-bit has-grants flag + optional EDN blob. Shared by all zone encoders."
  [w obj]
  (let [grants  (or (:object/grants obj) [])
        has-grt (if (seq grants) 1 0)]
    (-> w
        (bits/write-bits has-grt 1)
        (cond-> (pos? has-grt)
          (as-> ww (encode-edn-blob ww grants))))))


(defn- write-card-obj
  "Write one card object: 7-bit card index + grants."
  [w obj]
  (let [idx (or (card-index/encode (:card/id obj)) 0)]
    (-> w
        (bits/write-bits (clamp idx 0 127) 7)
        (write-grants obj))))


(defn- write-card-list
  "Write a count (count-bits wide) then N card objects with optional grants."
  [w cards count-bits]
  (let [n (count cards)
        w1 (bits/write-bits w (clamp n 0 (dec (bit-shift-left 1 count-bits))) count-bits)]
    (reduce write-card-obj
            w1
            (take (clamp n 0 (dec (bit-shift-left 1 count-bits))) cards))))


(defn- write-permanent
  "Write one battlefield permanent: card index + tapped + counters + grants."
  [w obj]
  (let [card-idx (or (card-index/encode (:card/id obj)) 0)
        tapped   (if (:object/tapped obj) 1 0)
        counters (or (:object/counters obj) {})
        has-ctr  (if (seq counters) 1 0)]
    (-> w
        (bits/write-bits (clamp card-idx 0 127) 7)
        (bits/write-bits tapped 1)
        (bits/write-bits has-ctr 1)
        (cond-> (pos? has-ctr)
          (as-> ww
            (let [ctr-pairs (seq counters)
                  cnt (clamp (count ctr-pairs) 0 7)]
              (reduce (fn [www [ctype amount]]
                        (let [tidx (or (get counter-type->idx ctype) 0)]
                          (-> www
                              (bits/write-bits (clamp tidx 0 7) 3)
                              (bits/write-bits (clamp amount 0 255) 8))))
                      (bits/write-bits ww cnt 3)
                      (take cnt ctr-pairs)))))
        (write-grants obj))))


(defn- write-battlefield
  "Write battlefield: 6-bit count then each permanent."
  [w permanents]
  (let [n  (count permanents)
        w1 (bits/write-bits w (clamp n 0 63) 6)]
    (reduce write-permanent w1 (take 63 permanents))))


(defn- write-player-zones
  "Write all five zones for one player."
  [w player-state]
  (-> w
      (write-card-list (:hand player-state) 6)
      (write-battlefield (:battlefield player-state))
      (write-card-list (:graveyard player-state) 6)
      (write-card-list (:exile player-state) 6)
      (write-card-list (:library player-state) 7)))


;; ---------------------------------------------------------------------------
;; Mana encoder

(defn- write-mana
  "Write 6 mana colors × 4 bits each (clamped 0-15)."
  [w pool]
  (reduce (fn [ww color]
            (bits/write-bits ww (clamp (or (get pool color) 0) 0 15) 4))
          w
          mana-colors))


;; ---------------------------------------------------------------------------
;; Header encoder

(defn- write-header
  "Write the fixed header."
  [w state p1-id p2-id has-grants?]
  (let [p1     (get-in state [:players p1-id])
        p2     (get-in state [:players p2-id])
        flags  (if has-grants? 2 0)
        p1-life (clamp (+ (or (:player/life p1) 20) 128) 0 255)
        p2-life (clamp (+ (or (:player/life p2) 20) 128) 0 255)
        p1-storm (clamp (or (:player/storm-count p1) 0) 0 63)
        p2-storm (clamp (or (:player/storm-count p2) 0) 0 63)
        turn    (clamp (or (:game/turn state) 1) 0 255)
        phase   (or (get phase->idx (:game/phase state)) 3)  ; default main1
        step    (if-let [s (:game/step state)]
                  (or (get phase->idx s) 0xF)
                  0xF)
        active-bit (if (= (:game/active-player state) p1-id) 0 1)
        prio-bit   (if (= (:game/priority state) p1-id) 0 1)
        p1-land (clamp (or (:player/land-plays-left p1) 1) 0 3)
        p2-land (clamp (or (:player/land-plays-left p2) 0) 0 3)]
    (-> w
        (bits/write-bits 1 4)          ; version
        (bits/write-bits flags 4)
        (bits/write-bits p1-life 8)
        (bits/write-bits p2-life 8)
        (bits/write-bits p1-storm 6)
        (bits/write-bits p2-storm 6)
        (bits/write-bits turn 8)
        (bits/write-bits phase 4)
        (bits/write-bits step 4)
        (bits/write-bits active-bit 1)
        (bits/write-bits prio-bit 1)
        (write-mana (get-in state [:players p1-id :player/mana-pool]))
        (write-mana (get-in state [:players p2-id :player/mana-pool]))
        (bits/write-bits p1-land 2)
        (bits/write-bits p2-land 2))))


;; ---------------------------------------------------------------------------
;; Public API

(defn- write-player-grants
  "Write player-level grants as EDN blobs for both players when flag is set.
   p1-grants and p2-grants are vectors of grant maps (may be empty)."
  [w p1-grants p2-grants]
  (-> w
      (encode-edn-blob (or (seq p1-grants) []))
      (encode-edn-blob (or (seq p2-grants) []))))


(defn encode-snapshot
  "Encode a portable game-state map (from extractor/extract) to a base64url string.
   Returns nil if the result would exceed the URL character limit."
  [state]
  (let [[p1-id p2-id] (player-ids state)
        p1-grants    (get-in state [:players p1-id :player/grants])
        p2-grants    (get-in state [:players p2-id :player/grants])
        has-grants?  (or (seq p1-grants) (seq p2-grants))
        w            (-> (bits/writer)
                         (write-header state p1-id p2-id has-grants?)
                         (write-player-zones (get (:players state) p1-id))
                         (write-player-zones (get (:players state) p2-id))
                         (cond-> has-grants?
                           (as-> ww (write-player-grants ww p1-grants p2-grants))))
        result       (bits/base64url-encode (bits/finish w))]
    (when (<= (count result) url-char-limit)
      result)))
