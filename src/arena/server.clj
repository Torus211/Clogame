(ns arena.server
  (:require [org.httpkit.server :as ws]
            [cheshire.core :as json]
            [arena.shared :refer :all]
            [arena.bot :as bot]
            [arena.network-utils :as net])
  (:import [java.util UUID]))

;; Глобальное состояние
(defonce clients (atom {}))
(defonce game-state (atom initial-state))
(defonce game-running? (atom true))
(defonce game-thread (atom nil))
(defonce last-bonus-spawn (atom (System/currentTimeMillis)))
(defonce server-instance (atom nil))
(defonce boss-thread (atom nil))
(defonce server-stats (atom {:start-time (System/currentTimeMillis)
                            :players-connected 0
                            :players-disconnected 0
                            :bullets-fired 0
                            :bonuses-spawned 0}))

;; Утилиты для работы с WebSocket
(defn send-json! [ch data]
  (try
    (when (ws/open? ch)
      (ws/send! ch (json/generate-string data)))
    (catch Exception e
      (println "❌ Error sending to client:" e))))

(defn broadcast [data]
  (let [msg (json/generate-string data)]
    (doseq [[_ client] @clients]
      (try
        (when (ws/open? (:channel client))
          (ws/send! (:channel client) msg))
        (catch Exception e
          (println "❌ Error broadcasting to client:" e))))))

(defn broadcast-except [except-pid data]
  (let [msg (json/generate-string data)]
    (doseq [[pid client] @clients]
      (when (and (not= pid except-pid) (ws/open? (:channel client)))
        (try
          (ws/send! (:channel client) msg)
          (catch Exception e
            (println "❌ Error broadcasting to client" pid ":" e)))))))

