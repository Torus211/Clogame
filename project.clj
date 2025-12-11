(defproject arena "2.0.0-BOSS"
  :description "Multiplayer arena game with Prolog AI boss in Clojure"
  :url "https://github.com/your-username/arena-game"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.async "1.5.648"]
                 [http-kit "2.5.3"]
                 [cheshire "5.10.0"]
                 [quil "2.8.0"]]
  
  :main arena.core
  :target-path "target/%s"
  :resource-paths ["resources"]
  
  :jvm-opts ["-Xmx512m"
             "-XX:+UseG1GC"]
  
  :profiles {:uberjar {:aot :all
                       :uberjar-name "arena-game-standalone.jar"}}
  
  :aliases {"server" ["run" "server"]
            "client" ["run" "client"]
            "build-uberjar" ["uberjar"]})
