(ns arena.shared)

;; ============================================================================
;; КОНСТАНТЫ АРЕНЫ И ИГРОКОВ
;; ============================================================================

(def arena-width 800)
(def arena-height 600)
(def player-size 20)
(def player-speed 5)
(def max-hp 100)

;; ============================================================================
;; КОНСТАНТЫ БОССА
;; ============================================================================

(def boss-size 35) ; Босс больше обычных игроков
(def boss-max-hp 1000) ; Босс имеет 1000 HP
(def boss-speed 3) ; Босс движется немного медленнее
(def boss-damage 50) ; Босс наносит больше урона
(def boss-respawn-time 30000) ; 30 секунд до возрождения босса
(def boss-ai-tick-ms 500) ; Босс обновляет AI каждые 500 мс

;; ============================================================================
;; КОНСТАНТЫ СНАРЯДОВ
;; ============================================================================

(def bullet-speed 8)
(def bullet-size 6)
(def bullet-damage 25)
(def bullet-lifetime 2000) ; в миллисекундах
(def bullet-cooldown 300) ; кд между выстрелами игрока
(def boss-bullet-cooldown 800) ; кд между выстрелами босса

;; ============================================================================
;; КОНСТАНТЫ БОНУСОВ
;; ============================================================================

(def bonus-size 15)
(def bonus-lifetime 10000) ; 10 секунд
(def bonus-spawn-time 5000) ; спавн каждые 5 секунд
(def max-bonuses 3) ; максимальное количество бонусов одновременно

;; Типы бонусов
(def bonus-types 
  [{:type "health" :color [0 255 0] :value 50}
   {:type "speed" :color [0 0 255] :value 2} 
   {:type "damage" :color [255 0 0] :value 10}])

;; ============================================================================
;; КОНСТАНТЫ ИГРЫ
;; ============================================================================

(def game-tick-ms 50) ; 20 FPS (основной игровой цикл)
(def respawn-time 3000) ; время возрождения игроков
(def max-players 8) ; максимальное количество игроков

;; Длительность баффов (в миллисекундах)
(def speed-buff-duration 10000) ; 10 секунд
(def damage-buff-duration 10000) ; 10 секунд

;; ============================================================================
;; ЦВЕТА И ВИЗУАЛЬНЫЕ КОНСТАНТЫ
;; ============================================================================

(def colors
  {:background [40 40 80]
   :player [0 255 0]
   :enemy [255 0 0]
   :boss [128 0 128]
   :boss-low-hp [255 50 50]
   :boss-critical-hp [255 0 0]
   :bullet [255 255 0]
   :boss-bullet [255 0 0]
   :health-bonus [0 255 0]
   :speed-bonus [0 0 255]
   :damage-bonus [255 0 0]
   :ui-background [0 0 0 180]
   :ui-text [255 255 255]
   :ui-text-secondary [200 200 255]})

;; ============================================================================
;; НАЧАЛЬНОЕ СОСТОЯНИЕ ИГРЫ
;; ============================================================================

(def initial-state
  {:players {}      ;; player-id -> {:x :y :hp :score :last-shot :dead :dead-since :speed-buff :damage-buff}
   :bullets {}      ;; bullet-id -> {:x :y :dx :dy :owner :created-at}
   :bonuses {}      ;; bonus-id -> {:type :x :y :color :value :created-at}
   :next-bullet-id 0
   :next-bonus-id 0})

;; ============================================================================
;; ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ
;; ============================================================================

(defn random-spawn-position []
  {:x (rand-int (- arena-width player-size))
   :y (rand-int (- arena-height player-size))})

(defn random-boss-spawn-position []
  {:x (rand-int (- arena-width boss-size))
   :y (rand-int (- arena-height boss-size))})

(defn random-bonus-position []
  {:x (rand-int (- arena-width bonus-size))
   :y (rand-int (- arena-height bonus-size))})

(defn valid-coordinates? [x y & [size]]
  (let [entity-size (or size player-size)]
    (and (number? x) (number? y)
         (>= x 0) (<= x (- arena-width entity-size))
         (>= y 0) (<= y (- arena-height entity-size)))))

(defn distance [x1 y1 x2 y2]
  (Math/sqrt (+ (Math/pow (- x2 x1) 2) (Math/pow (- y2 y1) 2))))

(defn collides? [x1 y1 size1 x2 y2 size2]
  (let [half-size1 (/ size1 2)
        half-size2 (/ size2 2)
        center-x1 (+ x1 half-size1)
        center-y1 (+ y1 half-size1)
        center-x2 (+ x2 half-size2)
        center-y2 (+ y2 half-size2)]
    (< (distance center-x1 center-y1 center-x2 center-y2) 
       (+ half-size1 half-size2))))

(defn collides-with-player? [x y size players]
  (some (fn [[_ player]]
          (and (not (:dead player))
               (let [player-size (if (= (:id player) "boss") boss-size player-size)]
                 (collides? x y size 
                           (:x player) (:y player) player-size))))
        players))

(defn get-random-bonus-type []
  (rand-nth bonus-types))

