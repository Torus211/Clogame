(ns arena.network-utils
  (:require [clojure.string :as str])
  (:import [java.net NetworkInterface InetAddress Inet4Address InetSocketAddress Socket]
           [java.util Enumeration]
           [java.util.concurrent TimeUnit]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]))

(defn get-local-ip []
  "Получает локальный IPv4 адрес, исключая loopback интерфейсы"
  (try
    (let [interfaces (enumeration-seq (NetworkInterface/getNetworkInterfaces))
          addresses (->> interfaces
                      (filter (fn [^NetworkInterface interface]
                                (and (.isUp interface)
                                     (not (.isLoopback interface)))))
                      (mapcat (fn [^NetworkInterface interface]
                                (enumeration-seq (.getInetAddresses interface))))
                      (filter (fn [^InetAddress addr]
                                (and (instance? Inet4Address addr)
                                     (.isSiteLocalAddress addr))))
                      (map (fn [^InetAddress addr] (.getHostAddress addr)))
                      (distinct))]
      (if (seq addresses)
        (first addresses)
        (do
          (println "⚠️  No suitable network interfaces found, using localhost")
          "localhost")))
    (catch Exception e
      (println "❌ Error determining local IP:" (.getMessage e))
      "localhost")))

(defn get-all-local-ips []
  "Возвращает все локальные IPv4 адреса для диагностики"
  (try
    (let [interfaces (enumeration-seq (NetworkInterface/getNetworkInterfaces))]
      (->> interfaces
           (map (fn [^NetworkInterface interface]
                  {:name (.getName interface)
                   :display-name (.getDisplayName interface)
                   :up? (.isUp interface)
                   :loopback? (.isLoopback interface)
                   :addresses (->> (enumeration-seq (.getInetAddresses interface))
                                (filter (fn [^InetAddress addr]
                                          (instance? Inet4Address addr)))
                                (map (fn [^InetAddress addr]
                                       {:host (.getHostAddress addr)
                                        :hostname (.getHostName addr)
                                        :site-local? (.isSiteLocalAddress addr)
                                        :loopback? (.isLoopbackAddress addr)})))}))
           (filter (fn [interface] (seq (:addresses interface))))))
    (catch Exception e
      (println "❌ Error getting network interfaces:" (.getMessage e))
      [])))

