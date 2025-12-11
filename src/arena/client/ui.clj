(ns arena.client.ui
  (:require [quil.core :as q]
            [arena.client.state :as state]
            [arena.shared :as shared]
            [arena.client.websocket :as ws]))

;; Константы для UI
(def ui-colors
  {:background [40 40 80 200]
   :text [255 255 255]
   :text-secondary [200 200 255]
   :success [0 255 0]
   :warning [255 165 0]
   :danger [255 0 0]
   :boss-health [128 0 128]
   :boss-health-low [255 0 0]
   :boss-health-critical [255 50 50]
   :player-health [0 255 0]
   :buff-speed [0 0 255]
   :buff-damage [255 165 0]})

(defn apply-color [[r g b] & [a]]
  (if a
    (q/fill r g b a)
    (q/fill r g b)))

(defn draw-panel [x y width height & [color]]
  (let [[r g b] (or color (:background ui-colors))]
    (q/fill r g b 200)
    (q/stroke 255 255 255 100)
    (q/stroke-weight 1)
    (q/rect x y width height 5)
    (q/no-stroke)))

(defn draw-text [text x y & {:keys [size color align vertical-align]
                            :or {size 14 color (:text ui-colors) align :left vertical-align :top}}]
  (apply-color color)
  (q/text-size size)
  (q/text-align align vertical-align)
  (q/text text x y))

(defn draw-progress-bar [x y width height value max-value & [color]]
  (let [percent (/ value max-value)
        fill-width (* width percent)
        bar-color (cond
                   (< percent 0.2) (:boss-health-critical ui-colors)
                   (< percent 0.5) (:boss-health-low ui-colors)
                   color color
                   :else (:boss-health ui-colors))]
    
    ;; Фон полоски
    (apply-color [50 50 50])
    (q/rect x y width height 3)
    
    ;; Заполнение
    (apply-color bar-color)
    (q/rect x y fill-width height 3)
    
    ;; Обводка
    (q/no-fill)
    (q/stroke 255 255 255 150)
    (q/stroke-weight 1)
    (q/rect x y width height 3)
    (q/no-stroke)))

(defn draw-connection-status []
  (let [status (state/get-connection-status)
        game-info (state/get-comprehensive-game-info)]
    (draw-panel 10 10 200 60)
    
    (case status
      :connected (draw-text "✅ Connected to Server" 20 20 :color (:success ui-colors))
      :disconnected (draw-text "❌ Disconnected" 20 20 :color (:danger ui-colors))
      :error (draw-text "⚠️ Connection Error" 20 20 :color (:warning ui-colors))
      (draw-text "🔄 Connecting..." 20 20 :color (:text-secondary ui-colors)))
    
    (when-let [self-id (:self-id game-info)]
      (draw-text (str "Player: " (name self-id)) 20 40 :color (:text-secondary ui-colors)))
    
    (draw-text (str "Ping: " (:network-latency (:performance-stats (state/get-debug-info))) "ms") 
               20 55 :size 12 :color (:text-secondary ui-colors))))

(defn draw-game-stats []
  (let [stats (state/get-game-stats)
        game-info (state/get-comprehensive-game-info)]
    (draw-panel 10 80 200 120)
    
    (draw-text "GAME STATS" 20 95 :color (:text ui-colors) :size 16)
    (draw-text (str "FPS: " (:fps stats)) 20 115)
    (draw-text (str "Players: " (:players-alive game-info) "/" (:players-total game-info)) 20 130)
    (draw-text (str "Bullets: " (:bullets-count game-info)) 20 145)
    (draw-text (str "Bonuses: " (:bonuses-count game-info)) 20 160)
    (draw-text (str "Game Time: " (-> (:game-time game-info) (/ 1000) int) "s") 20 175)
    (draw-text (str "Packets: " (:packets-received stats)) 20 190)))

(defn draw-player-stats []
  (let [game-info (state/get-comprehensive-game-info)
        player (:player game-info)
        ranking (:ranking game-info)]
    (when player
      (draw-panel 10 (- shared/arena-height 180) 250 170)
      
      (draw-text "PLAYER STATS" 20 (- shared/arena-height 165) :color (:text ui-colors) :size 16)
      
      ;; HP игрока
      (let [hp-percent (/ (:hp player) (:max-hp player))]
        (draw-text (str "HP: " (:hp player) "/" (:max-hp player)) 20 (- shared/arena-height 145))
        (draw-progress-bar 20 (- shared/arena-height 135) 150 8 (:hp player) (:max-hp player) (:player-health ui-colors)))
      
      (draw-text (str "Score: " (:score player)) 20 (- shared/arena-height 120))
      (draw-text (str "Position: " (int (:x (:position player))) ", " (int (:y (:position player)))) 
                 20 (- shared/arena-height 105))
      
      ;; Ранг игрока
      (when ranking
        (draw-text (str "Rank: " (:rank ranking) "/" (:total ranking)) 20 (- shared/arena-height 90)))
      
      ;; Баффы
      (when (:has-speed-buff? player)
        (apply-color (:buff-speed ui-colors))
        (q/text "⚡ SPEED BOOST ACTIVE!" 20 (- shared/arena-height 75)))
      
      (when (:has-damage-buff? player)
        (apply-color (:buff-damage ui-colors))
        (q/text "💥 DAMAGE BOOST ACTIVE!" 20 (- shared/arena-height 60)))
      
      ;; Состояние смерти
      (when (not (:alive? player))
        (apply-color (:danger ui-colors))
        (q/text-size 16)
        (q/text "💀 YOU ARE DEAD - Respawning..." 20 (- shared/arena-height 45)))
      
      (apply-color (:text ui-colors)))))

