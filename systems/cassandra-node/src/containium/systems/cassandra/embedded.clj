;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.cassandra.embedded
  "The embedded Cassandra 2.0 implementation."
  (:require [containium.systems :refer (Startable Stoppable require-system)]
            [containium.systems.cassandra :refer (Cassandra cql-statements)]
            [containium.systems.cassandra.config :as cconf]
            [containium.systems.config :refer (Config get-config)]
            [containium.systems.logging :as logging :refer (SystemLogger refer-logging)])
  (:import [org.apache.cassandra.cql3 QueryProcessor ResultSet ColumnSpecification
            UntypedResultSet QueryOptions]
           [org.apache.cassandra.db ConsistencyLevel]
           [org.apache.cassandra.db.marshal AbstractType BooleanType BytesType DateType DecimalType
            DoubleType EmptyType FloatType InetAddressType Int32Type IntegerType ListType LongType
            MapType SetType UTF8Type UUIDType]
           [org.apache.cassandra.service CassandraDaemon QueryState]
           [org.apache.cassandra.transport.messages ResultMessage$Rows]
           [java.util List Map Set UUID Date]
           [java.net InetAddress]
           [java.nio CharBuffer ByteBuffer]
           [java.nio.charset Charset]
           [java.math BigInteger BigDecimal]))
(refer-logging)


;;; Helper functions.

(defn- kw->consistency
  "Given a consistency keyword, returns the corresponding
  ConsistencyLevel instance, or nil."
  [kw]
  (case kw
    :any ConsistencyLevel/ANY
    :one ConsistencyLevel/ONE
    :two ConsistencyLevel/TWO
    :three ConsistencyLevel/THREE
    :quorum ConsistencyLevel/QUORUM
    :all ConsistencyLevel/ALL
    :local-quorum ConsistencyLevel/LOCAL_QUORUM
    :each-quorum ConsistencyLevel/EACH_QUORUM
    nil))


