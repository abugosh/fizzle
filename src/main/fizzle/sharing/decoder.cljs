(ns fizzle.sharing.decoder
  "Snapshot decoder: base64url string → binary → portable game-state map.

   Exact inverse of fizzle.sharing.encoder. Reads version nibble first;
   v1 uses 7-bit card indices and the field widths documented in encoder.cljs.

   Returns {:error \"message\"} instead of throwing for all malformed input."
  (:require
    [cljs.reader :as reader]
    [fizzle.sharing.bits :as bits]
    [fizzle.sharing.card-index :as card-index]))


;; ---------------------------------------------------------------------------
;; Constants (must mirror encoder)

(def ^:private phases
  [:untap :upkeep :draw :main1 :combat :main2 :end :cleanup])


(def ^:private stack-types
  [:spell :storm :activated-ability :etb :permanent-tapped :land-entered :storm-copy])


(def ^:private mana-colors
  [:white :blue :black :red :green :colorless])


(def ^:private counter-types
  [:+1/+1 :-1/-1 :mining :charge :loyalty :verse :lore :time :age])


;; Human player is always p1 in encoded form
(def ^:private p1-id :player-1)
(def ^:private p2-id :player-2)


;; ---------------------------------------------------------------------------
;; Position-tracking reader wrapper
;;
;; BitReader.readBits silently returns 0 for out-of-bounds bytes (JS typed
;; array returns undefined → bit ops produce 0). We track position ourselves
;; to detect overrun before reading.

(deftype TrackingReader
  [^:mutable r ^:mutable pos capacity]

  Object

  (read
    [_ n]
    (if (> (+ pos n) capacity)
      ::truncated
      (do (set! pos (+ pos n))
          (bits/read-bits r n))))


  (getPos [_] pos))


(defn- make-reader
  [buf]
  (TrackingReader. (bits/reader buf) 0 (* 8 (.-length buf))))


(defn- safe-read
  "Read n bits. Returns ::truncated if buffer would be exhausted."
  [^TrackingReader r n]
  (.read r n))


;; ---------------------------------------------------------------------------
;; Truncation check helper

(defn- check!
  [v]
  (when (= v ::truncated) (throw (ex-info "truncated" {:truncated true})))
  v)


;; ---------------------------------------------------------------------------
;; EDN blob decoder

(defn- read-edn-blob
  "Read a 16-bit length-prefixed UTF-8 EDN blob.
   Returns decoded value or throws on truncation."
  [r]
  (let [n (check! (safe-read r 16))]
    (let [byte-arr (js/Uint8Array. n)]
      (dotimes [i n]
        (aset byte-arr i (check! (safe-read r 8))))
      (let [s (.toString (js/Buffer.from byte-arr) "utf8")]
        (reader/read-string s)))))


;; ---------------------------------------------------------------------------
;; Zone decoders

(defn- read-card-obj
  "Read one 7-bit card index, return {:card/id <keyword>} or throw on unknown."
  [r]
  (let [idx (check! (safe-read r 7))
        cid (card-index/decode idx)]
    (when (nil? cid) (throw (ex-info (str "Unknown card at index " idx) {:unknown-card idx})))
    {:card/id cid}))


(defn- read-card-list
  "Read a count (count-bits wide) then N card objects."
  [r count-bits]
  (let [n (check! (safe-read r count-bits))]
    (vec (repeatedly n #(read-card-obj r)))))


(defn- read-permanent
  "Read one battlefield permanent."
  [r]
  (let [card-idx (check! (safe-read r 7))
        cid      (card-index/decode card-idx)]
    (when (nil? cid) (throw (ex-info (str "Unknown card at index " card-idx) {:unknown-card card-idx})))
    (let [tapped   (= 1 (check! (safe-read r 1)))
          has-ctr  (= 1 (check! (safe-read r 1)))
          counters (if has-ctr
                     (let [cnt (check! (safe-read r 3))]
                       (into {} (repeatedly cnt
                                            (fn []
                                              [(get counter-types (check! (safe-read r 3)) :unknown)
                                               (check! (safe-read r 8))]))))
                     {})
          has-grt  (= 1 (check! (safe-read r 1)))
          grants   (if has-grt (read-edn-blob r) [])]
      {:card/id         cid
       :object/tapped   tapped
       :object/counters counters
       :object/grants   grants})))


(defn- read-battlefield
  "Read battlefield: 6-bit count then each permanent."
  [r]
  (let [n (check! (safe-read r 6))]
    (vec (repeatedly n #(read-permanent r)))))


(defn- read-player-zones
  "Read all five zones for one player."
  [r]
  {:hand        (read-card-list r 6)
   :battlefield (read-battlefield r)
   :graveyard   (read-card-list r 6)
   :exile       (read-card-list r 6)
   :library     (read-card-list r 7)})


;; ---------------------------------------------------------------------------
;; Mana decoder

(defn- read-mana
  "Read 6 mana colors × 4 bits each."
  [r]
  (into {} (map (fn [color] [color (check! (safe-read r 4))]) mana-colors)))


;; ---------------------------------------------------------------------------
;; Stack decoder

(defn- read-stack-item
  "Read one stack item."
  [r p1-player-id]
  (let [type-idx (check! (safe-read r 3))
        card-idx (check! (safe-read r 7))
        ctrl-bit (check! (safe-read r 1))
        is-copy  (check! (safe-read r 1))
        has-tgt  (check! (safe-read r 1))
        item-type   (get stack-types type-idx :spell)
        card-id     (card-index/decode card-idx)
        controller  (if (= 0 ctrl-bit) p1-player-id p2-id)
        targets     (when (= 1 has-tgt) (read-edn-blob r))
        has-x       (check! (safe-read r 1))
        chosen-x    (when (= 1 has-x) (check! (safe-read r 8)))]
    (cond-> {:stack-item/type       item-type
             :stack-item/controller controller
             :stack-item/is-copy    (= 1 is-copy)}
      card-id  (assoc :card/id card-id)
      targets  (assoc :stack-item/targets targets)
      chosen-x (assoc :stack-item/chosen-x chosen-x))))


(defn- read-stack
  "Read all stack items."
  [r p1-player-id]
  (let [n (check! (safe-read r 4))]
    (vec (repeatedly n #(read-stack-item r p1-player-id)))))


;; ---------------------------------------------------------------------------
;; Header decoder

(defn- read-header
  "Read the fixed header. Version must already be validated before calling.
   Reads from flags onward (version already consumed)."
  [r version]
  (let [flags       (check! (safe-read r 4))
        p1-life-raw (check! (safe-read r 8))
        p2-life-raw (check! (safe-read r 8))
        p1-storm    (check! (safe-read r 6))
        p2-storm    (check! (safe-read r 6))
        turn        (check! (safe-read r 8))
        phase-idx   (check! (safe-read r 4))
        step-idx    (check! (safe-read r 4))
        active-bit  (check! (safe-read r 1))
        prio-bit    (check! (safe-read r 1))
        p1-mana     (read-mana r)
        p2-mana     (read-mana r)
        p1-land     (check! (safe-read r 2))
        p2-land     (check! (safe-read r 2))]
    {:version    version   ; caller-provided, already validated
     :flags      flags
     :p1-life    (- p1-life-raw 128)
     :p2-life    (- p2-life-raw 128)
     :p1-storm   p1-storm
     :p2-storm   p2-storm
     :turn       turn
     :phase      (get phases phase-idx :main1)
     :step       (when (not= step-idx 0xF) (get phases step-idx))
     :active-bit active-bit
     :prio-bit   prio-bit
     :p1-mana    p1-mana
     :p2-mana    p2-mana
     :p1-land    p1-land
     :p2-land    p2-land}))


;; ---------------------------------------------------------------------------
;; Public API

(defn decode-snapshot
  "Decode a base64url string (from encoder/encode-snapshot) to a portable map.

   Returns {:error \"message\"} for any malformed input:
   - empty or invalid base64url  → {:error \"Invalid encoding\"}
   - unknown version             → {:error \"Unsupported snapshot version N\"}
   - truncated data              → {:error \"Truncated snapshot\"}
   - unknown card index          → {:error \"Unknown card at index N\"}"
  [s]
  (if (or (nil? s) (= "" s))
    {:error "Invalid encoding"}
    (try
      (let [buf     (bits/base64url-decode s)
            r       (make-reader buf)
            version (check! (safe-read r 4))]
        (when (not= 1 version)
          (throw (ex-info (str "Unsupported snapshot version " version)
                          {:bad-version version})))
        (let [hdr        (read-header r version)
              flags      (:flags hdr)
              has-stack? (pos? (bit-and flags 1))
              active-id  (if (= 0 (:active-bit hdr)) p1-id p2-id)
              prio-id    (if (= 0 (:prio-bit hdr)) p1-id p2-id)
              p1-zones   (read-player-zones r)
              p2-zones   (read-player-zones r)
              stack      (if has-stack?
                           (read-stack r p1-id)
                           [])]
          {:game/turn            (:turn hdr)
           :game/phase           (:phase hdr)
           :game/step            (:step hdr)
           :game/active-player   active-id
           :game/priority        prio-id
           :game/winner          nil
           :game/loss-condition  nil
           :game/auto-mode       nil
           :game/human-player-id p1-id
           :players
           {p1-id (merge p1-zones
                         {:player/life           (:p1-life hdr)
                          :player/mana-pool      (:p1-mana hdr)
                          :player/storm-count    (:p1-storm hdr)
                          :player/land-plays-left (:p1-land hdr)
                          :player/max-hand-size  7
                          :player/grants         []})
            p2-id (merge p2-zones
                         {:player/life           (:p2-life hdr)
                          :player/mana-pool      (:p2-mana hdr)
                          :player/storm-count    (:p2-storm hdr)
                          :player/land-plays-left (:p2-land hdr)
                          :player/max-hand-size  7
                          :player/grants         []})}
           :stack stack}))
      (catch ExceptionInfo e
        (let [data (ex-data e)]
          (cond
            (:truncated data)    {:error "Truncated snapshot"}
            (:bad-version data)  {:error (str "Unsupported snapshot version " (:bad-version data))}
            (:unknown-card data) {:error (str "Unknown card at index " (:unknown-card data))}
            :else                {:error (ex-message e)})))
      (catch :default _
        {:error "Invalid encoding"}))))