(defn draw-boss-info []
  (let [game-info (state/get-comprehensive-game-info)
        boss (:boss game-info)]
    (when boss
      (let [panel-width 400
            panel-height 60
            x (- (/ shared/arena-width 2) (/ panel-width 2))
            y 10
            hp-percent (/ (:hp boss) (:max-hp boss))]
        
        (draw-panel x y panel-width panel-height)
        
        ;; Заголовок босса
        (draw-text "🔥 ARENA BOSS" (+ x 10) (+ y 15) :color (:text ui-colors) :size 18)
        
        ;; Полоска HP босса
        (let [bar-width 300
              bar-height 20
              bar-x (+ x 90)
              bar-y (+ y 20)]
          (draw-progress-bar bar-x bar-y bar-width bar-height (:hp boss) (:max-hp boss))
          
          ;; Текст HP
          (draw-text (str (:hp boss) "/" (:max-hp boss) " HP") 
                     (+ bar-x (/ bar-width 2)) (+ bar-y (/ bar-height 2)) 
                     :align :center :vertical-align :center :size 12))
        
        ;; Предупреждения при низком HP
        (when (:very-low-hp? boss)
          (let [pulse (-> (System/currentTimeMillis) (/ 200) (mod 255) int)]
            (apply-color (:danger ui-colors) pulse)
            (draw-text "⚡ BOSS IS NEARLY DEFEATED! ⚡" 
                       (/ shared/arena-width 2) (+ y 45) 
                       :align :center :size 14)))
        
        ;; Сообщение о смерти босса
        (when (not (:alive? boss))
          (apply-color (:success ui-colors))
          (draw-text "🎉 BOSS DEFEATED! VICTORY! 🎉" 
                     (/ shared/arena-width 2) (+ y 45) 
                     :align :center :size 16))))))

(defn draw-controls-info []
  (let [panel-width 220
        panel-height 140
        x (- shared/arena-width panel-width 10)
        y 10]
    
    (draw-panel x y panel-width panel-height)
    
    (draw-text "CONTROLS" (+ x 10) (+ y 15) :color (:text ui-colors) :size 16)
    (draw-text "WASD: Move" (+ x 10) (+ y 35))
    (draw-text "Shift: Sprint" (+ x 10) (+ y 50))
    (draw-text "Arrows: Shoot" (+ x 10) (+ y 65))
    (draw-text "Mouse: Aim & Shoot" (+ x 10) (+ y 80))
    (draw-text "Space: Quick Shoot" (+ x 10) (+ y 95))
    (draw-text "Ctrl+R: Reconnect" (+ x 10) (+ y 110))
    (draw-text "F3: Debug Info" (+ x 10) (+ y 125))))

(defn draw-focus-indicator []
  (let [panel-width 210
        panel-height 30
        x (- shared/arena-width panel-width 10)
        y (- shared/arena-height panel-height 10)
        focused (state/get-window-focused)]
    
    (if focused
      (do
        (draw-panel x y panel-width panel-height (:success ui-colors))
        (draw-text "WINDOW FOCUSED ✓" 
                   (+ x (/ panel-width 2)) (+ y (/ panel-height 2))
                   :align :center :vertical-align :center :color (:text ui-colors)))
      (do
        (draw-panel x y panel-width panel-height (:danger ui-colors))
        (draw-text "CLICK TO FOCUS!" 
                   (+ x (/ panel-width 2)) (+ y (/ panel-height 2))
                   :align :center :vertical-align :center :color (:text ui-colors))))))

(defn draw-network-info []
  (when (= (state/get-connection-status) :connected)
    (let [game-info (state/get-comprehensive-game-info)]
      (draw-text (str "👥 Online: " (:players-total game-info) " players") 
                 (/ shared/arena-width 2) 75 
                 :align :center :color (:text-secondary ui-colors))
      
      (draw-text (str "🎯 Alive: " (:players-alive game-info) " players") 
                 (/ shared/arena-width 2) 95 
                 :align :center :color (:text-secondary ui-colors)))))

