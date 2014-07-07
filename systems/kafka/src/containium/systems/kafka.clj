;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.kafka
  (:require [containium.systems :refer (require-system)]
            [containium.systems.config :refer (get-config Config)]
            [containium.systems.logging :as logging :refer (SystemLogger refer-logging)])
  (:import  [containium.systems Startable Stoppable]
            [kafka.server KafkaConfig KafkaServer]
            [java.util Properties]))
(refer-logging)


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


(defrecord EmbeddedKafka [^KafkaServer server logger]
  Kafka
  (get-server [_] server)

  Stoppable
  (stop [_]
    (info logger "Stopping embedded Kafka...")
    (.shutdown server)
    (info logger "Waiting for embedded Kafka to be fully stopped.")
    (.awaitShutdown server)
    (info logger "Embedded Kafka fully stopped.")))


(def embedded
  (reify Startable
    (start [_ systems]
      (let [config (get-config (require-system Config systems) :kafka)
            logger (require-system SystemLogger systems)
            _ (info logger "Starting embedded Kafka using config:" config)
            server-properties (map->properties (:server config))
            server (doto (KafkaServer. (KafkaConfig. server-properties) (kafka.utils.SystemTime$/MODULE$)) .startup)]
        (info logger "Embedded Kafka started.")
        (EmbeddedKafka. server logger)))))
