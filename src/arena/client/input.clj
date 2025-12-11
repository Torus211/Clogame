(ns arena.client.input
  (:require [quil.core :as q]
            [arena.client.websocket :as ws]
            [arena.client.state :as state]
            [arena.shared :as shared]))

;; Константы для управления
(def mouse-shoot-cooldown 300) ; КД между выстрелами мышью (мс)
(def last-mouse-shot (atom 0)) ; Время последнего выстрела мышью

(defn get-player-size [player]
  "Возвращает размер игрока в зависимости от типа"
  (if (= (:id player) "boss")
    35  ; Размер босса
    shared/player-size)) ; Размер обычного игрока

(defn calculate-new-position [current-pos keys]
  "Рассчитывает новую позицию игрока с учетом нажатых клавиш и баффов"
  (let [{:keys [x y]} current-pos
        base-speed (if (contains? keys :shift) 
                    (* shared/player-speed 2) 
                    shared/player-speed)
        ;; Учет баффа скорости
        actual-speed (if (:speed-buff current-pos)
                      (* base-speed (:value (:speed-buff current-pos)))
                      base-speed)
        player-size (get-player-size current-pos)
        new-x (cond
                (contains? keys :a) (max 0 (- x actual-speed))
                (contains? keys :d) (min (- shared/arena-width player-size) (+ x actual-speed))
                :else x)
        new-y (cond
                (contains? keys :w) (max 0 (- y actual-speed))
                (contains? keys :s) (min (- shared/arena-height player-size) (+ y actual-speed))
                :else y)]
    {:x new-x :y new-y}))

(defn update-player-position []
  "Обновляет позицию игрока и отправляет на сервер"
  (let [self-id (state/get-self-id)
        current-players (state/get-players)
        current-self (get current-players self-id)]
    
    (when (and self-id current-self (not (:dead current-self)))
      (let [new-pos (calculate-new-position current-self (state/get-keys-pressed))]
        (when (not= new-pos (select-keys current-self [:x :y]))
          (state/set-players (assoc current-players self-id (merge current-self new-pos)))
          (ws/send-move (:x new-pos) (:y new-pos)))))))

(defn handle-shooting [key]
  "Обработка стрельбы по клавишам-стрелкам"
  (let [self-id (state/get-self-id)
        players (state/get-players)
        self (get players self-id)]
    (when (and self (not (:dead self)))
      (case key
        :up (ws/send-shoot 0 -1)
        :down (ws/send-shoot 0 1)
        :left (ws/send-shoot -1 0)
        :right (ws/send-shoot 1 0)
        nil))))

(defn handle-mouse-shooting [event]
  "Обработка стрельбы в направлении курсора мыши"
  (let [self-id (state/get-self-id)
        players (state/get-players)
        self (get players self-id)
        now (System/currentTimeMillis)]
    
    (when (and self (not (:dead self)) (>= (- now @last-mouse-shot) mouse-shoot-cooldown))
      (let [mx (:x event)
            my (:y event)
            player-center-x (+ (:x self) (/ (get-player-size self) 2))
            player-center-y (+ (:y self) (/ (get-player-size self) 2))
            dx (- mx player-center-x)
            dy (- my player-center-y)
            distance (Math/sqrt (+ (* dx dx) (* dy dy)))]
        
        ;; Стреляем только если курсор не слишком близко к игроку
        (when (> distance 10)
          (let [normalized-dx (/ dx distance)
                normalized-dy (/ dy distance)]
            (reset! last-mouse-shot now)
            (ws/send-shoot normalized-dx normalized-dy)))))))

