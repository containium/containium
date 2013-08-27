;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.core
  (:require [containium.cassandra :as cassandra]
            [containium.elasticsearch :as elastic]
            [containium.kafka :as kafka]
            [containium.http-kit :as http-kit]
            [clojure.edn :as edn]
            [clojure.java.io :refer (resource)]
            [clojure.string :refer (split trim)]
            [boxure.core :refer (boxure) :as boxure]
            [clojure.tools.nrepl.server :as nrepl])
  (:import [jline.console ConsoleReader]))


;;; Globals for nREPL access. A necessary evil.

(defonce globals (atom {}))


;;; Root systems logic.

(defn with-systems
  "This function starts the root systems for the containium. The
  `system-components` argument is a sequence of triples, where each
  triple contains an identifier (likely a keyword) for the root
  system, the symbol pointing to the start function of the system and
  a symbol to the stop function of the system. The start function
  takes the config from spec as an argument, whereas the stop function
  takes that what the start function returned as an argument."
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


;;; Box logic.

(defn check-project
  [project]
  (->> (list (when-not (:containium project)
               "Missing :containium configuration in project.clj.")
             (when-not (-> project :containium :start)
               "Missing :start configuration in :containium of project.clj.")
             (when-not (-> project :containium :stop)
               "Missing :stop configuration in :containium of project.clj.")
             (when (-> project :containium :ring)
               (when-not (-> project :containium :ring :handler-sym)
                 "Missing :handler-sym configuration in :ring of :containium of project.clj.")))
       (remove nil?)))


;;--- FIXME: Check for (and allow somehow?) for duplicates.

(defn start-box
  "The logic for starting a box."
  [module resolve-dependencies root-config]
  (println "Starting module" module "...")
  (try
    (let [project (boxure/jar-project module)]
      (if-let [errors (seq (check-project project))]
        (apply println "Could not start module" (:name project) "for the following reasons:\n  "
               (interpose "\n  " errors))
        (let [box (boxure {:resolve-dependencies resolve-dependencies
                           :isolate "containium.*"}
                          (.getClassLoader clojure.lang.RT)
                          module)
              module-config (:containium project)
              start-result @(boxure/eval box
                                         `(do (require '~(symbol (namespace (:start module-config))))
                                              (~(:start module-config) ~root-config)))]
          (if (instance? Throwable start-result)
            (do (boxure/clean-and-stop box)
                (throw start-result))
            (try
              (when (:ring module-config) (http-kit/upstart-box box))
              (println "Module" (:name box) "started.")
              (assoc box :start-result start-result)
              (catch Throwable ex
                (boxure/clean-and-stop box)
                (throw ex)))))))
    (catch Throwable ex
      (println "Exception while starting module" module ":" ex)
      (.printStackTrace ex))))


(defn start-boxes
  "Starts all the boxes in the specified spec."
  [spec systems]
  (let [{:keys [config modules resolve-dependencies]} spec]
    (loop [modules modules
           boxes {}]
      (if-let [module (first modules)]
        (if-let [result (start-box module resolve-dependencies config)]
          (recur (rest modules) (assoc boxes (:name result) result))
          (recur (rest modules) boxes))
        boxes))))


(defn stop-box
  [box]
  (let [name (:name box)
        module-config (-> box :project :containium)]
    (println "Stopping module" name "...")
    (try
      (when (:ring module-config) (http-kit/remove-box box))
      (let [stop-result @(boxure/eval box
                                      `(do (require '~(symbol (namespace (:stop module-config))))
                                           (~(:stop module-config) ~(:start-result box))))]
        (when (instance? Throwable stop-result)
          (println "Module" name "reported exception when stopping:" stop-result)))
      (catch Throwable ex
        (println "Exception while stopping module" name ":" ex)
        (.printStackTrace ex)
        ;; Kill box here?
        )
      (finally
        (boxure/clean-and-stop box)
        (println "Module" name "stopped.")))))


(defn stop-boxes
  "Calls the stop function of all boxes in the specefied boxes map."
  [boxes]
  (doseq [box (vals boxes)]
    (stop-box box)))


;;; Command loop.

(defmulti handle-command
  "This multi-method dispatches on the command argument. It also
  receives the command arguments, the spec, the current command-loop
  state and the running boxes map. This method may return a pair,
  where the first item is an updated state map, and the second item is
  an updated boxes map. If no value is returned, or a value in the
  pair is nil, then the state and/or boxes are not updated in the
  command-loop."
  (fn [command args spec state boxes] command))


(defmethod handle-command :default
  [command _ _ _ _]
  (println "Unknown command:" command)
  (println "Type 'help' for info on the available commands."))


(defmethod handle-command "help"
  [_ _ _ _ _]
  (println (str "Available commands are:"
                "\n"
                "\n module <list|start|stop> [path|name]"
                "\n   Prints a list of running modules."
                "\n"
                "\n repl <start|stop> [port]"
                "\n   Starts an nREPL at the specified port, or stops the current one, inside"
                "\n   the containium."
                "\n"
                "\n shutdown"
                "\n   Stops all boxes and systems gracefully."
                "\n"
                "\n threads"
                "\n   Prints a list of all threads.")))


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


(defmethod handle-command "threads"
  [_ _ _ _ _]
  (let [threads (keys (Thread/getAllStackTraces))]
    (println (apply str "Current threads (" (count threads) "):\n  "
                    (interpose "\n  " threads)))))


(defmethod handle-command "module"
  [_ args spec _ boxes]
  (let [[action arg] args]
    (case action
      "list" (println (apply str "Current modules (" (count boxes) "):\n  "
                             (interpose "\n  " (keys boxes))))
      "start" (let [{:keys [config resolve-dependencies]} spec]
                (if arg
                  (when-let [box (start-box arg resolve-dependencies config)]
                    [nil (assoc boxes (:name box) box)])
                  (println "Missing path argument.")))
      "stop" (if arg
               (if-let [box (boxes arg)]
                 (do (stop-box box)
                     [nil (dissoc boxes arg)])
                 (println "Unknown module:" arg))
               (println "Missing name argument."))
      (println (str "Unknown action '" action "', please use 'list', 'start' or 'stop'.")))))


(defn shutdown-state
  "Go over the current command-loop state, and shutdown anything that
  needs shutting down when the containium is about to stop."
  [state]
  (when-let [server (:nrepl state)]
    (nrepl/stop-server server)
    (println "nREPL server stopped.")))


(defn handle-commands
  "This functions starts the command loop. It uses the handle-command
  multi-method for handling the individual commands (except shutdown).
  See the documentation on the handle-command for more info on this.
  This function receives the spec, the root systems and a map of
  started boxes. More boxes may be started from the command loop, or
  stopped. Therefore, this function returns an updated map of
  currently running boxes."
  [spec systems boxes]
  ;; Handle commands like starting and stopping modules, and stopping the application.
  ;; This can be done through typing here, updates on the file system, through sockets...
  (let [jline (ConsoleReader.)]
    (loop [state {}
           boxes boxes]
      (swap! globals assoc :boxes boxes)
      (let [[command & args] (split (trim (.readLine jline "containium> ")) #"\s+")]
        (case command
          "" (recur state boxes)
          "shutdown" (do (shutdown-state state) boxes)
          (let [[new-state new-boxes] (handle-command command args spec state boxes)]
            (recur (or new-state state) (or new-boxes boxes))))))))


;;; The coordinating functions.

(defn run
  "This function is used for the with-systems function. It is called
  when all root systems are up and running. Currently it starts the
  boxes, enters the command loop, and stops the boxes when the command
  loop exited."
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
                   [:kafka kafka/start kafka/stop]
                   [:http-kit http-kit/start http-kit/stop]]
      (:config spec) {} (partial run spec)))
  (shutdown-agents))
