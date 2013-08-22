;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.core
  (:require [containium.cassandra :as cassandra]
            [clojure.edn :as edn]
            [clojure.java.io :refer (resource)]
            [boxure.core :refer (boxure)]))


(defn start-elastic [config]
  (println "Starting ElasticSearch..."))

(defn stop-elastic [elastic]
  (println "Stopping ElasticSearsch..."))


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
                       :isolate ""}
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


(defn handle-commands
  [spec systems boxes]
  ;; Handle commands like starting and stopping modules, and stopping the application.
  ;; This can be done through typing here, updates on the file system, through sockets...
  (println "Press enter to stop...")
  (read-line)
  boxes)


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
  (let [boxes (start-boxes spec systems)
        boxes (handle-commands spec systems boxes)]
    (stop-boxes boxes)))


(defn -main
  [& args]
  (let [spec (-> "spec.clj" resource slurp edn/read-string)]
    (with-systems [[:cassandra cassandra/start-cassandra cassandra/stop-cassandra]
                   [:elastic start-elastic stop-elastic]]
      (:config spec) {} (partial run spec))))