(defn handle-key-pressed [event]
  "Обработка нажатия клавиш"
  (let [key-code (q/key-code)
        key-char (q/key-as-keyword)
        raw-key (q/raw-key)]
    
    (cond
      ;; Движение
      (#{:w :a :s :d} key-char) (state/add-key-pressed key-char)
      
      ;; Стрельба стрелками
      (#{:up :down :left :right} key-char) (handle-shooting key-char)
      
      ;; Sprint
      (= key-code 16) (state/add-key-pressed :shift) ; Shift key
      
      ;; Переподключение
      (and (= key-char :r) (contains? (state/get-keys-pressed) :ctrl)) 
      (do
        (println "🔄 Manual reconnection triggered")
        (ws/connect))
      
      ;; Стрельба пробелом (альтернатива)
      (= key-code 32) ; Space bar
      (let [self-id (state/get-self-id)
            players (state/get-players)
            self (get players self-id)]
        (when (and self (not (:dead self)))
          ;; Стреляем в направлении последнего движения или вперед
          (let [keys-pressed (state/get-keys-pressed)
                dx (cond
                     (contains? keys-pressed :a) -1
                     (contains? keys-pressed :d) 1
                     :else 0)
                dy (cond
                     (contains? keys-pressed :w) -1
                     (contains? keys-pressed :s) 1
                     :else (if (zero? dx) -1 0))]
            (ws/send-shoot dx dy))))
      
      ;; Отладочная информация (F3)
      (= key-code 114) ; F3
      (do
        (println "=== DEBUG INFO ===")
        (println "Players:" (state/get-players))
        (println "Self ID:" (state/get-self-id))
        (println "Connection:" (state/get-connection-status))
        (println "Keys pressed:" (state/get-keys-pressed)))
      
      :else nil)))

(defn handle-key-released [event]
  "Обработка отпускания клавиш"
  (let [key-code (q/key-code)
        key-char (q/key-as-keyword)]
    
    (cond
      (#{:w :a :s :d} key-char) (state/remove-key-pressed key-char)
      (= key-code 16) (state/remove-key-pressed :shift) ; Shift key
      :else nil)))

(defn handle-mouse-pressed [event]
  "Обработка нажатия кнопок мыши"
  (let [button (:button event)]
    (case button
      :left (handle-mouse-shooting event) ; Левая кнопка - стрельба
      :right (println "Right mouse button pressed") ; Правая кнопка - зарезервировано
      :center (println "Middle mouse button pressed") ; Средняя кнопка
      (println "Mouse button pressed:" button))
    
    (println "Mouse clicked at:" (:x event) "," (:y event))))

(defn handle-mouse-dragged [event]
  "Обработка перемещения мыши с зажатой кнопкой (непрерывная стрельба)"
  (let [button (:button event)]
    (when (= button :left)
      (handle-mouse-shooting event))))

(defn handle-focus-gained []
  "Обработка получения фокуса окном"
  (println "✅ Window gained focus")
  (state/set-window-focused true)
  
  ;; Восстанавливаем состояние клавиш при возврате фокуса
  (let [current-keys (state/get-keys-pressed)]
    (when (seq current-keys)
      (println "Restoring keys state:" current-keys))))

(defn handle-focus-lost []
  "Обработка потери фокуса окном"
  (println "⚠️ Window lost focus - clearing keys")
  (state/set-window-focused false)
  (state/clear-keys-pressed)
  
  ;; Останавливаем движение при потере фокуса
  (let [self-id (state/get-self-id)
        current-players (state/get-players)
        current-self (get current-players self-id)]
    (when (and self-id current-self)
      (ws/send-move (:x current-self) (:y current-self)))))

(defn handle-mouse-wheel [event]
  "Обработка прокрутки колесика мыши (для будущих функций)"
  (let [amount (:amount event)]
    (println "Mouse wheel scrolled:" amount)
    ;; Можно добавить смену оружия или другие функции
    ))

;; Дополнительные функции для улучшения управления

(defn get-mouse-direction [player-x player-y mouse-x mouse-y]
  "Возвращает нормализованный вектор направления от игрока к курсору"
  (let [dx (- mouse-x player-x)
        dy (- mouse-y player-y)
        distance (Math/sqrt (+ (* dx dx) (* dy dy)))]
    (if (> distance 0)
      [(/ dx distance) (/ dy distance)]
      [0 -1]))) ; По умолчанию стреляем вверх

(defn auto-shoot-enabled? []
  "Проверяет, включена ли автоматическая стрельба (для будущей реализации)"
  false) ; Пока отключено

(defn handle-continuous-shooting []
  "Обработка непрерывной стрельбы (если включена)"
  (when (auto-shoot-enabled?)
    (let [self-id (state/get-self-id)
          players (state/get-players)
          self (get players self-id)
          now (System/currentTimeMillis)]
      (when (and self (not (:dead self)) (>= (- now @last-mouse-shot) mouse-shoot-cooldown))
        (let [mx (q/mouse-x)
              my (q/mouse-y)
              [dx dy] (get-mouse-direction 
                        (+ (:x self) (/ (get-player-size self) 2))
                        (+ (:y self) (/ (get-player-size self) 2))
                        mx my)]
          (when (and (not (zero? dx)) (not (zero? dy)))
            (reset! last-mouse-shot now)
            (ws/send-shoot dx dy)))))))

;; Функция для обновления ввода (вызывается каждый кадр)
(defn update-input []
  (update-player-position)
  (handle-continuous-shooting))