(defn draw-debug-info []
  (when (:show-debug? (state/get-debug-info))
    (let [game-info (state/get-comprehensive-game-info)
          debug (state/get-debug-info)
          stats (state/get-game-stats)
          panel-width 300
          panel-height 200
          x 10
          y 210]
      
      (draw-panel x y panel-width panel-height [0 0 0 200])
      
      (draw-text "DEBUG INFORMATION" (+ x 10) (+ y 15) :color (:text ui-colors) :size 16)
      
      (draw-text (str "FPS: " (:fps stats)) (+ x 10) (+ y 35))
      (draw-text (str "Memory: " (-> (Runtime/getRuntime) (.totalMemory) (- (.freeMemory (Runtime/getRuntime))) (/ 1024 1024) int) "MB") 
                 (+ x 10) (+ y 50))
      (draw-text (str "Network Latency: " (:network-latency debug) "ms") (+ x 10) (+ y 65))
      (draw-text (str "Packets Received: " (:packets-received stats)) (+ x 10) (+ y 80))
      (draw-text (str "Game Time: " (:game-time game-info) "ms") (+ x 10) (+ y 95))
      (draw-text (str "Players: " (count (state/get-players))) (+ x 10) (+ y 110))
      (draw-text (str "Bullets: " (count (state/get-bullets))) (+ x 10) (+ y 125))
      (draw-text (str "Bonuses: " (count (state/get-bonuses))) (+ x 10) (+ y 140))
      (draw-text (str "Boss Position: " (int (:x (:position (:boss game-info)))) ", " 
                     (int (:y (:position (:boss game-info))))) 
                 (+ x 10) (+ y 155))
      (draw-text (str "Self Position: " (int (:x (:position (:player game-info)))) ", " 
                     (int (:y (:position (:player game-info))))) 
                 (+ x 10) (+ y 170))
      (draw-text "Press F3 to hide debug" (+ x 10) (+ y 185) :size 12 :color (:text-secondary ui-colors)))))

(defn draw-victory-screen []
  (let [boss (get (state/get-players) "boss")]
    (when (and boss (:dead boss))
      ;; Затемнение фона
      (apply-color [0 0 0 180])
      (q/rect 0 0 shared/arena-width shared/arena-height)
      
      ;; Основное сообщение
      (draw-text "🎉 VICTORY! 🎉" 
                 (/ shared/arena-width 2) (- (/ shared/arena-height 2) 60)
                 :align :center :size 48 :color [255 215 0])
      
      (draw-text "The Arena Boss has been defeated!" 
                 (/ shared/arena-width 2) (- (/ shared/arena-height 2) 10)
                 :align :center :size 24 :color (:text ui-colors))
      
      ;; Статистика игрока
      (when-let [self (state/get-self-player)]
        (draw-text (str "Your Score: " (:score self)) 
                   (/ shared/arena-width 2) (+ (/ shared/arena-height 2) 30)
                   :align :center :size 20 :color (:success ui-colors)))
      
      ;; Ранг
      (when-let [ranking (state/get-self-ranking)]
        (draw-text (str "Final Rank: " (:rank ranking) " of " (:total ranking)) 
                   (/ shared/arena-width 2) (+ (/ shared/arena-height 2) 60)
                   :align :center :size 18 :color (:text-secondary ui-colors)))
      
      ;; Инструкция
      (draw-text "Continue playing or restart the game" 
                 (/ shared/arena-width 2) (+ (/ shared/arena-height 2) 100)
                 :align :center :size 16 :color (:text-secondary ui-colors)))))

(defn draw-game-over-screen []
  (let [self (state/get-self-player)]
    (when (and self (:dead self))
      ;; Полупрозрачный черный фон
      (apply-color [0 0 0 150])
      (q/rect 0 0 shared/arena-width shared/arena-height)
      
      ;; Сообщение о смерти
      (draw-text "💀 YOU DIED" 
                 (/ shared/arena-width 2) (- (/ shared/arena-height 2) 40)
                 :align :center :size 36 :color (:danger ui-colors))
      
      (draw-text "Waiting for respawn..." 
                 (/ shared/arena-width 2) (- (/ shared/arena-height 2) 5)
                 :align :center :size 20 :color (:text ui-colors))
      
      ;; Таймер возрождения
      (let [respawn-time 3000 ; 3 секунды
            time-since-death (- (System/currentTimeMillis) (:dead-since self))
            time-left (- respawn-time time-since-death)
            percent-left (/ time-left respawn-time)]
        (when (pos? time-left)
          (let [bar-width 300
                bar-height 20
                bar-x (- (/ shared/arena-width 2) (/ bar-width 2))
                bar-y (+ (/ shared/arena-height 2) 30)]
            (draw-progress-bar bar-x bar-y bar-width bar-height time-left respawn-time (:warning ui-colors))
            (draw-text (str "Respawning in " (int (/ time-left 1000)) "s") 
                       (/ shared/arena-width 2) (+ bar-y (/ bar-height 2))
                       :align :center :vertical-align :center :size 14)))))))

(defn draw-ui [current-time]
  ;; Основные элементы UI
  (draw-connection-status)
  (draw-game-stats)
  (draw-player-stats)
  (draw-boss-info)
  (draw-controls-info)
  (draw-focus-indicator)
  (draw-network-info)
  
  ;; Отладочная информация (если включена)
  (draw-debug-info)
  
  ;; Экранные сообщения (поверх всего)
  (draw-victory-screen)
  (draw-game-over-screen))
