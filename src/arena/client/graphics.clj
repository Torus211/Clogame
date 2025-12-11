(ns arena.client.graphics
  (:require [quil.core :as q]
            [arena.client.state :as state]
            [arena.shared :as shared]))

(defn draw-player [player self? boss?]
  (let [x (:x player)
        y (:y player)
        hp (:hp player)
        max-hp (if boss? 1000 shared/max-hp)  ; Босс имеет 1000 HP
        dead? (:dead player)
        score (:score player 0)
        speed-buff? (:speed-buff player)
        damage-buff? (:damage-buff player)
        
        ;; Размер и цвет в зависимости от типа
        size (if boss? 35 shared/player-size)
        color (cond
                dead? [100 100 100 150]
                self? [0 255 0]
                boss? [128 0 128]  ; Фиолетовый для босса
                :else [255 0 0])
        
        ;; Позиции для полосок HP и текста
        hp-bar-width (if boss? 100 shared/player-size)
        hp-bar-height (if boss? 8 5)
        hp-bar-y (if boss? (- y 25) (- y 15))
        text-y (if boss? (- y 35) (- y 20))
        center-x (+ x (/ size 2))
        center-y (+ y (/ size 2))]
    
    ;; Основной квадрат игрока/босса
    (apply q/fill color)
    (q/rect x y size size)
    
    ;; Эффекты баффов
    (when speed-buff?
      (q/fill 0 0 255 100)
      (q/ellipse center-x center-y (+ size 8) (+ size 8)))
    
    (when damage-buff?
      (q/fill 255 165 0 100)
      (q/rect (- x 4) (- y 4) (+ size 8) (+ size 8)))
    
    ;; Полоска HP
    (when (not dead?)
      (let [hp-percent (/ hp max-hp)
            hp-width (* hp-bar-width hp-percent)
            hp-color (cond
                      (< hp-percent 0.2) [255 0 0]
                      (< hp-percent 0.5) [255 165 0]
                      :else [0 255 0])]
        
        ;; Фон полоски HP
        (q/fill 50 50 50)
        (q/rect x hp-bar-y hp-bar-width hp-bar-height)
        
        ;; Заполнение HP
        (apply q/fill hp-color)
        (q/rect x hp-bar-y hp-width hp-bar-height)
        
        ;; Текст HP для босса
        (when boss?
          (q/fill 255 255 255)
          (q/text-align :center :center)
          (q/text (str hp "/" max-hp) 
                  (+ x (/ hp-bar-width 2)) 
                  (+ hp-bar-y (/ hp-bar-height 2))))))
    
    ;; Имя и счет
    (q/fill 255 255 255)
    (q/text-align :center :bottom)
    (q/text (cond
              self? (str "YOU (" score ")")
              boss? (str "🔥 BOSS (" score ")")
              :else (str "Enemy (" score ")")) 
            center-x text-y)
    
    ;; Анимация мерцания для босса при низком HP
    (when (and boss? (not dead?) (< (/ hp max-hp) 0.3))
      (let [pulse (-> (System/currentTimeMillis)
                      (/ 200)
                      (mod 255)
                      int)]
        (q/fill 255 255 255 pulse)
        (q/rect (- x 2) (- y 2) (+ size 4) (+ size 4))))))

(defn draw-bullet [bullet]
  (let [x (:x bullet)
        y (:y bullet)
        owner (:owner bullet)
        boss-bullet? (= owner "boss")]  ; Пули босса другого цвета
    
    (if boss-bullet?
      (do
        ;; Пули босса - красные с эффектом
        (q/fill 255 0 0)
        (q/ellipse x y (+ shared/bullet-size 2) (+ shared/bullet-size 2))
        
        ;; Эффект свечения для пуль босса
        (q/fill 255 100 100 150)
        (q/ellipse x y (+ shared/bullet-size 6) (+ shared/bullet-size 6))
        
        ;; Анимация пуль босса
        (let [pulse (-> (System/currentTimeMillis)
                        (/ 100)
                        (mod 255)
                        int)]
          (q/fill 255 200 200 pulse)
          (q/ellipse x y (+ shared/bullet-size 10) (+ shared/bullet-size 10))))
      
      (do
        ;; Обычные пули
        (q/fill 255 255 0)
        (q/ellipse x y shared/bullet-size shared/bullet-size)
        
        ;; Эффект следа
        (q/fill 255 200 0 100)
        (q/ellipse x y 
                   (+ shared/bullet-size 4) 
                   (+ shared/bullet-size 4))))))

