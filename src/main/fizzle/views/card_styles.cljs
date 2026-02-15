(ns fizzle.views.card-styles)


(defn get-type-border-class
  "Get border color class based on card types.
   Uses classification priority: creature > land > other.
   Returns tapped variant if tapped? is true."
  [types tapped?]
  (let [type-set (set (or types []))
        base-class (cond
                     (contains? type-set :creature) "border-type-creature"
                     (contains? type-set :land) "border-type-land"
                     (contains? type-set :enchantment) "border-type-enchantment"
                     :else "border-type-artifact")]  ; default for artifacts and other
    (if tapped?
      (str base-class "-tapped")
      base-class)))


(defn get-color-identity-bg-class
  "Get background tint class based on card colors.
   Single color → specific tint, 2+ colors → multicolor (gold), 0 colors → default."
  [colors]
  (let [color-set (set (or colors []))
        color-count (count color-set)]
    (cond
      (= 0 color-count) "bg-perm-bg"  ; colorless
      (>= color-count 2) "bg-color-identity-multicolor"  ; multicolor (gold)
      (contains? color-set :white) "bg-color-identity-white"
      (contains? color-set :blue) "bg-color-identity-blue"
      (contains? color-set :black) "bg-color-identity-black"
      (contains? color-set :red) "bg-color-identity-red"
      (contains? color-set :green) "bg-color-identity-green"
      :else "bg-perm-bg")))
