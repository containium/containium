;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.cassandra.embedded20
  "Functions for starting and stopping an embedded Cassandra 2.0 instance."
  (:refer-clojure :exclude (replace))
  (:require [containium.systems :refer (require-system)]
            [containium.systems.config :refer (Config get-config)]
            [containium.systems.cassandra :refer (Cassandra cql-statements)]
            [containium.systems.cassandra.config]
            [clojure.java.io :refer (copy)]
            [clojure.string :refer (replace split)])
  (:import [containium.systems Startable Stoppable]
           [org.apache.cassandra.cql3 QueryOptions QueryProcessor UntypedResultSet]
           [org.apache.cassandra.db ConsistencyLevel]
           [org.apache.cassandra.service CassandraDaemon QueryState]
           [org.apache.cassandra.transport.messages ResultMessage$Rows]
           [containium ByteBufferInputStream])


  (defn- prepare*
    [client-state query]
    (-> query
        (QueryProcessor/prepare client-state false)
        .statementId
        QueryProcessor/getPrepared))


  (defrecord EmbeddedCassandra20 [^CassandraDaemon daemon ^Thread thread client-state query-state keyspace-q]
    Cassandra
    (prepare [_ query]
      (prepare* client-state query))

    (do-prepared [_ prepared consistency args]
      (let [options (QueryOptions.
                     (case consistency
                       :any            ConsistencyLevel/ANY
                       :one            ConsistencyLevel/ONE
                       :two            ConsistencyLevel/TWO
                       :three          ConsistencyLevel/THREE
                       :quorum         ConsistencyLevel/QUORUM
                       :all            ConsistencyLevel/ALL
                       :local-quorum   ConsistencyLevel/LOCAL_QUORUM
                       :each-quorum    ConsistencyLevel/EACH_QUORUM)
                     (map ->bytebuffer args))
            result (QueryProcessor/processPrepared prepared query-state options)]
        (when (instance? ResultMessage$Rows result)
          (UntypedResultSet. (.result ^ResultMessage$Rows result)))))

    (has-keyspace? [this name]
      (not (.isEmpty (do-prepared this keyspace-q :one [name]))))

    (write-schema [this schema-str]
      (doseq [s (cql-statements schema-str)
              :let [ps (prepare this s)]]
        (do-prepared this ps :one [])))

    Stoppable
    (stop [this]
      (println "Stopping embedded Cassandra instance...")
      (.deactivate daemon)
      (.interrupt thread)
      (println "Waiting for Cassandra to be stopped...")
      (while (some-> daemon .nativeServer .isRunning) (Thread/sleep 200))
      (println "Embedded Cassandra instance stopped.")))

  (System/setProperty "cassandra.config.loader" (str *ns* ".config"))

  (defn do-start [config]
    (println "Starting embedded Cassandra")
                                        ; [!] DO NOT IMPORT ClientState, or Cassandra's configuration will intiailize before the call to System/setProperty
    (System/setProperty "cassandra.start_rpc"     "false")
    (System/setProperty "cassandra-foreground"    "false")
                                        ; Pass this config by binding, as the config Class gets constructed by Cassandra itself.
    (binding [containium.systems.cassandra.config/*system-config* config]
      (let [daemon (CassandraDaemon.)
            thread (Thread. #(.activate daemon))
            client-state (org.apache.cassandra.service.ClientState. true)
            keyspace-q (prepare* client-state
                                 "SELECT * FROM system.schema_keyspaces WHERE keyspace_name = ?;")
            query-state (QueryState. client-state)]
        (.setDaemon thread true)
        (.start thread)
        (println "Waiting for Cassandra to be fully started...")
        (while (not (some-> daemon .nativeServer .isRunning)) (Thread/sleep 20))
        (println "Cassandra fully started.")
        (EmbeddedCassandra20. daemon thread client-state query-state keyspace-q))))

  (def embedded20
    (reify Startable
      (start [_ systems]
        (do-start (get-config (require-system Config systems) :cassandra)))))
