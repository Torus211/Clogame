(ns arena.core
  (:gen-class)
  (:require [arena.server :as server]
            [arena.bot :as bot]
            [arena.network-utils :as net]
            [clojure.string :as str]))

(defn get-local-ip []
  (try
    (net/get-local-ip)
    (catch Exception e
      (println "⚠️  Could not determine local IP:" (.getMessage e))
      "localhost")))

(defn print-server-info []
  (let [local-ip (get-local-ip)]
    (println "
   ___                  _    
  / _ \\__ _ _ __   __ _| | __
 / /_)/ _` | '_ \\ / _` | |/ /
/ ___/ (_| | | | | (_| |   < 
\\/    \\__,_|_| |_|\\__,_|_|\\_\\
                             
  ")
    (println "🚀 ARENA SERVER STARTED SUCCESSFULLY!")
    (println "======================================")
    (println "📍 Local connection: localhost:8080")
    (println "🌐 Network connection:" local-ip ":8080")
    (println "")
    (println "🎯 GAME FEATURES:")
    (println "   • Boss with 1000 HP and Prolog AI")
    (println "   • Multiplayer arena combat")
    (println "   • Health, Speed, and Damage bonuses")
    (println "   • Real-time WebSocket communication")
    (println "")
    (println "👥 Players can connect using:")
    (println "   - IP address:" local-ip)
    (println "   - Hostname: localhost")
    (println "")
    (println "🎮 Controls: WASD + Mouse | F3: Debug | Ctrl+R: Reconnect")
    (println "")
    (println "⏹️  Press Ctrl+C to stop the server")))

(defn print-client-info [server-ip]
  (println "
   ___                  _    
  / _ \\__ _ _ __   __ _| | __
 / /_)/ _` | '_ \\ / _` | |/ /
/ ___/ (_| | | | | (_| |   < 
\\/    \\__,_|_| |_|\\__,_|_|\\_\\
                             
  ")
  (println "🎯 ARENA CLIENT STARTING...")
  (println "============================")
  (println "🔗 Connecting to server:" server-ip)
  (println "")
  (println "🎯 GAME FEATURES:")
  (println "   • Battle against AI boss with 1000 HP")
  (println "   • Multiplayer PvP and PvE combat")
  (println "   • Collect bonuses for advantages")
  (println "   • Real-time action with smooth controls")
  (println "")
  (println "🎮 CONTROLS:")
  (println "   Movement: WASD keys")
  (println "   Sprint: Hold Shift")
  (println "   Shoot: Mouse click or Arrow keys")
  (println "   Aim: Mouse position")
  (println "   Debug: F3 key")
  (println "   Reconnect: Ctrl+R")
  (println "")
  (println "💡 TIP: Focus the game window and defeat the boss!"))

(defn validate-ip-address [ip]
  (or (= ip "localhost")
      (re-matches #"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$" ip)))

(defn discover-servers []
  (println "🔍 Searching for local game servers...")
  (println "   No automatic discovery implemented yet.")
  (println "   Please enter server IP manually.")
  [])

(defn interactive-server-selection []
  (println "")
  (println "🌐 SERVER SELECTION")
  (println "===================")
  (println "1 - Connect to localhost")
  (println "2 - Enter server IP manually")
  (println "3 - Search for local servers")
  (print "Choose option (1/2/3): ")
  (flush)
  
  (let [choice (read-line)]
    (case choice
      "1" "localhost"
      "2" (do
            (print "Enter server IP: ")
            (flush)
            (let [ip (read-line)]
              (if (validate-ip-address ip)
                ip
                (do
                  (println "❌ Invalid IP address. Using localhost.")
                  "localhost"))))
      "3" (do
            (let [servers (discover-servers)]
              (if (seq servers)
                (do
                  (println "Available servers:")
                  (doseq [[i server] (map-indexed vector servers)]
                    (println (str "  " (inc i) " - " server)))
                  (print "Select server: ")
                  (flush)
                  (let [selection (read-line)]
                    (get servers (dec (Integer/parseInt selection)))))
                "localhost")))
      "localhost")))

(defn start-server-mode []
  (println "")
  (println "🚀 STARTING ARENA SERVER...")
  (println "============================")
  
  (println "🤖 Initializing Prolog AI boss...")
  (bot/init-prolog)
  
  (server/start-server)
  (print-server-info)
  
  (println "")
  (println "Server is running. Press Ctrl+C to stop...")
  
  (try
    (while true
      (Thread/sleep 1000))
    (catch InterruptedException e
      (println "")
      (println "🛑 Server shutdown requested...")
      (server/stop-server)
      (println "✅ Server stopped successfully"))))

(defn start-client-mode [server-ip]
  (println "")
  (println "🎯 STARTING ARENA CLIENT...")
  (println "============================")
  
  (let [final-ip (if server-ip
                   server-ip
                   (interactive-server-selection))]
    
    (when (not (validate-ip-address final-ip))
      (println "❌ Invalid server address:" final-ip)
      (println "💡 Using localhost instead")
      (start-client-mode "localhost"))
    
    (when (validate-ip-address final-ip)
      (print-client-info final-ip)
      
      (try
        (require 'arena.client)
        (println "")
        (println "✅ Client loaded successfully")
        (println "🎮 Starting game interface...")
        
        (Thread/sleep 1000)
        
        ((resolve 'arena.client/start-client) final-ip)
        (catch Exception e
          (println "❌ Failed to start client:" (.getMessage e))
          (println "💡 Make sure JavaFX is properly configured")
          (System/exit 1))))))

(defn show-usage []
  (println "
USAGE:
  java -jar arena.jar server    - Start as game host (no graphics)
  java -jar arena.jar client    - Join game with interactive setup
  java -jar arena.jar client <IP> - Join specific server

EXAMPLES:
  java -jar arena.jar server
  java -jar arena.jar client
  java -jar arena.jar client localhost
  java -jar arena.jar client 192.168.1.100

ADVANCED:
  java -jar arena.jar debug     - Start with debug mode enabled
  java -jar arena.jar help      - Show this message

FEATURES:
  • Multiplayer arena combat with Prolog AI boss
  • Real-time WebSocket communication
  • Bonus system with power-ups
  • Smooth controls with mouse and keyboard"))

(defn start-debug-mode []
  (println "🔧 DEBUG MODE")
  (println "==============")
  (println "This would start both server and client for testing.")
  (println "Not implemented in this version.")
  (println "Use 'server' or 'client' mode instead."))

(defn -main
  "Запускает Arena в режиме сервера или клиента"
  [& args]
  (println "
   ___                  _    
  / _ \\__ _ _ __   __ _| | __
 / /_)/ _` | '_ \\ / _` | |/ /
/ ___/ (_| | | | | (_| |   < 
\\/    \\__,_|_| |_|\\__,_|_|\\_\\
                             
  ")
  (println "🎮 ARENA - MULTIPLAYER BOSS BATTLE")
  (println "===================================")
  (println "Version: 2.0 | With Prolog AI Boss")
  (println "")
  
  (let [mode (first args)
        arg1 (second args)]
    
    (cond
      (or (= mode "server") (= mode "1") (= mode "s"))
      (start-server-mode)
      
      (or (= mode "client") (= mode "2") (= mode "c"))
      (start-client-mode arg1)
      
      (= mode "debug")
      (start-debug-mode)
      
      (or (= mode "help") (= mode "-help") (= mode "--help") (= mode "h"))
      (show-usage)
      
      (nil? mode)
      (do
        (println "SELECT MODE:")
        (println "1 - 🚀 Start Server (Host Game with Boss)")
        (println "2 - 🎯 Start Client (Join Game)")
        (println "3 - ℹ️  Show Help")
        (print "Choose mode (1/2/3): ")
        (flush)
        
        (let [choice (read-line)]
          (case choice
            "1" (start-server-mode)
            "2" (start-client-mode nil)
            "3" (show-usage)
            (do
              (println "❌ Invalid choice")
              (show-usage)
              (System/exit 1)))))
      
      :else
      (do
        (println "❌ Unknown command:" mode)
        (println "")
        (show-usage)
        (System/exit 1)))))

(.addShutdownHook
 (Runtime/getRuntime)
 (Thread. (fn []
            (println "")
            (println "🛑 Shutting down Arena...")
            (try
              (server/stop-server)
              (catch Exception e
                (println "✅ Clean shutdown completed"))))))
