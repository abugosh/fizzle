(ns fizzle.views.card-styles)


(defn get-type-border-class
  "Get border color class based on card types.
   Uses classification priority: creature > land > other.
   Returns tapped variant if tapped? is true."
  [types tapped?]
  (let [type-set (set (or types []))
        base-class (cond
                     (contains? type-set :creature) "border-type-border-creature"
                     (contains? type-set :land) "border-type-border-land"
                     (contains? type-set :enchantment) "border-type-border-enchantment"
                     :else "border-type-border-artifact")]
    (if tapped?
      (str base-class "-tapped")
      base-class)))


(defn get-color-identity-bg-class
  "Get background tint class based on card colors and types.
   Single color → specific tint, 2+ colors → multicolor (gold),
   colorless land → brown, colorless other → silver."
  [colors types]
  (let [color-set (set (or colors []))
        color-count (count color-set)]
    (cond
      (>= color-count 2) "bg-identity-multicolor"
      (contains? color-set :white) "bg-identity-white"
      (contains? color-set :blue) "bg-identity-blue"
      (contains? color-set :black) "bg-identity-black"
      (contains? color-set :red) "bg-identity-red"
      (contains? color-set :green) "bg-identity-green"
      (contains? (set (or types [])) :land) "bg-identity-land"
      :else "bg-identity-colorless")))
