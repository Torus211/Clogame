(ns arena.client.websocket
  (:require [cheshire.core :as json]
            [arena.client.state :as state])
  (:import (java.net URI)
           (java.net.http HttpClient WebSocket WebSocket$Listener)
           (java.nio CharBuffer)
           (java.util.concurrent CompletableFuture)
           (java.util Timer TimerTask)))

(defonce ws-atom (atom nil))
(defonce ping-timer (atom nil))
(defonce message-buffer (atom ""))
(defonce server-url (atom "ws://localhost:8080"))
(defonce reconnect-attempts (atom 0))
(defonce max-reconnect-attempts 5)
(defonce last-ping-time (atom 0))
(defonce network-latency (atom 0))

(defn set-server-url! [url]
  (reset! server-url (str "ws://" url ":8080")))

(defn safe-send [data]
  (when-let [ws @ws-atom]
    (future
      (try
        (.sendText ws (json/generate-string data) true)
        (catch Exception e
          (println "❌ WebSocket send error:" e))))))

(defn send-ping []
  (reset! last-ping-time (System/currentTimeMillis))
  (safe-send {:type "ping" :timestamp @last-ping-time}))

(defn send-move [x y]
  (safe-send {:type "move" :x x :y y}))

(defn send-shoot [dx dy]
  (safe-send {:type "shoot" :dx dx :dy dy}))

(defn calculate-latency [ping-time]
  (when (pos? ping-time)
    (let [latency (- (System/currentTimeMillis) ping-time)]
      (reset! network-latency latency)
      latency)))

(defn start-ping-timer []
  (let [timer (Timer. true)
        task (proxy [TimerTask] []
               (run [] (send-ping)))]
    (.scheduleAtFixedRate timer task 0 10000) ; Ping every 10 seconds
    (reset! ping-timer timer)))

(defn stop-ping-timer []
  (when-let [timer @ping-timer]
    (.cancel timer)
    (reset! ping-timer nil)))

(defn buffer-to-string [buffer]
  (if (instance? CharBuffer buffer)
    (.toString buffer)
    (str buffer)))

(defn try-parse-json [json-str]
  (try
    (json/parse-string json-str true)
    (catch Exception e
      (println "❌ JSON parse error:" e "for message:" (subs json-str 0 (min 100 (count json-str))))
      nil)))

(defn handle-boss-state [players]
  "Обработка специальной логики для босса"
  (when-let [boss (get players "boss")]
    (state/add-debug-message (str "Boss HP: " (:hp boss) "/1000"))
    
    ;; Уведомление о низком HP босса
    (when (< (:hp boss) 300)
      (state/add-debug-message "BOSS IS LOW ON HEALTH!"))))

(defn handle-complete-message [msg-str]
  (try
    (let [parsed-msg (try-parse-json msg-str)]
      (state/increment-packets-received)
      (if parsed-msg
        (let [msg-type (:type parsed-msg)]
          (case msg-type
            "init" (do
                     (println "✅ Connected to server, player ID:" (:self-id parsed-msg))
                     (state/set-self-id (:self-id parsed-msg))
                     (state/set-players (:players parsed-msg))
                     (reset! reconnect-attempts 0)
                     
                     ;; Проверяем наличие босса
                     (when (get (:players parsed-msg) "boss")
                       (println "🎯 Boss detected in the arena!"))
                     
                     (println "👥 Initial players:" (count (:players parsed-msg)))
                     
                     ;; Обновляем статистику
                     (state/update-derived-stats))
            
            "state" (do
                      (state/update-game-state
                       {:players (:players parsed-msg)
                        :bullets (:bullets parsed-msg)
                        :bonuses (:bonuses parsed-msg)})
                      
                      ;; Специальная обработка для босса
                      (handle-boss-state (:players parsed-msg)))
            
            "player-joined" (do
                              (println "👋 New player joined:" (:player-id parsed-msg))
                              (state/set-players
                               (assoc (state/get-players) 
                                      (keyword (:player-id parsed-msg)) 
                                      (:player parsed-msg)))
                              (state/update-derived-stats))
            
            "player-left" (do
                            (println "👋 Player left:" (:player-id parsed-msg))
                            (state/set-players
                             (dissoc (state/get-players) 
                                     (keyword (:player-id parsed-msg))))
                            (state/update-derived-stats))
            
            "pong" (do
                     (when-let [timestamp (:timestamp parsed-msg)]
                       (let [latency (calculate-latency timestamp)]
                         (state/update-network-latency latency)
                         (state/add-debug-message (str "Ping: " latency "ms")))))
            
            "error" (println "❌ Server error:" (:message parsed-msg))
            
            (println "⚠️  Unknown message type:" msg-type "content:" (dissoc parsed-msg :type))))
        (println "❌ Failed to parse JSON message")))
    (catch Exception e
      (println "❌ Error handling message:" e))))

