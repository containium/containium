;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.cassandra
  "Functions for starting and stopping an embedded Cassandra instance."
  (:require [containium.systems]
            [containium.systems.config :refer (get-config)]
            [clojure.java.io :refer (copy)])
  (:import [containium.systems Startable Stoppable]
           [org.apache.cassandra.cql3 QueryProcessor UntypedResultSet]
           [org.apache.cassandra.db ConsistencyLevel]
           [org.apache.cassandra.service CassandraDaemon ClientState QueryState]
           [org.apache.cassandra.transport.messages ResultMessage$Rows]
           [containium ByteBufferInputStream]
           [java.io ByteArrayOutputStream]
           [java.util Arrays]
           [java.nio CharBuffer ByteBuffer]
           [java.nio.charset Charset]))


;;; The public API for embedded Cassandra systems.

(defprotocol EmbeddedCassandra
  (prepare [this query])
  (do-prepared [this prepared consistency args]))


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


(defrecord EmbeddedCassandra12 [daemon thread client-state query-state]
  EmbeddedCassandra
  (prepare [_ query]
    (-> query
        (QueryProcessor/prepare client-state false)
        .statementId
        QueryProcessor/getPrepared))

  (do-prepared [_ prepared consistency args]
    (let [consistency (case consistency
                        :any ConsistencyLevel/ANY
                        :one ConsistencyLevel/ONE
                        :two ConsistencyLevel/TWO
                        :three ConsistencyLevel/THREE
                        :quorum ConsistencyLevel/QUORUM
                        :all ConsistencyLevel/ALL
                        :local-quorum ConsistencyLevel/LOCAL_QUORUM
                        :each-quorum ConsistencyLevel/EACH_QUORUM)
          result (QueryProcessor/processPrepared prepared consistency query-state
                                                 (map ->bytebuffer args))]
      (when (instance? ResultMessage$Rows result)
        (UntypedResultSet. (.result ^ResultMessage$Rows result)))))

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
      (let [config (get-config (:config systems) :cassandra)]
        (println "Starting embedded Cassandra using config" config)
        (System/setProperty "cassandra.config" (:config-file config))
        (System/setProperty "cassandra-foreground" "false")
        (let [daemon (CassandraDaemon.)
              thread (Thread. #(.activate daemon))
              client-state (ClientState. true)
              query-state (QueryState. client-state)]
          (.setDaemon thread true)
          (.start thread)
          (println "Waiting for Cassandra to be fully started...")
          (while (not (some-> daemon .nativeServer .isRunning)) (Thread/sleep 200))
          (println "Cassandra fully started.")
          (EmbeddedCassandra12. daemon thread client-state query-state))))))
