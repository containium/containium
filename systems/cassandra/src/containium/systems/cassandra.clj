;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.cassandra
  "Functions for starting and stopping an embedded Cassandra instance."
  (:refer-clojure :exclude (replace))
  (:require [containium.systems :refer (require-system)]
            [containium.systems.config :refer (Config get-config)]
            [clojure.java.io :refer (copy)]
            [clojure.string :refer (replace split)])
  (:import [containium.systems Startable Stoppable]
           [org.apache.cassandra.cql3 QueryOptions QueryProcessor UntypedResultSet]
           [org.apache.cassandra.db ConsistencyLevel]
           [org.apache.cassandra.service CassandraDaemon QueryState]
           [org.apache.cassandra.transport.messages ResultMessage$Rows]
           [containium ByteBufferInputStream]
           [java.io ByteArrayOutputStream]
           [java.util Arrays]
           [java.nio CharBuffer ByteBuffer]
           [java.nio.charset Charset]))


;;; The public API for embedded Cassandra systems.

(defprotocol EmbeddedCassandra
  (prepare [this query]
    "Returns a CQLStatment that contains the prepared query String.")

  (^UntypedResultSet do-prepared [this prepared consistency args]
    "Executes a prepared CQLStatement. Consistency is one of :any, :one,
  :two, :three, :quorum, :all, :local-quorum or :each-quorum. The args
  argument is a sequence containing the position arguments for the
  query.")

  (has-keyspace? [this name]
    "Returns a boolean indicating whether the named keyspace exists.")

  (write-schema [this schema-str]
    "Writes a CQL schema String to the database. Comments are filtered
  out automatically and the statements are executed in sequence."))


(defn bytebuffer->inputstream
  "Returns an InputStream reading from a ByteBuffer."
  [^ByteBuffer bb]
  (ByteBufferInputStream. bb))


(defn bytebuffer->bytes
  "Converts a ByteBuffer to a byte array. If the ByteBuffer is backed
  by an array, a copy of the relevant part of that array is returned.
  Otherwise, the bytes are streamed into a byte array."
  [^ByteBuffer bb]
  (if (.hasArray bb)
    (Arrays/copyOfRange (.array bb)
                        (+ (.position bb) (.arrayOffset bb))
                        (+ (.position bb) (.arrayOffset bb) (.limit bb)))
    (let [baos (ByteArrayOutputStream. (.remaining bb))]
      (copy (bytebuffer->inputstream bb) baos)
      (.toByteArray baos))))


;;; The Cassandra 1.2 implementation.

(defn- ->bytebuffer
  "Converts some basic type to a ByteBuffer. Currently supported types
  are: String, Long, byte array and ByteBuffer."
  [primitive]
  (condp instance? primitive
    String
    (let [encoder (.newEncoder (Charset/forName "UTF-8"))]
      (.encode encoder (CharBuffer/wrap ^String primitive)))

    (Class/forName "[B")
    (ByteBuffer/wrap primitive)

    Long
    (.putLong (ByteBuffer/allocate 8) primitive)

    ByteBuffer
    primitive))


(defn- cql-statements
  "Returns the CQL statements from the specified String in a sequence."
  [s]
  (let [no-comments (-> s
                        (replace #"(?s)/\*.*?\*/" "")
                        (replace #"--.*$" "")
                        (replace #"//.*$" ""))]
    (map #(str % ";") (split no-comments #"\s*;\s*"))))


(defn- prepare*
  [client-state query]
  (-> query
      (QueryProcessor/prepare client-state false)
      .statementId
      QueryProcessor/getPrepared))


(defrecord EmbeddedCassandra12 [^CassandraDaemon daemon ^Thread thread client-state query-state keyspace-q]
  EmbeddedCassandra
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


(def embedded12
  (reify Startable
    (start [_ systems]
      (let [config (get-config (require-system Config systems) :cassandra)]
        (println "Starting embedded Cassandra, using config" config "...")
        ; [!] DO NOT IMPORT ClientState, or Cassandra's configuration will intiailize before the call to System/setProperty
        (System/setProperty "cassandra.config" (:config-file config))
        (System/setProperty "cassandra-foreground" "false")
        (let [daemon (CassandraDaemon.)
              thread (Thread. #(.activate daemon))
              client-state (org.apache.cassandra.service.ClientState. true)
              keyspace-q (prepare* client-state
                                   "SELECT * FROM system.schema_keyspaces WHERE keyspace_name = ?;")
              query-state (QueryState. client-state)]
          (.setDaemon thread true)
          (.start thread)
          (println "Waiting for Cassandra to be fully started...")
          (while (not (some-> daemon .nativeServer .isRunning)) (Thread/sleep 200))
          (println "Cassandra fully started.")
          (EmbeddedCassandra12. daemon thread client-state query-state keyspace-q))))))