(defn handle-server-msg [msg last?]
  (let [msg-str (buffer-to-string msg)]
    (swap! message-buffer str msg-str)
    (when last?
      (let [complete-msg @message-buffer]
        (reset! message-buffer "")
        (handle-complete-message complete-msg)))))

(defn should-reconnect? []
  "Определяет, стоит ли пытаться переподключиться"
  (and (< @reconnect-attempts max-reconnect-attempts)
       (not= (state/get-connection-status) :connected)))

(defn attempt-reconnect []
  "Попытка переподключения с экспоненциальной задержкой"
  (when (should-reconnect?)
    (swap! reconnect-attempts inc)
    (let [attempt @reconnect-attempts
          delay-ms (* 3000 (min attempt 5))] ; Экспоненциальная задержка до 15 секунд
      (println "🔄 Attempting to reconnect..." attempt "/" max-reconnect-attempts "(waiting" delay-ms "ms)")
      (Thread/sleep delay-ms)
      (connect))))

(defn connect []
  (future
    (try
      (let [client (HttpClient/newHttpClient)
            listener (reify WebSocket$Listener
                       (onOpen [_ ws]
                         (println "✅ WebSocket connection established")
                         (.request ws 1)
                         (reset! ws-atom ws)
                         (state/set-connection-status :connected)
                         (reset! message-buffer "")
                         (reset! reconnect-attempts 0)
                         (start-ping-timer)
                         
                         ;; Обновляем статистику
                         (state/update-derived-stats))
                       
                       (onText [_ ws msg last?]
                         (try
                           (handle-server-msg msg last?)
                           (catch Exception e
                             (println "❌ Error processing message:" e)
                             (state/add-debug-message (str "Message processing error: " (.getMessage e)))))
                         (.request ws 1)
                         (CompletableFuture/completedFuture nil))
                       
                       (onError [_ ws err]
                         (println "❌ WebSocket error:" (.getMessage err))
                         (state/set-connection-status :error)
                         (state/add-debug-message (str "WebSocket error: " (.getMessage err)))
                         (CompletableFuture/completedFuture nil))
                       
                       (onClose [_ ws status reason]
                         (println "🔌 WebSocket disconnected:" reason)
                         (reset! ws-atom nil)
                         (state/set-connection-status :disconnected)
                         (reset! message-buffer "")
                         (stop-ping-timer)
                         
                         ;; Автоматическая переподключение
                         (attempt-reconnect)
                         
                         (CompletableFuture/completedFuture nil)))]
        
        (println "🔄 Connecting to:" @server-url)
        (state/set-connection-status :connecting)
        (let [ws-future (.buildAsync (.newWebSocketBuilder client)
                                     (URI/create @server-url) 
                                     listener)]
          (.join ws-future)))
      
      (catch Exception e
        (println "❌ WebSocket connection failed:" (.getMessage e))
        (state/set-connection-status :error)
        (state/add-debug-message (str "Connection failed: " (.getMessage e)))
        
        ;; Повторная попытка подключения
        (attempt-reconnect)))))

(defn disconnect []
  "Явное отключение от сервера"
  (println "🔌 Disconnecting from server...")
  (stop-ping-timer)
  (reset! reconnect-attempts max-reconnect-attempts) ; Предотвращаем авто-переподключение
  (when-let [ws @ws-atom]
    (.sendClose ws 1000 "Client disconnected")
    (reset! ws-atom nil))
  (state/set-connection-status :disconnected))

(defn get-connection-status []
  "Возвращает статус соединения"
  {:connected? (= (state/get-connection-status) :connected)
   :reconnect-attempts @reconnect-attempts
   :max-reconnect-attempts max-reconnect-attempts
   :latency-ms @network-latency
   :server-url @server-url})

;; Функция для принудительной перезагрузки соединения
(defn reconnect []
  "Принудительное переподключение"
  (println "🔄 Manual reconnection initiated")
  (disconnect)
  (Thread/sleep 1000)
  (connect))
