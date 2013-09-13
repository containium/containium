;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.kafka
  (:require [containium.systems]
            [containium.systems.config :refer (get-config Config)])
  (:import  [containium.systems Startable Stoppable]
            [kafka.server KafkaConfig KafkaServer]
            [kafka.javaapi.producer Producer ProducerData]
            [kafka.producer ProducerConfig]
            [java.util Properties]))


;;; The public API of the Kafka system.

(defprotocol Kafka
  (send-message [this topic message]))


;;; The embedded implementation.

(defn- map->properties
  ([map]
     (map->properties map (Properties.)))
  ([map ^Properties properties]
     (doseq [[k v] map]
       (.setProperty properties
                     (if (keyword? k) (name k) (str k))
                     (str v)))
     properties))


(defrecord EmbeddedKafka [^KafkaServer server ^Producer producer]
  Kafka
  (send-message [_ topic message]
    (.send producer (ProducerData. topic message)))

  Stoppable
  (stop [_]
    (println "Stopping embedded Kafka...")
    (.close producer)
    (.shutdown server)
    (println "Waiting for embedded Kafka to be fully stopped.")
    (.awaitShutdown server)
    (println "Embedded Kafka fully stopped.")))


(def embedded
  (reify Startable
    (start [_ systems]
      (assert (:config systems) "Kafka system requires a :config system.")
      (assert (satisfies? Config (:config systems))
              "Expected :config system to satisfy Config protocol.")
      (let [config (get-config (:config systems) :kafka)
            _ (println "Starting embedded Kafka using config:" config)
            server-properties (map->properties (:server config))
            server (doto (KafkaServer. (KafkaConfig. server-properties)) .startup)
            producer-properties (map->properties (:producer config))
            producer (Producer. (ProducerConfig. producer-properties))]
        (println "Embedded Kafka started.")
        (EmbeddedKafka. server producer)))))
