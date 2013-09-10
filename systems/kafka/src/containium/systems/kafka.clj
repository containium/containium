;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.kafka
  (:require [containium.systems :refer (->AppSystem)])
  (:import  [kafka.server KafkaConfig KafkaServer]
            [java.util Properties]))


(defn start
  [config systems]
  (println "Starting embedded Kafka using config:" config)
  (let [server-props (doto (Properties.)
                       (.setProperty "port" (:port config)) ;
                       (.setProperty "brokerid" (:broker-id config))
                       (.setProperty "log.dir" (:log-dir config))
                       (.setProperty "zk.connect" (:zk-connect config)))
        server (KafkaServer. (KafkaConfig. server-props))]
    (.startup server)
    (println "Embedded Kafka started.")
    server))


(defn stop
  [server]
  (println "Stoppind embedded Kafka...")
  (.shutdown server)
  (println "Waiting for embedded Kafka to be fully stopped.")
  (.awaitShutdown server)
  (println "Embedded Kafka fully stopped."))


(def system (->AppSystem start stop nil))