;; Обработка сообщений от клиентов
(defn handle-move [pid data]
  (when (valid-coordinates? (:x data) (:y data))
    (swap! game-state update-in [:players pid]
           #(when % (assoc % :x (:x data) :y (:y data))))))

(defn handle-shoot [pid data]
  (let [now (System/currentTimeMillis)
        player (get-in @game-state [:players pid])]
    (when (and player (not (:dead player)) 
               (>= (- now (get player :last-shot 0)) 300))
      (let [bullet-id (str (:next-bullet-id @game-state))
            bullet-x (+ (:x player) (/ player-size 2))
            bullet-y (+ (:y player) (/ player-size 2))
            bullet {:id bullet-id
                    :x bullet-x :y bullet-y
                    :dx (:dx data) :dy (:dy data)
                    :owner pid
                    :created-at now}]
        (swap! game-state 
               (fn [state]
                 (-> state
                     (assoc-in [:bullets bullet-id] bullet)
                     (update :next-bullet-id inc)
                     (assoc-in [:players pid :last-shot] now))))
        (swap! server-stats update :bullets-fired inc)
        (println "🎯 Player" pid "shot bullet" bullet-id "at" bullet-x bullet-y)))))

(defn handle-ping [pid data]
  (swap! clients update-in [pid :last-ping] (constantly (System/currentTimeMillis)))
  ;; Отправляем pong обратно
  (when-let [client (get @clients pid)]
    (send-json! (:channel client) {:type "pong" :timestamp (:timestamp data)})))

(defn handle-message [pid msg]
  (try
    (let [data (json/parse-string msg true)
          msg-type (:type data)]
      (case msg-type
        "move" (handle-move pid data)
        "shoot" (handle-shoot pid data)
        "ping" (handle-ping pid data)
        (println "⚠️ Unknown message type from" pid ":" msg-type)))
    (catch Exception e
      (println "❌ Error handling message from" pid ":" e))))

;; Игровая логика с поддержкой босса
(defn update-bullets []
  (swap! game-state
         (fn [state]
           (let [now (System/currentTimeMillis)
                 bullets (:bullets state)
                 updated-bullets (->> bullets
                                   (map (fn [[id bullet]]
                                          [id (-> bullet
                                                  (update :x + (* bullet-speed (:dx bullet)))
                                                  (update :y + (* bullet-speed (:dy bullet))))]))
                                   (filter (fn [[id bullet]]
                                             (and (>= (:x bullet) 0) (<= (:x bullet) arena-width)
                                                  (>= (:y bullet) 0) (<= (:y bullet) arena-height)
                                                  (< (- now (:created-at bullet)) bullet-lifetime))))
                                   (into {}))]
             (assoc state :bullets updated-bullets)))))

(defn calculate-damage [attacker-id target-id]
  "Рассчитывает урон с учетом баффов и типа атакующего"
  (let [attacker (get-in @game-state [:players attacker-id])
        base-damage (if (= attacker-id "boss") boss-damage bullet-damage)
        damage-buff (when (:damage-buff attacker) (:value (:damage-buff attacker)))]
    (if damage-buff
      (+ base-damage damage-buff)
      base-damage)))

(defn check-bullet-collisions []
  (let [state @game-state
        bullets (:bullets state)
        players (:players state)]
    (doseq [[bullet-id bullet] bullets
            :let [owner (:owner bullet)
                  bullet-x (:x bullet)
                  bullet-y (:y bullet)]]
      (doseq [[player-id player] players
              :when (and (not= player-id owner)
                         (not (:dead player))
                         (collides? bullet-x bullet-y bullet-size
                                   (:x player) (:y player) 
                                   (if (= player-id "boss") 35 player-size)))]
        (let [damage (calculate-damage owner player-id)
              current-hp (get player :hp (if (= player-id "boss") 1000 max-hp))
              new-hp (- current-hp damage)
              dead? (<= new-hp 0)]
          (println "💥 Bullet" bullet-id "from" owner "hit" 
                  (if (= player-id "boss") "BOSS" (str "player " player-id))
                  "HP:" current-hp "->" new-hp)
          (swap! game-state
                 (fn [s]
                   (-> s
                       (update-in [:players player-id :hp] (constantly (max 0 new-hp)))
                       (update-in [:players owner :score] (fnil inc 0))
                       (assoc-in [:players player-id :dead] dead?)
                       (assoc-in [:players player-id :dead-since] 
                                 (when dead? (System/currentTimeMillis)))
                       (update :bullets dissoc bullet-id))))
          
          ;; Специальное сообщение при убийстве босса
          (when (and dead? (= player-id "boss"))
            (println "🎉 BOSS DEFEATED! Player" owner "has slain the boss!")))))))

(defn spawn-bonus []
  (let [bonus-id (str (:next-bonus-id @game-state))
        bonus-types [{:type "health" :color [0 255 0] :value 50}
                     {:type "speed" :color [0 0 255] :value 2} 
                     {:type "damage" :color [255 0 0] :value 10}]
        bonus-type (rand-nth bonus-types)
        bonus (merge {:id bonus-id
                      :x (rand-int (- arena-width bonus-size))
                      :y (rand-int (- arena-height bonus-size))
                      :created-at (System/currentTimeMillis)}
                     bonus-type)]
    (swap! game-state 
           (fn [state]
             (-> state
                 (assoc-in [:bonuses bonus-id] bonus)
                 (update :next-bonus-id inc))))
    (swap! server-stats update :bonuses-spawned inc)
    (println "🎁 Spawned bonus:" (:type bonus-type) "at" (:x bonus) (:y bonus))))

(defn update-bonuses []
  (let [now (System/currentTimeMillis)]
    (swap! game-state
           (fn [state]
             (let [bonuses (:bonuses state)
                   updated-bonuses (->> bonuses
                                     (filter (fn [[id bonus]]
                                               (< (- now (:created-at bonus)) bonus-lifetime)))
                                     (into {}))]
               (assoc state :bonuses updated-bonuses))))))

(defn check-bonus-collisions []
  (let [state @game-state
        bonuses (:bonuses state)
        players (:players state)]
    (doseq [[bonus-id bonus] bonuses
            :let [bonus-x (:x bonus)
                  bonus-y (:y bonus)]]
      (doseq [[player-id player] players
              :when (and (not (:dead player))
                         (collides? bonus-x bonus-y bonus-size
                                   (:x player) (:y player) 
                                   (if (= player-id "boss") 35 player-size)))]
        (let [bonus-type (:type bonus)]
          (println "⭐ Player" player-id "collected bonus:" bonus-type)
          (swap! game-state
                 (fn [s]
                   (-> s
                       (update :bonuses dissoc bonus-id)
                       (update-in [:players player-id :score] (fnil + 0) 10)
                       (cond->
                         (= bonus-type "health")
                         (update-in [:players player-id :hp] 
                                   (fn [hp] 
                                     (let [max-hp (if (= player-id "boss") 1000 max-hp)]
                                       (min max-hp (+ (or hp max-hp) (:value bonus))))))
                         
                         (= bonus-type "speed")
                         (assoc-in [:players player-id :speed-buff] 
                                  {:value (:value bonus) :expires (+ (System/currentTimeMillis) 10000)})
                         
                         (= bonus-type "damage")
                         (assoc-in [:players player-id :damage-buff] 
                                  {:value (:value bonus) :expires (+ (System/currentTimeMillis) 10000)}))))))))))

(defn update-player-buffs []
  (let [now (System/currentTimeMillis)]
    (swap! game-state
           (fn [state]
             (reduce (fn [s [player-id player]]
                       (cond-> s
                         (and (:speed-buff player) (> now (:expires (:speed-buff player))))
                         (update-in [:players player-id] dissoc :speed-buff)
                         
                         (and (:damage-buff player) (> now (:expires (:damage-buff player))))
                         (update-in [:players player-id] dissoc :damage-buff)))
                     state
                     (:players state))))))

(defn respawn-dead-players []
  (let [now (System/currentTimeMillis)]
    (swap! game-state
           (fn [state]
             (reduce (fn [s [player-id player]]
                       (if (and (:dead player) 
                                (not= player-id "boss") ; Босс не возрождается автоматически
                                (> (- now (:dead-since player)) respawn-time))
                         (do
                           (println "🔄 Respawning player" player-id)
                           (-> s
                               (assoc-in [:players player-id] 
                                        (merge (random-spawn-position)
                                              {:hp max-hp :score (get player :score 0)}))))
                         s))
                     state
                     (:players state))))))

(defn respawn-boss-if-needed []
  "Возрождает босса если он мертв и прошло достаточно времени"
  (let [now (System/currentTimeMillis)
        boss (get-in @game-state [:players "boss"])]
    (when (and boss (:dead boss) (> (- now (:dead-since boss)) 30000)) ; 30 секунд
      (println "🔥 BOSS RESPAWNING!")
      (swap! game-state assoc-in [:players "boss"] (bot/create-boss)))))

(defn cleanup-disconnected-clients []
  (let [now (System/currentTimeMillis)
        timeout 30000]
    (doseq [[pid client] @clients
            :when (> (- now (:last-ping client)) timeout)]
      (println "👋 Removing disconnected client:" pid)
      (swap! clients dissoc pid)
      (swap! game-state update :players dissoc pid)
      (swap! server-stats update :players-disconnected inc))))

(defn spawn-bonus-if-needed []
  (let [now (System/currentTimeMillis)
        state @game-state
        bonuses-count (count (:bonuses state))]
    (when (and (< bonuses-count 3) 
               (> (- now @last-bonus-spawn) bonus-spawn-time))
      (reset! last-bonus-spawn now)
      (spawn-bonus))))

(defn update-boss-ai []
  "Обновляет AI босса через Prolog логику"
  (try
    (swap! game-state bot/update-boss)
    (catch Exception e
      (println "❌ Boss AI error:" (.getMessage e)))))

(defn game-tick []
  (try
    (update-bullets)
    (update-bonuses)
    (update-player-buffs)
    (check-bullet-collisions)
    (check-bonus-collisions)
    (respawn-dead-players)
    (respawn-boss-if-needed)
    (cleanup-disconnected-clients)
    (spawn-bonus-if-needed)
    (update-boss-ai)
    
    (let [current-state @game-state
          game-data {:type "state"
                     :players (:players current-state)
                     :bullets (vals (:bullets current-state))
                     :bonuses (vals (:bonuses current-state))}]
      (broadcast game-data))
    (catch Exception e
      (println "❌ Error in game tick:" e))))

;; Игровой цикл
(defn start-game-loop []
  (reset! game-thread
    (future
      (while @game-running?
        (Thread/sleep game-tick-ms)
        (game-tick)))))

(defn stop-game-loop []
  (reset! game-running? false)
  (when-let [thread @game-thread]
    (future-cancel thread)))

;; WebSocket обработчик
(defn ws-handler [req]
  (ws/with-channel req ch
    (let [pid (str (UUID/randomUUID))
          initial-player (merge (random-spawn-position)
                               {:hp max-hp :score 0})]
      
      (println "🎮 New player connected:" pid)
      (swap! clients assoc pid {:channel ch :last-ping (System/currentTimeMillis)})
      (swap! game-state assoc-in [:players pid] initial-player)
      (swap! server-stats update :players-connected inc)
      
      (send-json! ch {:type "init" 
                      :self-id pid 
                      :players (:players @game-state)})
      
      (broadcast-except pid {:type "player-joined" 
                             :player-id pid 
                             :player initial-player})
      
      ;; Отправляем информацию о боссе новому игроку
      (when-let [boss (get-in @game-state [:players "boss"])]
        (send-json! ch {:type "boss-info" 
                        :boss boss}))
      
      (ws/on-receive ch (partial handle-message pid))
      
      (ws/on-close ch
        (fn [status]
          (println "👋 Client disconnected:" pid)
          (swap! clients dissoc pid)
          (swap! game-state update :players dissoc pid)
          (broadcast {:type "player-left" :player-id pid}))))))

;; Статистика сервера
(defn get-server-stats []
  (let [stats @server-stats
        uptime (- (System/currentTimeMillis) (:start-time stats))
        current-state @game-state]
    (merge stats
           {:uptime-ms uptime
            :uptime-str (str (int (/ uptime 1000)) "s")
            :current-players (count @clients)
            :total-players (count (:players current-state))
            :active-bullets (count (:bullets current-state))
            :active-bonuses (count (:bonuses current-state))
            :boss-alive? (let [boss (get-in current-state [:players "boss"])]
                           (and boss (not (:dead boss))))})))

(defn print-server-stats []
  (let [stats (get-server-stats)]
    (println "\n📊 SERVER STATISTICS:")
    (println "====================")
    (println "Uptime:" (:uptime-str stats))
    (println "Players connected:" (:current-players stats) "/" (:players-connected stats) "total")
    (println "Players disconnected:" (:players-disconnected stats))
    (println "Bullets fired:" (:bullets-fired stats))
    (println "Bonuses spawned:" (:bonuses-spawned stats))
    (println "Boss alive:" (:boss-alive? stats))
    (println "Active bullets:" (:active-bullets stats))
    (println "Active bonuses:" (:active-bonuses stats))))

;; Управление сервером
(defn start-server []
  (println "🚀 Starting Arena server on port 8080...")
  (println "🤖 Initializing Prolog AI boss...")
  
  ;; Инициализируем Prolog
  (bot/init-prolog)
  
  (reset! game-running? true)
  (reset! game-state (-> initial-state
                         (assoc-in [:players "boss"] (bot/create-boss)))) ; Создаем босса
  (reset! clients {})
  (reset! server-stats {:start-time (System/currentTimeMillis)
                       :players-connected 0
                       :players-disconnected 0
                       :bullets-fired 0
                       :bonuses-spawned 0})
  
  (start-game-loop)
  (reset! server-instance (ws/run-server ws-handler {:port 8080}))
  
  (println "✅ Server started successfully!")
  (println "🔥 Boss spawned with 1000 HP and Prolog AI")
  (println "📍 Players can connect to: localhost:8080")
  (println "🌐 Or use your local IP:" (net/get-local-ip))
  (println "⏹️  Press Ctrl+C to stop the server")
  
  ;; Запускаем периодический вывод статистики
  (future
    (while @game-running?
      (Thread/sleep 30000) ; Каждые 30 секунд
      (print-server-stats))))

(defn stop-server []
  (println "🛑 Stopping Arena server...")
  (stop-game-loop)
  (when-let [server @server-instance]
    (server :timeout 100)
    (reset! server-instance nil))
  (print-server-stats)
  (println "✅ Server stopped"))

;; API для управления сервером
(defn restart-server []
  (println "🔄 Restarting server...")
  (stop-server)
  (Thread/sleep 2000)
  (start-server))

(defn get-server-status []
  {:running? @game-running?
   :stats (get-server-stats)
   :clients (count @clients)
   :game-state @game-state})
