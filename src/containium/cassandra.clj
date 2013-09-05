;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.cassandra
  "Functions for starting and stopping an embedded Cassandra instance."
  (:require [containium.systems :refer (->AppSystem)])
  (:import [org.apache.cassandra.service CassandraDaemon]))


(defrecord Cassandra [daemon thread])


(defn start
  "Start a Cassandra instance. Give a config location, for example
  `file:dev-resources/cassandra.yaml`. Returns the Server."
  [config systems]
  (println "Starting embedded Cassandra using config" (:cassandra config))
  (System/setProperty "cassandra.config" (-> config :cassandra :config-file))
  (System/setProperty "cassandra-foreground" "false")
  (let [daemon (CassandraDaemon.)
        thread (Thread. #(.activate daemon))]
    (.setDaemon thread true)
    (.start thread)
    (println "Waiting for Cassandra to be fully started...")
    (while (not (some-> daemon .nativeServer .isRunning)) (Thread/sleep 200))
    (println "Cassandra fully started.")
    (new Cassandra daemon thread)))


(defn stop
  "Stop a Cassandra server instance."
  [{:keys [daemon thread] :as cassandra}]
  (println "Stopping embedded Cassandra instance...")
  (.deactivate daemon)
  (.interrupt thread)
  (println "Waiting for Cassandra to be stopped...")
  (while (some-> daemon .nativeServer .isRunning) (Thread/sleep 200))
  (println "Embedded Cassandra instance stopped."))


(defn running?
  "Returns whether the supplied server is running and ready to receive
  connections."
  [{:keys [daemon] :as cassandra}]
  (when-let [s (.nativeServer daemon)]
    (.isRunning s)))


(def system (->AppSystem start stop nil))