(defn valid-ip-address? [ip]
  "Проверяет валидность IP адреса или hostname"
  (try
    (cond
      (= ip "localhost") true
      (str/blank? ip) false
      :else (let [parts (str/split ip #"\.")]
              (and (= (count parts) 4)
                   (every? (fn [part]
                             (try
                               (let [num (Integer/parseInt part)]
                                 (and (>= num 0) (<= num 255)))
                               (catch Exception _ false)))
                           parts))))
    (catch Exception e
      (println "❌ IP validation error for" ip ":" (.getMessage e))
      false)))

(defn valid-hostname? [hostname]
  "Проверяет валидность hostname"
  (try
    (and (not (str/blank? hostname))
         (re-matches #"^[a-zA-Z0-9.-]+$" hostname)
         (not (str/starts-with? hostname "-"))
         (not (str/ends-with? hostname "-"))
         (< (count hostname) 254))
    (catch Exception e
      (println "❌ Hostname validation error:" (.getMessage e))
      false)))

(defn check-server-availability [host port & [timeout-ms]]
  "Проверяет доступность сервера по TCP соединению"
  (let [timeout (or timeout-ms 5000)]
    (try
      (with-open [socket (Socket.)]
        (.connect socket (InetSocketAddress. host port) timeout)
        true)
      (catch Exception e
        (println "❌ Server" host ":" port "is not available:" (.getMessage e))
        false))))

(defn check-websocket-endpoint [host port & [timeout-ms]]
  "Проверяет доступность WebSocket endpoint через HTTP"
  (let [timeout (or timeout-ms 5000)
        http-client (-> (HttpClient/newBuilder)
                        (.connectTimeout (Duration/ofMillis timeout))
                        (.build))
        request (-> (HttpRequest/newBuilder)
                    (.uri (java.net.URI/create (str "http://" host ":" port "/")))
                    (.timeout (Duration/ofMillis timeout))
                    (.build))]
    (try
      (let [response (.send http-client request (HttpResponse$BodyHandlers/ofString))]
        (<= 200 (.statusCode response) 399))
      (catch Exception e
        (println "❌ WebSocket endpoint check failed for" host ":" port ":" (.getMessage e))
        false))))

(defn discover-local-servers []
  "Обнаруживает локальные серверы в сети (multicast discovery)"
  (println "🔍 Searching for local game servers...")
  
  (try
    ;; Сканируем локальную сеть на наличие серверов
    (let [local-ip (get-local-ip)
          network-prefix (->> (str/split local-ip #"\.")
                           (take 3)
                           (str/join "."))
          port 8080
          potential-servers (concat
                             ["localhost" "127.0.0.1" local-ip]
                             (map #(str network-prefix "." %) (range 1 255)))]
      
      (println "   Scanning network" network-prefix ".x ...")
      
      ;; Проверяем только первые 10 адресов для скорости
      (let [checked-servers (->> potential-servers
                              (take 20)
                              (pmap (fn [host]
                                      (future
                                        (when (check-server-availability host port 1000)
                                          host))))
                              (map deref)
                              (filter some?))]
        
        (if (seq checked-servers)
          (do
            (println "   ✅ Found" (count checked-servers) "server(s):")
            (doseq [server checked-servers]
              (println "      -" server))
            checked-servers)
          (do
            (println "   ❌ No servers found automatically")
            []))))
    
    (catch Exception e
      (println "   ❌ Server discovery failed:" (.getMessage e))
      [])))

(defn format-server-url [ip]
  "Форматирует URL WebSocket сервера"
  (str "ws://" ip ":8080"))

(defn parse-server-url [url]
  "Парсит URL сервера и возвращает хост и порт"
  (try
    (if (str/starts-with? url "ws://")
      (let [without-protocol (subs url 5)
            [host port] (str/split without-protocol #":")]
        {:host host :port (Integer/parseInt port)})
      {:host url :port 8080})
    (catch Exception e
      (println "❌ Failed to parse server URL:" url)
      {:host "localhost" :port 8080})))

(defn get-network-info []
  "Возвращает полную информацию о сети для диагностики"
  (let [local-ip (get-local-ip)
        all-ips (get-all-local-ips)]
    {:local-ip local-ip
     :network-interfaces all-ips
     :server-available? (check-server-availability "localhost" 8080 1000)
     :websocket-endpoint-available? (check-websocket-endpoint "localhost" 8080 1000)}))

(defn diagnose-connection [host port]
  "Диагностика подключения к серверу"
  (println "🔧 Diagnosing connection to" host ":" port)
  
  (let [steps [{:name "Hostname resolution" 
                :test #(try (InetAddress/getByName host) true 
                           (catch Exception _ false))}
               {:name "TCP connectivity" 
                :test #(check-server-availability host port 3000)}
               {:name "WebSocket endpoint" 
                :test #(check-websocket-endpoint host port 3000)}]
        
        results (map (fn [step]
                       (let [result ((:test step))]
                         (println (if result "   ✅" "   ❌") (:name step))
                         (assoc step :success? result)))
                     steps)]
    
    {:success? (every? :success? results)
     :details results
     :suggestions (when-not (every? :success? results)
                    ["Check if server is running"
                     "Verify firewall settings"
                     "Ensure port 8080 is open"
                     "Try using IP address instead of hostname"])}))

(defn wait-for-server [host port & {:keys [timeout-ms interval-ms]
                                  :or {timeout-ms 30000 interval-ms 1000}}]
  "Ожидает доступности сервера с таймаутом"
  (println "⏳ Waiting for server" host ":" port "...")
  
  (let [start-time (System/currentTimeMillis)
        max-time (+ start-time timeout-ms)]
    
    (loop [attempt 1]
      (let [current-time (System/currentTimeMillis)
            elapsed (- current-time start-time)]
        
        (cond
          (>= current-time max-time)
          (do
            (println "❌ Server not available after" timeout-ms "ms")
            false)
          
          (check-server-availability host port 1000)
          (do
            (println "\n✅ Server is now available!")
            true)
          
          :else
          (do
            (print (str "\r   Attempt " attempt " (" elapsed "ms) ... "))
            (flush)
            (Thread/sleep interval-ms)
            (recur (inc attempt))))))))

;; Утилиты для работы с сетевыми исключениями
(defn network-error-type [exception]
  "Определяет тип сетевой ошибки"
  (let [msg (.getMessage exception)
        lower-msg (str/lower-case msg)]
    (cond
      (re-find #"connection refused" lower-msg) :connection-refused
      (re-find #"timeout" lower-msg) :timeout
      (re-find #"unknown host" lower-msg) :unknown-host
      (re-find #"network is unreachable" lower-msg) :network-unreachable
      :else :unknown)))

(defn suggest-solution [error-type]
  "Предлагает решение для сетевой ошибки"
  (case error-type
    :connection-refused "Server is not running or port is blocked"
    :timeout "Network latency is too high or server is overloaded"
    :unknown-host "Check hostname spelling and DNS configuration"
    :network-unreachable "Check network connection and firewall"
    "Unknown network error - check server status and network configuration"))