(defn create-bonus [bonus-id]
  (let [bonus-type (get-random-bonus-type)]
    (merge {:id bonus-id
            :x (rand-int (- arena-width bonus-size))
            :y (rand-int (- arena-height bonus-size))
            :created-at (System/currentTimeMillis)}
           bonus-type)))

(defn create-bullet [bullet-id x y dx dy owner]
  {:id bullet-id
   :x x :y y
   :dx dx :dy dy
   :owner owner
   :created-at (System/currentTimeMillis)})

(defn create-player []
  (let [pos (random-spawn-position)]
    (merge pos
           {:hp max-hp
            :score 0
            :last-shot 0
            :dead false
            :dead-since 0
            :speed-buff nil
            :damage-buff nil})))

(defn create-boss []
  {:id "boss"
   :x 400 :y 300
   :hp boss-max-hp
   :score 0
   :last-shot 0
   :dead false
   :dead-since 0
   :speed-buff nil
   :damage-buff nil
   :type :boss})

;; ============================================================================
;; ФУНКЦИИ ДЛЯ РАБОТЫ С БАФФАМИ
;; ============================================================================

(defn apply-speed-buff [player]
  (assoc player :speed-buff 
         {:value (:value (first (filter #(= (:type %) "speed") bonus-types)))
          :expires (+ (System/currentTimeMillis) speed-buff-duration)}))

(defn apply-damage-buff [player]
  (assoc player :damage-buff 
         {:value (:value (first (filter #(= (:type %) "damage") bonus-types)))
          :expires (+ (System/currentTimeMillis) damage-buff-duration)}))

(defn apply-health-buff [player]
  (let [max-hp (if (= (:id player) "boss") boss-max-hp max-hp)]
    (update player :hp (fn [hp] (min max-hp (+ (or hp max-hp) 
                                              (:value (first (filter #(= (:type %) "health") bonus-types)))))))))

(defn has-active-buff? [player buff-type]
  (let [buff (get player buff-type)]
    (and buff (> (:expires buff) (System/currentTimeMillis)))))

(defn get-player-speed [player]
  (if (has-active-buff? player :speed-buff)
    (let [base-speed (if (= (:id player) "boss") boss-speed player-speed)]
      (* base-speed (:value (:speed-buff player))))
    (if (= (:id player) "boss") boss-speed player-speed)))

(defn get-bullet-damage [player]
  (if (has-active-buff? player :damage-buff)
    (let [base-damage (if (= (:id player) "boss") boss-damage bullet-damage)]
      (+ base-damage (:value (:damage-buff player))))
    (if (= (:id player) "boss") boss-damage bullet-damage)))

(defn get-bullet-cooldown [player]
  (if (= (:id player) "boss")
    boss-bullet-cooldown
    bullet-cooldown))

;; ============================================================================
;; ФУНКЦИИ ПРОВЕРКИ СОСТОЯНИЯ
;; ============================================================================

(defn can-shoot? [player]
  (let [now (System/currentTimeMillis)
        last-shot (:last-shot player 0)
        cooldown (get-bullet-cooldown player)]
    (and (not (:dead player))
         (>= (- now last-shot) cooldown))))

(defn should-respawn? [player]
  (and (:dead player)
       (not= (:id player) "boss") ; Босс не возрождается автоматически по этому правилу
       (> (- (System/currentTimeMillis) (:dead-since player)) respawn-time)))

(defn should-boss-respawn? [boss]
  (and boss 
       (:dead boss)
       (> (- (System/currentTimeMillis) (:dead-since boss)) boss-respawn-time)))

(defn is-bonus-expired? [bonus]
  (> (- (System/currentTimeMillis) (:created-at bonus)) bonus-lifetime))

(defn is-bullet-expired? [bullet]
  (> (- (System/currentTimeMillis) (:created-at bullet)) bullet-lifetime))

(defn is-boss-low-hp? [boss]
  (and boss (< (:hp boss) (* boss-max-hp 0.3))))

(defn is-boss-critical-hp? [boss]
  (and boss (< (:hp boss) (* boss-max-hp 0.1))))

;; ============================================================================
;; ГЕОМЕТРИЧЕСКИЕ ФУНКЦИИ
;; ============================================================================

(defn point-in-rect? [px py x y width height]
  (and (>= px x) (<= px (+ x width))
       (>= py y) (<= py (+ y height))))

(defn get-arena-center []
  {:x (/ arena-width 2) :y (/ arena-height 2)})

(defn get-arena-corners []
  [{:x 0 :y 0}
   {:x arena-width :y 0}
   {:x 0 :y arena-height}
   {:x arena-width :y arena-height}])

(defn get-player-center [player]
  (let [size (if (= (:id player) "boss") boss-size player-size)]
    {:x (+ (:x player) (/ size 2))
     :y (+ (:y player) (/ size 2))}))

;; ============================================================================
;; ФУНКЦИИ ДЛЯ РАБОТЫ С ЦВЕТАМИ
;; ============================================================================

(defn rgb [r g b]
  [r g b])

(defn rgba [r g b a]
  [r g b a])

(defn interpolate-color [color1 color2 factor]
  (mapv (fn [c1 c2] (+ c1 (* (- c2 c1) factor))) color1 color2))

(defn get-boss-color [boss]
  (cond
    (not boss) (:boss colors)
    (:dead boss) [100 100 100 150]
    (is-boss-critical-hp? boss) (:boss-critical-hp colors)
    (is-boss-low-hp? boss) (:boss-low-hp colors)
    :else (:boss colors)))

(defn get-bullet-color [bullet]
  (if (= (:owner bullet) "boss")
    (:boss-bullet colors)
    (:bullet colors)))

;; ============================================================================
;; МАТЕМАТИЧЕСКИЕ ФУНКЦИИ
;; ============================================================================

(defn clamp [value min-val max-val]
  (max min-val (min max-val value)))

(defn lerp [start end factor]
  (+ start (* (- end start) factor)))

(defn normalize-vector [dx dy]
  (let [magnitude (Math/sqrt (+ (* dx dx) (* dy dy)))]
    (if (zero? magnitude)
      [0 0]
      [(/ dx magnitude) (/ dy magnitude)])))

(defn calculate-direction [from-x from-y to-x to-y]
  (normalize-vector (- to-x from-x) (- to-y from-y)))

(defn random-direction []
  (let [angle (rand (* 2 Math/PI))]
    [(Math/cos angle) (Math/sin angle)]))

;; ============================================================================
;; ФУНКЦИИ ДЛЯ РАБОТЫ С ИГРОВЫМИ ОБЪЕКТАМИ
;; ============================================================================

(defn get-entity-size [entity]
  (cond
    (= (:id entity) "boss") boss-size
    (:type entity) player-size
    :else player-size))

(defn get-entity-max-hp [entity]
  (if (= (:id entity) "boss")
    boss-max-hp
    max-hp))

(defn is-boss? [entity]
  (or (= (:id entity) "boss")
      (= (:type entity) :boss)))

(defn filter-players-except-boss [players]
  (into {} (remove (fn [[id _]] (= id "boss")) players)))

(defn get-boss-from-players [players]
  (get players "boss"))

(defn calculate-distance-to-boss [x y players]
  (if-let [boss (get-boss-from-players players)]
    (distance x y (:x boss) (:y boss))
    ##Inf)) ; Если босса нет, возвращаем бесконечность

;; ============================================================================
;; ФУНКЦИИ ДЛЯ AI БОССА
;; ============================================================================

(defn find-closest-player [boss-x boss-y players]
  (let [other-players (filter-players-except-boss players)]
    (when (seq other-players)
      (let [closest (apply min-key (fn [[_ player]]
                                     (distance boss-x boss-y (:x player) (:y player)))
                           other-players)]
        (second closest)))))

(defn find-closest-bonus [boss-x boss-y bonuses & [bonus-type]]
  (let [filtered-bonuses (if bonus-type
                          (filter #(= (:type %) bonus-type) bonuses)
                          bonuses)]
    (when (seq filtered-bonuses)
      (apply min-key (fn [bonus]
                       (distance boss-x boss-y (:x bonus) (:y bonus)))
             filtered-bonuses))))

(defn calculate-boss-move-direction [boss-x boss-y target-x target-y]
  (normalize-vector (- target-x boss-x) (- target-y boss-y)))

(defn is-bullet-dangerous? [bullet-x bullet-y boss-x boss-y & [danger-radius]]
  (let [radius (or danger-radius 100)]
    (< (distance bullet-x bullet-y boss-x boss-y) radius)))

;; ============================================================================
;; УТИЛИТЫ ДЛЯ СЕТЕВОЙ КОММУНИКАЦИИ
;; ============================================================================

(defn prepare-game-state-for-broadcast [state]
  {:type "state"
   :players (:players state)
   :bullets (vals (:bullets state))
   :bonuses (vals (:bonuses state))})

(defn prepare-player-joined-message [player-id player]
  {:type "player-joined"
   :player-id player-id
   :player player})

(defn prepare-player-left-message [player-id]
  {:type "player-left"
   :player-id player-id})

(defn prepare-boss-info-message [boss]
  {:type "boss-info"
   :boss boss})

;; ============================================================================
;; КОНФИГУРАЦИЯ БАЛАНСА ИГРЫ
;; ============================================================================

(def game-balance
  {:player {:hp max-hp
            :speed player-speed
            :bullet-damage bullet-damage
            :bullet-cooldown bullet-cooldown}
   :boss {:hp boss-max-hp
          :speed boss-speed
          :bullet-damage boss-damage
          :bullet-cooldown boss-bullet-cooldown
          :respawn-time boss-respawn-time}
   :bullets {:speed bullet-speed
             :lifetime bullet-lifetime}
   :bonuses {:lifetime bonus-lifetime
             :spawn-time bonus-spawn-time
             :max-count max-bonuses}})

;; Экспортируем версию для отладки
(def version "2.0.0-boss")
