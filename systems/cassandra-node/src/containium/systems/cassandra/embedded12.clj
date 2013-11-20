;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.cassandra.embedded12
  "The embedded Cassandra 1.2 implementation."
  (:refer-clojure :exclude [replace])
  (:require [containium.systems :refer (Startable Stoppable require-system)]
            [containium.systems.cassandra :refer (Cassandra)]
            [containium.systems.config :refer (Config get-config)]
            [clojure.java.io :refer (copy)]
            [clojure.string :refer (replace split)])
  (:import [org.apache.cassandra.cql3 QueryProcessor ResultSet ColumnSpecification
            UntypedResultSet]
           [org.apache.cassandra.db ConsistencyLevel]
           [org.apache.cassandra.db.marshal AbstractType BooleanType BytesType DoubleType
            EmptyType FloatType InetAddressType Int32Type ListType LongType MapType SetType
            UTF8Type UUIDType]
           [org.apache.cassandra.service CassandraDaemon ClientState QueryState]
           [org.apache.cassandra.transport.messages ResultMessage$Rows]
           [containium ByteBufferInputStream]
           [java.io ByteArrayOutputStream]
           [java.util Arrays List Map Set UUID]
           [java.net InetAddress]
           [java.nio CharBuffer ByteBuffer]
           [java.nio.charset Charset]))


;;; Encoding functions.

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



;;; Helper functions.

(defn- cql-statements
  "Returns the CQL statements from the specified String in a sequence."
  [s]
  (let [no-comments (-> s
                        (replace #"(?s)/\*.*?\*/" "")
                        (replace #"--.*$" "")
                        (replace #"//.*$" ""))]
    (map #(str % ";") (split no-comments #"\s*;\s*"))))


(defn- kw->consistency
  "Given a consistency keyword, returns the corresponding
  ConsistencyLevel instance."
  [kw]
  (case kw
    :any ConsistencyLevel/ANY
    :one ConsistencyLevel/ONE
    :two ConsistencyLevel/TWO
    :three ConsistencyLevel/THREE
    :quorum ConsistencyLevel/QUORUM
    :all ConsistencyLevel/ALL
    :local-quorum ConsistencyLevel/LOCAL_QUORUM
    :each-quorum ConsistencyLevel/EACH_QUORUM))


;;--- TODO: Test laziness.
;;--- TODO: Test decoded types.
(defn- decode-resultset
  [^ResultSet resultset keywordize?]
  (let [^List metas (.. resultset metadata names)]
    (for [^List row (.rows resultset)]
      (->> (for [[^ColumnSpecification meta ^ByteBuffer column] (zipmap metas row)
                 :let [^AbstractType type (.type meta)
                       ^String name (.. meta name toString)]]
             [name (.compose type column)])
           (reduce (fn [m [k v]] (assoc m (if keywordize? (keyword k) k) v)) {})))))


(defprotocol Encode
  (abstract-type [value] "Returns the AbstractType for the given value")
  (encode-value [value] "Encodes a value to a Cassandra encoded ByteBuffer."))

(extend-protocol Encode
  Boolean
  (abstract-type [value] BooleanType/instance)
  (encode-value [value] (.decompose (abstract-type value) value))

  ByteBuffer
  (abstract-type [value] BytesType/instance)
  (encode-value [value] (.decompose (abstract-type value) value))

  Double
  (abstract-type [value] DoubleType/instance)
  (encode-value [value] (.decompose (abstract-type value) value))

  Float
  (abstract-type [value] FloatType/instance)
  (encode-value [value] (.decompose (abstract-type value) value))

  InetAddress
  (abstract-type [value] InetAddressType/instance)
  (encode-value [value] (.decompose (abstract-type value) value))

  Integer
  (abstract-type [value] Int32Type/instance)
  (encode-value [value] (.decompose (abstract-type value) value))

  List
  (abstract-type [value] (ListType/getInstance ^AbstractType (abstract-type (first value))))
  (encode-value [value] (into [] (.decompose (abstract-type value) value)))

  Map
  (abstract-type [value] (MapType/getInstance (abstract-type (ffirst value))
                                              (abstract-type (second (first value)))))
  (encode-value [value] (into {} (.decompose (abstract-type value) value)))

  Long
  (abstract-type [value] LongType/instance)
  (encode-value [value] (.decompose (abstract-type value) value))

  Set
  (abstract-type [value] (SetType/getInstance ^AbstractType (abstract-type (first value))))
  (encode-value [value] (into #{} (.decompose (abstract-type value) value)))

  String
  (abstract-type [value] UTF8Type/instance)
  (encode-value [value] (.decompose (abstract-type value) value))

  UUID
  (abstract-type [value] UUIDType/instance)
  (encode-value [value] (.decompose (abstract-type value) value))

  nil
  (abstract-type [value] EmptyType/instance)
  (encode-value [value] (.decompose (abstract-type value) value)))


;;; Cassandra protocol implementation.

(def ^:dynamic *consistency* nil)

(def ^:dynamic *keywordize* false)


(defn- prepare**
  [{:keys [client-state]} query]
  (-> query
      (QueryProcessor/prepare client-state false)
      .statementId
      QueryProcessor/getPrepared))


(def prepare* (memoize prepare**))


(defn- do-prepared*
  [{:keys [query-state]} prepared {:keys [consistency values raw? keywordize?]
                                   :or {consistency *consistency*
                                        keywordize? *keywordize*}}]
  (assert consistency "Missing :consistency key and *consistency* not bound.")
  (let [consistency (kw->consistency consistency)
        result (QueryProcessor/processPrepared prepared consistency query-state
                                               (map encode-value values))]
    (when (instance? ResultMessage$Rows result)
      (if raw?
        (UntypedResultSet. (.result ^ResultMessage$Rows result))
        (decode-resultset (.result ^ResultMessage$Rows result) keywordize?)))))


(defn- has-keyspace*
  [record name]
  (let [pq (prepare* record "SELECT * FROM system.schema_keyspaces WHERE keyspace_name = ?;")]
    (not (.isEmpty ^UntypedResultSet (do-prepared* record pq {:consistency :one
                                                              :values [name]
                                                              :raw? true})))))


(defn- write-schema*
  [record schema-str]
  (doseq [s (cql-statements schema-str)
          :let [ps (prepare* record s)]]
    (do-prepared* record ps {:consistency :one, :raw? true})))


(defrecord EmbeddedCassandra12 [^CassandraDaemon daemon ^Thread thread client-state query-state]
  Cassandra
  (prepare [this query]
    (prepare* this query))

  (do-prepared [this prepared args]
    (do-prepared* this prepared args))

  (has-keyspace? [this name]
    (has-keyspace* this name))

  (keyspaced [this name]
    (let [keyspaced-state (doto (ClientState. true) (.setKeyspace name))]
      (EmbeddedCassandra12. nil nil keyspaced-state (QueryState. keyspaced-state))))

  (write-schema [this schema-str]
    (write-schema* this schema-str))

  Stoppable
  (stop [this]
    (if (and daemon thread)
      (do (println "Stopping embedded Cassandra instance...")
          (.deactivate daemon)
          (.interrupt thread)
          (println "Waiting for Cassandra to be stopped...")
          (while (some-> daemon .nativeServer .isRunning) (Thread/sleep 200))
          (println "Embedded Cassandra instance stopped."))
      (println "Cannot call stop on a keyspaced instance."))))


(def embedded12
  (reify Startable
    (start [_ systems]
      (let [config (get-config (require-system Config systems) :cassandra)]
        (println "Starting embedded Cassandra, using config" config "...")
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
