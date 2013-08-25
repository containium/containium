;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.core
  (:require [containium.cassandra :as cassandra]
            [containium.elasticsearch :as elastic]
            [containium.kafka :as kafka]
            [clojure.edn :as edn]
            [clojure.java.io :refer (resource)]
            [clojure.string :refer (split trim)]
            [boxure.core :refer (boxure)]
            [clojure.tools.nrepl.server :as nrepl])
  (:import [jline.console ConsoleReader]))


;;; Globals for nREPL access. A necessary evil.

(def globals (atom {}))


(defn with-systems
  [system-components config systems f]
  (if-let [[key start stop] (first system-components)]
    (try
      (let [system (start config)]
        (with-systems (rest system-components) config (assoc systems key system) f)
        (try
          (stop system)
          (catch Throwable ex
            (println "Exception while stopping system component" key ":" ex))))
      (catch Throwable ex
        (println "Exception while starting system component" key ":" ex)))
    (f systems)))



(defn start-box
  [module resolve-dependencies]
  (try
    (let [box (boxure {:resolve-dependencies resolve-dependencies
                       :isolate "containium.*"}
                      (.getClassLoader clojure.lang.RT)
                      module)]
      ;; Box start logic here.
      box)
    (catch Throwable ex
      (println "Exception while starting module" module ":" ex))))


(defn start-boxes
  [spec systems]
  (let [{:keys [config modules resolve-dependencies]} spec]
    (loop [modules modules
           boxes {}]
      (if-let [module (first modules)]
        (if-let [result (start-box module resolve-dependencies)]
          (recur (rest modules) (assoc boxes (:name result) result))
          (recur (rest modules) boxes))
        boxes))))


(defmulti handle-command
  "May return a pair, where the first item is an updated state, and
  the second item is an updated boxes. If no value is returned, or a
  value in the pair is nil, then the state and/or boxes are not
  updated in the command loop."
  (fn [command args spec state boxes] command))


(defmethod handle-command :default
  [command _ _ _ _]
  (println "Unknown command:" command)
  (println "Type 'help' for info on the available commands."))


(defmethod handle-command "help"
  [_ _ _ _ _]
  (println (str "Available commands are:"
                "\n shutdown                 - Stops all boxes and systems gracefully."
                "\n repl <start|stop> [port] - Starts an nREPL at the specified port, or stops the"
                "\n                            current one, inside the containium.")))

(defmethod handle-command "repl"
  [_ args spec state _]
  (let [[action port-str] args]
    (case action
      "stop" (if-let [server (:nrepl state)]
               (do (nrepl/stop-server server)
                   (println "nREPL server stopped.")
                   [(dissoc state :nrepl)])
               (println "No active nREPL server to stop."))
      "start" (if-let [server (:nrepl state)]
                (println "An nREPL server is already running, on port" (:port server))
                (if port-str
                  (if-let [port (try (Integer/parseInt port-str) (catch Exception ex))]
                    (let [server (nrepl/start-server :port port)]
                      (println "nREPL server started on port" port-str)
                      [(assoc state :nrepl server)])
                    (println "Invalid port number:" port-str))
                  (let [server (nrepl/start-server)]
                    (println "nREPL server started on port" (:port server))
                    [(assoc state :nrepl server)])))
      (println (str "Unknown action '" action "', please use 'start' or 'stop'.")))))


(defn shutdown-state
  [state]
  (when-let [server (:nrepl state)]
    (nrepl/stop-server server)
    (println "nREPL server stopped.")))


(defn handle-commands
  [spec systems boxes]
  ;; Handle commands like starting and stopping modules, and stopping the application.
  ;; This can be done through typing here, updates on the file system, through sockets...
  (let [jline (ConsoleReader.)]
    (loop [state {}
           boxes boxes]
      (swap! globals assoc :boxes boxes)
      (let [[command & args] (split (trim (.readLine jline "containium> ")) #"\s+")]
        (if (= "shutdown" command)
          (do (shutdown-state state)
              boxes)
          (let [[new-state new-boxes] (handle-command command args spec state boxes)]
            (recur (or new-state state) (or new-boxes boxes))))))))


(defn stop-boxes
  [boxes]
  (doseq [[name box] boxes]
    (try
      ;; Box stop logic here.
      (catch Throwable ex
        (println "Exception while stopping module" name ":" ex)
        ;; Kill box here?
        ))))


(defn run
  [spec systems]
  (swap! globals assoc :systems systems)
  (let [boxes (start-boxes spec systems)
        boxes (handle-commands spec systems boxes)]
    (stop-boxes boxes)))


(defn -main
  [& args]
  (let [spec (-> "spec.clj" resource slurp edn/read-string)]
    (swap! globals assoc :spec spec)
    (with-systems [[:cassandra cassandra/start cassandra/stop]
                   [:elastic elastic/start elastic/stop]
                   [:kafka kafka/start kafka/stop]]
      (:config spec) {} (partial run spec)))
  (shutdown-agents))
