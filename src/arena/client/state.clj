(ns arena.client.state
  (:require [clojure.core.async :as async]
            [arena.shared :as shared]))

;; Атомы состояния игры
(defonce game-state (atom {:players {} 
                          :bullets [] 
                          :bonuses [] 
                          :self-id nil
                          :game-mode :multiplayer
                          :last-update (System/currentTimeMillis)}))

(defonce connection-status (atom :disconnected))
(defonce keys-pressed (atom #{}))
(defonce window-focused (atom true))
(defonce game-stats (atom {:fps 0 
                          :last-frame-time 0 
                          :packets-received 0
                          :boss-alive? false
                          :boss-hp 0
                          :boss-max-hp 1000
                          :game-time 0
                          :players-alive 0
                          :players-total 0}))
(defonce debug-info (atom {:show-debug? false
                          :performance-stats {}
                          :network-latency 0
                          :last-ping-time 0}))

;; ============================================================================
;; КОНСТАНТЫ (если их нет в shared)
;; ============================================================================

(def max-hp 100)
(def arena-width 800)
(def arena-height 600)

;; ============================================================================
;; ГЕТТЕРЫ ДЛЯ СОСТОЯНИЯ ИГРЫ
;; ============================================================================

(defn get-game-state [] @game-state)
(defn get-players [] (:players @game-state))
(defn get-bullets [] (:bullets @game-state))
(defn get-bonuses [] (:bonuses @game-state))
(defn get-self-id [] (:self-id @game-state))
(defn get-game-mode [] (:game-mode @game-state))

;; Получение конкретного игрока
(defn get-self-player [] 
  (get (:players @game-state) (:self-id @game-state)))

(defn get-player-by-id [player-id]
  (get (:players @game-state) player-id))

(defn get-boss []
  (get (:players @game-state) "boss"))

;; Состояние подключения и ввода
(defn get-connection-status [] @connection-status)
(defn get-keys-pressed [] @keys-pressed)
(defn get-window-focused [] @window-focused)

;; Статистика игры
(defn get-game-stats [] @game-stats)
(defn get-debug-info [] @debug-info)

;; ============================================================================
;; СПЕЦИАЛИЗИРОВАННЫЕ ГЕТТЕРЫ ДЛЯ БОССА
;; ============================================================================

(defn is-boss-alive? []
  (let [boss (get-boss)]
    (and boss (not (:dead boss)) (> (:hp boss) 0))))

(defn get-boss-hp []
  (let [boss (get-boss)]
    (if boss (:hp boss) 0)))

(defn get-boss-max-hp []
  (let [boss (get-boss)]
    (if boss 1000 0)))

(defn get-boss-position []
  (let [boss (get-boss)]
    (if boss 
      {:x (:x boss) :y (:y boss)}
      {:x 400 :y 300})))

(defn get-boss-status []
  (let [boss (get-boss)]
    (if boss
      {:alive? (not (:dead boss))
       :hp (:hp boss)
       :max-hp 1000
       :position {:x (:x boss) :y (:y boss)}
       :score (:score boss 0)
       :has-speed-buff? (boolean (:speed-buff boss))
       :has-damage-buff? (boolean (:damage-buff boss))}
      {:alive? false
       :hp 0
       :max-hp 1000
       :position {:x 400 :y 300}
       :score 0
       :has-speed-buff? false
       :has-damage-buff? false})))

;; ============================================================================
;; СТАТИСТИКА ИГРОКОВ
;; ============================================================================

(defn get-players-count []
  (count (get-players)))

(defn get-alive-players-count []
  (->> (get-players)
       (filter (fn [[_ player]] (not (:dead player))))
       count))

(defn get-player-rankings []
  (->> (get-players)
       (map (fn [[id player]] 
              {:id id
               :score (:score player 0)
               :hp (:hp player)
               :dead? (:dead player false)
               :is-boss? (= id "boss")}))
       (sort-by :score >)))

(defn get-self-ranking []
  (let [self-id (get-self-id)
        rankings (get-player-rankings)
        self-index (.indexOf (map :id rankings) self-id)]
    (if (>= self-index 0)
      {:rank (+ self-index 1)
       :total (count rankings)
       :player (nth rankings self-index)}
      nil)))

;; ============================================================================
;; СЕТТЕРЫ ОСНОВНОГО СОСТОЯНИЯ
;; ============================================================================

(defn update-game-state [updates]
  (swap! game-state merge updates)
  (update-derived-stats))

(defn set-players [players]
  (swap! game-state assoc :players players)
  (update-derived-stats))

(defn set-bullets [bullets]
  (swap! game-state assoc :bullets bullets))

(defn set-bonuses [bonuses]
  (swap! game-state assoc :bonuses bonuses))

(defn set-self-id [self-id]
  (swap! game-state assoc :self-id (keyword self-id)))

(defn set-game-mode [mode]
  (swap! game-state assoc :game-mode mode))

;; ============================================================================
;; СЕТТЕРЫ СОСТОЯНИЯ ПОДКЛЮЧЕНИЯ И ВВОДА
;; ============================================================================

(defn set-connection-status [status]
  (reset! connection-status status))

(defn add-key-pressed [key]
  (swap! keys-pressed conj key))

(defn remove-key-pressed [key]
  (swap! keys-pressed disj key))

(defn clear-keys-pressed []
  (reset! keys-pressed #{}))

(defn set-window-focused [focused]
  (reset! window-focused focused))

;; ============================================================================
;; ОБНОВЛЕНИЕ СТАТИСТИКИ И ПРОИЗВОДНЫХ ДАННЫХ
;; ============================================================================

(defn update-fps [current-time]
  (let [last-time (:last-frame-time @game-stats)
        fps (if (zero? last-time) 
              0 
              (int (/ 1000 (- current-time last-time))))]
    (swap! game-stats assoc 
           :fps fps 
           :last-frame-time current-time
           :game-time (+ (:game-time @game-stats) (- current-time last-time)))))

(defn increment-packets-received []
  (swap! game-stats update :packets-received inc))

(defn update-derived-stats []
  (let [players (get-players)
        alive-count (get-alive-players-count)
        total-count (get-players-count)
        boss-status (get-boss-status)]
    
    (swap! game-stats assoc
           :boss-alive? (:alive? boss-status)
           :boss-hp (:hp boss-status)
           :boss-max-hp (:max-hp boss-status)
           :players-alive alive-count
           :players-total total-count)))

(defn update-network-latency [latency]
  (swap! debug-info assoc :network-latency latency))

(defn update-performance-stats [stats]
  (swap! debug-info assoc :performance-stats stats))

;; ============================================================================
;; ОТЛАДОЧНЫЕ ФУНКЦИИ
;; ============================================================================

(defn toggle-debug-info []
  (swap! debug-info update :show-debug? not))

(defn set-debug-info [enabled?]
  (swap! debug-info assoc :show-debug? enabled?))

(defn add-debug-message [message]
  (when (:show-debug? @debug-info)
    (println "[DEBUG]" message)))

;; ============================================================================
;; ФУНКЦИИ ДЛЯ РАБОТЫ СО ВРЕМЕНЕМ И АНИМАЦИЯМИ
;; ============================================================================

(defn get-game-time []
  (:game-time @game-stats))

(defn get-time-since-last-update []
  (- (System/currentTimeMillis) (:last-update @game-state)))

(defn update-last-update-time []
  (swap! game-state assoc :last-update (System/currentTimeMillis)))

;; ============================================================================
;; ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ДЛЯ ПРОВЕРКИ СОСТОЯНИЯ
;; ============================================================================

(defn is-game-active? []
  (and (= (get-connection-status) :connected)
       (get-self-id)
       (get-self-player)))

(defn can-player-move? []
  (let [self (get-self-player)]
    (and self (not (:dead self)))))

(defn can-player-shoot? []
  (let [self (get-self-player)]
    (and self (not (:dead self)))))

(defn is-boss-low-hp? []
  (let [boss (get-boss)]
    (and boss (< (:hp boss) 300))))

(defn is-boss-very-low-hp? []
  (let [boss (get-boss)]
    (and boss (< (:hp boss) 100))))

;; ============================================================================
;; ФУНКЦИИ ДЛЯ РАБОТЫ С ПУЛЯМИ И БОНУСАМИ
;; ============================================================================

(defn get-bullets-by-owner [owner-id]
  (filter #(= (:owner %) owner-id) (get-bullets)))

(defn get-boss-bullets []
  (get-bullets-by-owner "boss"))

(defn get-player-bullets []
  (let [self-id (get-self-id)]
    (get-bullets-by-owner self-id)))

(defn get-nearby-bonuses [x y radius]
  (filter (fn [bonus]
            (let [dx (- (:x bonus) x)
                  dy (- (:y bonus) y)
                  distance (Math/sqrt (+ (* dx dx) (* dy dy)))]
              (< distance radius)))
          (get-bonuses)))

;; ============================================================================
;; ФУНКЦИИ СБРОСА И ПЕРЕИНИЦИАЛИЗАЦИИ
;; ============================================================================

(defn reset-game-state []
  (reset! game-state {:players {} 
                     :bullets [] 
                     :bonuses [] 
                     :self-id nil
                     :game-mode :multiplayer
                     :last-update (System/currentTimeMillis)})
  (reset! keys-pressed #{})
  (reset! game-stats {:fps 0 
                     :last-frame-time 0 
                     :packets-received 0
                     :boss-alive? false
                     :boss-hp 0
                     :boss-max-hp 1000
                     :game-time 0
                     :players-alive 0
                     :players-total 0}))

(defn partial-reset []
  "Сброс только игрового состояния, без сброса подключения"
  (swap! game-state assoc 
         :bullets []
         :bonuses [])
  (swap! game-stats assoc
         :boss-hp (get-boss-hp)
         :boss-alive? (is-boss-alive?)))

;; ============================================================================
;; ЭКСПОРТИРУЕМЫЕ ФУНКЦИИ ДЛЯ ВНЕШНЕГО ИСПОЛЬЗОВАНИЯ
;; ============================================================================

(defn initialize-state []
  "Инициализация состояния при запуске клиента"
  (reset-game-state)
  (println "🎮 Game state initialized"))

(defn get-comprehensive-game-info []
  "Возвращает полную информацию о состоянии игры для UI"
  (let [self (get-self-player)
        boss (get-boss)
        stats (get-game-stats)]
    {:connection {:status (get-connection-status)
                  :self-id (get-self-id)
                  :window-focused (get-window-focused)}
     :player {:alive? (and self (not (:dead self)))
              :hp (if self (:hp self) 0)
              :max-hp max-hp
              :score (if self (:score self 0) 0)
              :position (if self {:x (:x self) :y (:y self)} {:x 0 :y 0})
              :has-speed-buff? (boolean (:speed-buff self))
              :has-damage-buff? (boolean (:damage-buff self))}
     :boss {:alive? (is-boss-alive?)
            :hp (get-boss-hp)
            :max-hp 1000
            :low-hp? (is-boss-low-hp?)
            :very-low-hp? (is-boss-very-low-hp?)
            :position (get-boss-position)}
     :game {:fps (:fps stats)
            :players-alive (:players-alive stats)
            :players-total (:players-total stats)
            :bullets-count (count (get-bullets))
            :bonuses-count (count (get-bonuses))
            :game-time (:game-time stats)}
     :ranking (get-self-ranking)}))

;; Инициализация при загрузке namespace
(initialize-state)