(defn draw-bonus [bonus]
  (let [x (:x bonus)
        y (:y bonus)
        bonus-type (:type bonus)
        center-x (+ x (/ shared/bonus-size 2))
        center-y (+ y (/ shared/bonus-size 2))]
    
    ;; Анимация пульсации для бонусов
    (let [pulse (-> (System/currentTimeMillis)
                    (/ 150)
                    (mod 255)
                    int)
          pulse-size (* (Math/sin (/ (System/currentTimeMillis) 500)) 3)]
      
      (case bonus-type
        "health" (do
                   (q/fill 0 255 0)
                   (q/rect x y shared/bonus-size shared/bonus-size)
                   
                   ;; Эффект свечения
                   (q/fill 0 200 0 100)
                   (q/rect (- x pulse-size) (- y pulse-size) 
                           (+ shared/bonus-size (* 2 pulse-size)) 
                           (+ shared/bonus-size (* 2 pulse-size)))
                   
                   (q/fill 255 255 255)
                   (q/text-align :center :center)
                   (q/text "H" center-x center-y))
        
        "speed" (do
                  (q/fill 0 0 255)
                  (q/triangle x y 
                             (+ x shared/bonus-size) y 
                             center-x 
                             (+ y shared/bonus-size))
                  
                  ;; Эффект свечения
                  (q/fill 0 0 200 100)
                  (q/triangle (- x pulse-size) (- y pulse-size)
                             (+ x shared/bonus-size pulse-size) (- y pulse-size)
                             center-x 
                             (+ y shared/bonus-size pulse-size))
                  
                  (q/fill 255 255 255)
                  (q/text-align :center :center)
                  (q/text "S" center-x center-y))
        
        "damage" (do
                   (q/fill 255 0 0)
                   (q/ellipse center-x center-y shared/bonus-size shared/bonus-size)
                   
                   ;; Эффект свечения
                   (q/fill 200 0 0 100)
                   (q/ellipse center-x center-y 
                              (+ shared/bonus-size (* 2 pulse-size)) 
                              (+ shared/bonus-size (* 2 pulse-size)))
                   
                   (q/fill 255 255 255)
                   (q/text-align :center :center)
                   (q/text "D" center-x center-y))))))

(defn draw-background-effects []
  ;; Тонкие сеточные линии для фона
  (q/stroke 100 100 100 50)
  (q/stroke-weight 0.5)
  
  (let [grid-size 50]
    (doseq [x (range 0 (inc shared/arena-width) grid-size)]
      (q/line x 0 x shared/arena-height))
    (doseq [y (range 0 (inc shared/arena-height) grid-size)]
      (q/line 0 y shared/arena-width y)))
  
  ;; Сброс стиля линий
  (q/no-stroke))

(defn draw-game-objects []
  ;; Фоновые эффекты
  (draw-background-effects)
  
  ;; Отрисовка бонусов
  (doseq [bonus (state/get-bonuses)]
    (draw-bonus bonus))
  
  ;; Отрисовка пуль
  (doseq [bullet (state/get-bullets)]
    (draw-bullet bullet))
  
  ;; Отрисовка игроков
  (let [players (state/get-players)
        self-id (state/get-self-id)]
    (if (empty? players)
      ;; Сообщение при отсутствии игроков
      (do
        (q/fill 255 255 255)
        (q/text-align :center :center)
        (q/text-size 24)
        (q/text "Waiting for players..." 
                (/ shared/arena-width 2) 
                (/ shared/arena-height 2))
        (q/text-size 14)) ; Возвращаем нормальный размер текста
      
      ;; Отрисовка всех игроков
      (do
        ;; Сначала обычные игроки
        (doseq [[pid player] players]
          (when (not= pid "boss")  ; Исключаем босса из обычных игроков
            (draw-player player (= pid self-id) false)))
        
        ;; Затем босс (рисуется поверх)
        (when-let [boss (get players "boss")]
          (draw-player boss (= "boss" self-id) true))))))

(defn draw-debug-info []
  ;; Отладочная информация (только в разработке)
  (when-let [boss (get (state/get-players) "boss")]
    (q/fill 255 255 255)
    (q/text-align :left :top)
    (q/text (str "BOSS AI: " 
                 (if (:dead boss) "DEAD" "ALIVE") 
                 " | HP: " (:hp boss) 
                 " | Pos: [" (int (:x boss)) "," (int (:y boss)) "]")
            10 140)))

(defn draw-game-over []
  ;; Экран завершения игры (если босс побежден)
  (when-let [boss (get (state/get-players) "boss")]
    (when (:dead boss)
      (q/fill 0 0 0 200)
      (q/rect 0 0 shared/arena-width shared/arena-height)
      
      (q/fill 255 255 0)
      (q/text-align :center :center)
      (q/text-size 36)
      (q/text "🎉 BOSS DEFEATED! 🎉" 
              (/ shared/arena-width 2) 
              (- (/ shared/arena-height 2) 50))
      
      (q/text-size 24)
      (q/fill 255 255 255)
      (q/text "Victory!" 
              (/ shared/arena-width 2) 
              (+ (/ shared/arena-height 2) 20))
      
      (q/text-size 14))))

;; Основная функция отрисовки (вызывается из client.clj)
(defn draw-game-objects-with-effects []
  (draw-game-objects)
  (draw-debug-info)
  (draw-game-over))