(defn- clojurify
  [v]
  (condp instance? v
    Map  (into  {} v)
    Set  (into #{} v)
    List (seq v)
    v))


(defn- decode-resultset
  [^ResultSet resultset keywordize?]
  (let [^List metas (.. resultset metadata names)]
    (for [^List row (.rows resultset)]
      (->> (for [[^ColumnSpecification meta ^ByteBuffer column] (zipmap metas row)
                 :let [^AbstractType type (.type meta)
                       ^String name (.. meta name toString)]]
             [(if keywordize? (keyword name) #_else name) (clojurify (.compose type column))])
           (into {})))))


(defprotocol Encode
  (abstract-type [value] "Returns the AbstractType for the given value")
  (encode-value [value] "Encodes a value to a Cassandra encoded ByteBuffer."))

(extend-protocol Encode
  (Class/forName "[B")
  (abstract-type [value] BytesType/instance)
  (encode-value [value] (ByteBuffer/wrap value))

  BigDecimal
  (abstract-type [value] DecimalType/instance)
  (encode-value [value] (.decompose ^AbstractType (abstract-type value) value))

  BigInteger
  (abstract-type [value] IntegerType/instance)
  (encode-value [value] (.decompose ^AbstractType (abstract-type value) value))

  Boolean
  (abstract-type [value] BooleanType/instance)
  (encode-value [value] (.decompose ^AbstractType (abstract-type value) value))

  ByteBuffer
  (abstract-type [value] BytesType/instance)
  (encode-value [value] (.decompose ^AbstractType (abstract-type value) (.slice ^ByteBuffer value)))

  Date
  (abstract-type [value] DateType/instance)
  (encode-value [value] (.decompose ^AbstractType (abstract-type value) value))

  Double
  (abstract-type [value] DoubleType/instance)
  (encode-value [value] (.decompose ^AbstractType (abstract-type value) value))

  Float
  (abstract-type [value] FloatType/instance)
  (encode-value [value] (.decompose ^AbstractType (abstract-type value) value))

  InetAddress
  (abstract-type [value] InetAddressType/instance)
  (encode-value [value] (.decompose ^AbstractType (abstract-type value) value))

  Integer
  (abstract-type [value] Int32Type/instance)
  (encode-value [value] (.decompose ^AbstractType (abstract-type value) value))

  List
  (abstract-type [value] (ListType/getInstance ^AbstractType (abstract-type (first value))))
  (encode-value [value] (.decompose ^AbstractType (abstract-type value) value))

  Map
  (abstract-type [value] (MapType/getInstance (abstract-type (ffirst value))
                                              (abstract-type (second (first value)))))
  (encode-value [value] (.decompose ^AbstractType (abstract-type value) value))

  Long
  (abstract-type [value] LongType/instance)
  (encode-value [value] (.decompose ^AbstractType (abstract-type value) value))

  Set
  (abstract-type [value] (SetType/getInstance ^AbstractType (abstract-type (first value))))
  (encode-value [value] (.decompose ^AbstractType (abstract-type value) value))

  String
  (abstract-type [value] UTF8Type/instance)
  (encode-value [value] (.decompose ^AbstractType (abstract-type value) value))

  UUID
  (abstract-type [value] UUIDType/instance)
  (encode-value [value] (.decompose ^AbstractType (abstract-type value) value))

  nil
  (abstract-type [value] EmptyType/instance)
  (encode-value [value] (.decompose ^AbstractType (abstract-type value) value)))


;;; Cassandra protocol implementation.

(def ^:dynamic *consistency* nil)

(def ^:dynamic *keywordize* false)


(defn- prepare*
  [{:keys [client-state]} query]
  (let [result (QueryProcessor/prepare query client-state false)
        id (.statementId result)]
    (.getPrepared QueryProcessor/instance id)))


(defn- do-prepared*
  [{:keys [query-state]} statement opts values]
  (let [consistency (kw->consistency (get opts :consistency *consistency*))
        _ (assert consistency "Missing :consistency and *consistency* not bound.")
        options (QueryOptions. consistency (map encode-value values))
        result (.processPrepared QueryProcessor/instance statement query-state options)]
    (when (instance? ResultMessage$Rows result)
      (if (:raw? opts)
        (UntypedResultSet. (.result ^ResultMessage$Rows result))
        (decode-resultset (.result ^ResultMessage$Rows result)
                          (get opts :keywordize? *keywordize*))))))


(defn- has-keyspace*
  [record name]
  (let [pq (prepare* record "SELECT * FROM system.schema_keyspaces WHERE keyspace_name = ?;")]
    (not (.isEmpty ^UntypedResultSet
                   (do-prepared* record pq {:consistency :one, :raw? true} [name])))))


(defn- write-schema*
  [record schema-str]
  (doseq [s (cql-statements schema-str)
          :let [ps (prepare* record s)]]
    (do-prepared* record ps {:consistency :one, :raw? true} nil)))


(defrecord EmbeddedCassandra [^CassandraDaemon daemon ^Thread thread client-state query-state logger]
  Cassandra
  (prepare [this query]
    (prepare* this query))

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
    (let [keyspaced-state (doto (eval '(org.apache.cassandra.service.ClientState/forInternalCalls))
                            (.setKeyspace name))]
      (EmbeddedCassandra. nil nil keyspaced-state (QueryState. keyspaced-state) logger)))

  (write-schema [this schema-str]
    (write-schema* this schema-str))

  Stoppable
  (stop [this]
    (if (and daemon thread)
      (do (info logger "Stopping embedded Cassandra instance...")
          (.deactivate daemon)
          (.interrupt thread)
          (info logger "Waiting for Cassandra to be stopped...")
          (while (some-> daemon .nativeServer .isRunning) (Thread/sleep 200))
          (info logger "Embedded Cassandra instance stopped."))
      (warn logger "Cannot call stop on a keyspaced instance."))))


(def embedded
  (reify Startable
    (start [_ systems]
      (let [config (get-config (require-system Config systems) :cassandra)
            logger (require-system SystemLogger systems)]
        (info logger "Starting embedded Cassandra, using config" config "...")
        (System/setProperty "cassandra.start_rpc" "false")
        (System/setProperty "cassandra-foreground" "false")
        (System/setProperty "cassandra.config.loader" "containium.systems.cassandra.config")
        (binding [cconf/*system-config* config
                  cconf/*logger* logger]
          (let [daemon (CassandraDaemon.)
                thread (Thread. #(.activate daemon))
                client-state (eval '(org.apache.cassandra.service.ClientState/forInternalCalls))
                query-state (QueryState. client-state)]
            (.setDaemon thread true)
            (.start thread)
            (info logger "Waiting for Cassandra to be fully started...")
            (while (not (some-> daemon .nativeServer .isRunning)) (Thread/sleep 200))
            (info logger "Cassandra fully started.")
            (EmbeddedCassandra. daemon thread client-state query-state logger)))))))
