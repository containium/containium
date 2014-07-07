;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.cassandra.alia1
  "The Alia 1 implementation of the Cassandra system."
  (:require [qbits.alia :as alia]
            [containium.systems :refer (require-system Startable Stoppable)]
            [containium.systems.config :as config :refer (Config)]
            [containium.systems.cassandra :refer (Cassandra cql-statements)]
            [containium.systems.logging :as logging :refer (SystemLogger refer-logging)]))
(refer-logging)


(def ^:dynamic *consistency* nil)

(def ^:dynamic *keywordize* false)


(defn- prepare*
  [{:keys [session]} query-str]
  (alia/prepare session query-str))


(defn- do-prepared*
  [{:keys [session]} statement opts values]
  (let [args (merge {:consistency *consistency*, :keywordize? *keywordize*} opts {:values values})]
    (assert (:consistency opts) "Missing :consistency key and *consistency* not bound.")
    (apply alia/execute session statement (interleave (keys args) (vals args)))))


(defn- has-keyspace*
  [record name]
  (let [pq (prepare* record "SELECT * FROM system.schema_keyspaces WHERE keyspace_name = ?;")]
    (not (empty? (do-prepared* record pq {:consistency :one} [name])))))


(defn- write-schema*
  [record schema-str]
  (doseq [s (cql-statements schema-str)
          :let [ps (prepare* record s)]]
    (do-prepared* record ps {:consistency :one} nil)))


(defrecord Alia1 [cluster session logger]
  Cassandra
  (prepare [this query-str]
    (prepare* this query-str))

  (do-prepared [this statement]
    (do-prepared* this statement nil nil))

  (do-prepared [this statement opts-values]
    (cond (sequential? opts-values) (do-prepared* this statement nil opts-values)
          (map? opts-values) (do-prepared* this statement opts-values (:values opts-values))
          :else (throw (IllegalArgumentException.
                        "Parameter opts-values must be a map or sequence."))))

  (do-prepared [this statement opts values]
    (do-prepared* this statement opts values))

  (has-keyspace? [this name]
    (has-keyspace* this name))

  (keyspaced [this name]
    (Alia1. cluster (alia/connect cluster name)))

  (write-schema [this schema-str]
    (write-schema* this schema-str))

  Stoppable
  (stop [this]
    (info logger "Stopping Alia 1 system...")
    (alia/shutdown cluster)
    (info logger "Alia 1 system stopped.")))


(defn alia1
  "Create a new Alia 1 Startable, using the specified key to lookup
  the connection details in the Config system."
  [config-key]
  (reify Startable
    (start [_ systems]
      (let [config (config/get-config (require-system Config systems) config-key)
            logger (require-system SystemLogger systems)
            _ (info logger "Starting Alia 1 system, using config:" config)
            cluster (apply alia/cluster
                           (:contact-points config)
                           (interleave (keys config) (vals config)))
            session (alia/connect cluster)]
        (info logger "Alia 1 system started.")
        (Alia1. cluster session logger)))))
