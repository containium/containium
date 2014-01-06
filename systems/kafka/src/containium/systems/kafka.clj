;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.kafka
  (:require [containium.systems :refer (require-system)]
            [containium.systems.config :refer (get-config Config)])
  (:import  [containium.systems Startable Stoppable]
            [kafka.server KafkaConfig KafkaServer]
            [java.util Properties]))


;;; The public API of the Kafka system.

(defprotocol Kafka
  (get-server [this]))


;;; The embedded implementation.

;FIXME: remove this copy of map->properties when prime-vo-kafka is a separate small library.
(defn- map->properties
  ([map]
     (map->properties map (Properties.)))
  ([map ^Properties properties]
     (doseq [[k v] map]
       (.setProperty properties
                     (if (keyword? k) (name k) (str k))
                     (str v)))
     properties))


(defrecord EmbeddedKafka [^KafkaServer server]
  Kafka
  (get-server [_] server)

  Stoppable
  (stop [_]
    (println "Stopping embedded Kafka...")
    (.shutdown server)
    (println "Waiting for embedded Kafka to be fully stopped.")
    (.awaitShutdown server)
    (println "Embedded Kafka fully stopped.")))


(def embedded
  (reify Startable
    (start [_ systems]
      (let [config (get-config (require-system Config systems) :kafka)
            _ (println "Starting embedded Kafka using config:" config)
            server-properties (map->properties (:server config))
            server (doto (KafkaServer. (KafkaConfig. server-properties) (kafka.utils.SystemTime$/MODULE$)) .startup)]
        (println "Embedded Kafka started.")
        (EmbeddedKafka. server)))))
