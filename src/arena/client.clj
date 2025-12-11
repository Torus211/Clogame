(ns arena.client
  (:require [quil.core :as q]
            [quil.middleware :as m]
            [arena.client.websocket :as ws]
            [arena.client.input :as input]
            [arena.client.graphics :as graphics]
            [arena.client.ui :as ui]
            [arena.client.state :as state]
            [arena.shared :as shared])
  (:import [java.lang RuntimeException]))

(defn setup []
  (try
    (q/frame-rate 60)
    (q/color-mode :rgb)
    (q/rect-mode :corner)
    (q/ellipse-mode :center)
    (q/text-font (q/create-font "Arial" 14 true))
    (q/smooth)
    
    ;; Инициализация состояния
    (state/initialize-state)
    
    ;; Подключение к серверу
    (ws/connect)
    
    (println "
   ___                  _    
  / _ \\__ _ _ __   __ _| | __
 / /_)/ _` | '_ \\ / _` | |/ /
/ ___/ (_| | | | | (_| |   < 
\\/    \\__,_|_| |_|\\__,_|_|\\_\\
                             
  ")
    (println "🎮 ARENA CLIENT - BOSS BATTLE READY")
    (println "=====================================")
    (println "✅ Game initialized - waiting for connection...")
    (println "🎯 Controls: WASD + Mouse | F3: Debug | Ctrl+R: Reconnect")
    
    {:last-update (System/currentTimeMillis)
     :start-time (System/currentTimeMillis)
     :frame-count 0}
    
    (catch Exception e
      (println "❌ Error during setup:" (.getMessage e))
      (throw e))))

(defn update-game-state [state]
  "Обновление состояния игры каждый кадр"
  (try
    (let [current-time (System/currentTimeMillis)
          delta-time (- current-time (:last-update state))]
      
      ;; Обновляем ввод (движение и стрельба)
      (input/update-input)
      
      ;; Обновляем время последнего обновления
      (assoc state
             :last-update current-time
             :frame-count (inc (:frame-count state))
             :delta-time delta-time))
    
    (catch Exception e
      (println "⚠️ Error in update-game-state:" (.getMessage e))
      state)))

(defn draw-state [state]
  (try
    (let [current-time (System/currentTimeMillis)]
      
      ;; Обновляем FPS и статистику
      (state/update-fps current-time)
      (state/update-last-update-time)
      
      ;; Очистка экрана с градиентным фоном
      (q/background 30 30 60)
      
      ;; Рисуем тонкий градиент для фона
      (doseq [i (range 0 shared/arena-height 2)]
        (let [alpha (int (* 50 (/ i shared/arena-height)))]
          (q/stroke 60 60 100 alpha)
          (q/line 0 i shared/arena-width i)))
      
      ;; Обновление игрового состояния
      (update-game-state state)
      
      ;; Отрисовка игровых объектов с эффектами
      (graphics/draw-game-objects-with-effects)
      
      ;; Отрисовка UI поверх игры
      (ui/draw-ui current-time)
      
      ;; Отладочная информация в заголовке окна
      (when (:show-debug? (state/get-debug-info))
        (let [stats (state/get-game-stats)
              game-info (state/get-comprehensive-game-info)]
          (q/set-title (str "🎮 Arena Game - "
                           "FPS: " (:fps stats) " | "
                           "Players: " (:players-alive game-info) "/" (:players-total game-info) " | "
                           "Boss HP: " (:boss-hp stats) "/1000"))))
      
      state)
    
    (catch Exception e
      (println "⚠️ Error in draw-state:" (.getMessage e))
      state)))

;; ============================================================================
;; ОБРАБОТЧИКИ СОБЫТИЙ QUIL
;; ============================================================================

(defn key-pressed [state event]
  (try
    (input/handle-key-pressed event)
    state
    (catch Exception e
      (println "⚠️ Error in key-pressed:" (.getMessage e))
      state)))

(defn key-released [state event]
  (try
    (input/handle-key-released event)
    state
    (catch Exception e
      (println "⚠️ Error in key-released:" (.getMessage e))
      state)))

(defn mouse-pressed [state event]
  (try
    (input/handle-mouse-pressed event)
    state
    (catch Exception e
      (println "⚠️ Error in mouse-pressed:" (.getMessage e))
      state)))

(defn mouse-released [state event]
  ;; Можно добавить обработку отпускания кнопок мыши
  state)

(defn mouse-dragged [state event]
  (try
    (input/handle-mouse-dragged event)
    state
    (catch Exception e
      (println "⚠️ Error in mouse-dragged:" (.getMessage e))
      state)))

(defn mouse-wheel [state event]
  (try
    (input/handle-mouse-wheel event)
    state
    (catch Exception e
      (println "⚠️ Error in mouse-wheel:" (.getMessage e))
      state)))

(defn focus-gained [state]
  (try
    (input/handle-focus-gained)
    (println "✅ Window focused - controls active")
    state
    (catch Exception e
      (println "⚠️ Error in focus-gained:" (.getMessage e))
      state)))

(defn focus-lost [state]
  (try
    (input/handle-focus-lost)
    (println "⚠️ Window focus lost - controls disabled")
    state
    (catch Exception e
      (println "⚠️ Error in focus-lost:" (.getMessage e))
      state)))

;; ============================================================================
;; ОСНОВНОЙ SKETCH QUIL
;; ============================================================================

(defn start-sketch []
  (try
    (println "🎨 Starting Quil sketch...")
    (q/defsketch arena-client
      :title "🎮 Arena Game - Multiplayer Boss Battle - CLICK TO FOCUS!"
      :size [shared/arena-width shared/arena-height]
      :setup setup
      :draw draw-state
      :key-pressed key-pressed
      :key-released key-released
      :mouse-pressed mouse-pressed
      :mouse-released mouse-released
      :mouse-dragged mouse-dragged
      :mouse-wheel mouse-wheel
      :focus-gained focus-gained
      :focus-lost focus-lost
      :features [:keep-on-top :exit-on-close :resizable :no-safe-frames]
      :middleware [m/fun-mode])
    (println "✅ Quil sketch started successfully")
    (catch Exception e
      (println "❌ Failed to start Quil sketch:" (.getMessage e))
      (println "💡 Make sure JavaFX is properly installed and configured")
      (throw e))))

;; ============================================================================
;; ФУНКЦИИ УПРАВЛЕНИЯ КЛИЕНТОМ
;; ============================================================================

(defn start-client [server-ip]
  (println "🎯 Starting client with server IP:" server-ip)
  (ws/set-server-url! server-ip)
  
  ;; Проверяем валидность IP
  (if (or (= server-ip "localhost")
          (re-matches #"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$" server-ip))
    (do
      (println "🔗 Attempting connection to:" server-ip)
      ;; Запускаем Quil sketch
      (start-sketch))
    (do
      (println "❌ Invalid server IP address:" server-ip)
      (println "💡 Use 'localhost' or a valid IP address like '192.168.1.100'")
      (System/exit 1))))

(defn stop-client []
  "Остановка клиента и очистка ресурсов"
  (println "🛑 Stopping Arena client...")
  (ws/disconnect)
  (println "✅ Client stopped"))

(defn restart-client []
  "Перезапуск клиента"
  (println "🔄 Restarting client...")
  (stop-client)
  (Thread/sleep 1000)
  (ws/reconnect))

;; ============================================================================
;; УТИЛИТЫ ДЛЯ РАЗРАБОТКИ
;; ============================================================================

(defn get-client-status []
  "Возвращает статус клиента для отладки"
  (let [connection-status (ws/get-connection-status)
        game-info (state/get-comprehensive-game-info)
        state @state/game-state]
    {:connection connection-status
     :game-state {:players (count (:players state))
                  :bullets (count (:bullets state))
                  :bonuses (count (:bonuses state))
                  :self-id (:self-id state)}
     :performance {:fps (:fps (state/get-game-stats))
                   :uptime (- (System/currentTimeMillis) (:start-time state))}
     :boss (:boss game-info)}))

(defn -main []
  "Точка входа для standalone-запуска клиента"
  (println "🚀 Starting Arena Client in standalone mode...")
  (println "📍 Connect to server: localhost:8080")
  (start-client "localhost"))

;; Экспортируем функции для внешнего использования
(defn enable-debug-mode []
  "Включение режима отладки"
  (state/set-debug-info true)
  (println "🔧 Debug mode enabled"))

(defn disable-debug-mode []
  "Выключение режима отладки"
  (state/set-debug-info false)
  (println "🔧 Debug mode disabled"))

;; Автоматическая очистка при выходе
(.addShutdownHook (Runtime/getRuntime)
                  (Thread. ^Runnable (fn [] (stop-client)))))
