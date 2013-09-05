;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.ring-session-cassandra
  (:require [ring.middleware.session.store :refer (SessionStore read-session)]
            [taoensso.nippy :refer (freeze thaw)]
            [clojure.core.cache :refer (ttl-cache-factory)]
            [clojure.java.io :refer (copy)])
  (:import [org.apache.cassandra.cql3 QueryProcessor UntypedResultSet]
           [org.apache.cassandra.db ConsistencyLevel]
           [org.apache.cassandra.service ClientState QueryState]
           [org.apache.cassandra.transport.messages ResultMessage$Rows]
           [containium ByteBufferInputStream]
           [java.io ByteArrayOutputStream]
           [java.util UUID Arrays]
           [java.nio CharBuffer ByteBuffer]
           [java.nio.charset Charset]))


;;; Helper functions.

(defn- ->bytebuffer
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


(defn- bytebuffer->bytes
  [^ByteBuffer bb]
  (if (.hasArray bb)
    (Arrays/copyOfRange (.array bb)
                        (+ (.position bb) (.arrayOffset bb))
                        (+ (.position bb) (.arrayOffset bb) (.limit bb)))
    (let [baos (ByteArrayOutputStream. (.remaining bb))]
      (copy (ByteBufferInputStream. bb) baos)
      (.toByteArray baos))))


;;; General Cassandra functions.

(def client-state (ClientState. true))
(def query-state (QueryState. client-state))


(defn- prepared-query
  [query-str]
  (-> query-str
      (QueryProcessor/prepare client-state false)
      .statementId
      QueryProcessor/getPrepared))


(defn- do-prepared
  [pp-query consistency & args]
  (let [consistency (case consistency
                      :any ConsistencyLevel/ANY
                      :one ConsistencyLevel/ONE
                      :two ConsistencyLevel/TWO
                      :three ConsistencyLevel/THREE
                      :quorum ConsistencyLevel/QUORUM
                      :all ConsistencyLevel/ALL
                      :local-quorum ConsistencyLevel/LOCAL_QUORUM
                      :each-quorum ConsistencyLevel/EACH_QUORUM)
        result (QueryProcessor/processPrepared pp-query consistency query-state
                                               (map ->bytebuffer args))]
    (when (instance? ResultMessage$Rows result)
      (UntypedResultSet. (.result ^ResultMessage$Rows result)))))


;;; Session Cassandra definitions.

(def read-query
  (delay (prepared-query "SELECT data FROM ring.sessions WHERE key = ?;")))

(defn- write-query*
  [ttl]
  (prepared-query (str "UPDATE ring.sessions USING TTL " (* ttl 60 2) " SET data = ? WHERE key = ?;")))

(def write-query (memoize write-query*))

(def remove-query
  (delay (prepared-query "DELETE FROM ring.sessions WHERE key = ?;")))


(def session-consistency :one)

(defn- read-session-data
  [key]
  (when-let [^UntypedResultSet data (do-prepared (deref read-query) session-consistency key)]
    (when-not (.isEmpty data)
      (-> (.. data one (getBytes "data") slice)
          bytebuffer->bytes
          thaw))))


(defn- write-session-data
  [key data ttl]
  (do-prepared (write-query ttl) session-consistency (freeze data) key))


(defn- remove-session-data
  [key]
  (do-prepared (deref remove-query) session-consistency key))


;;; Ring session store using Cassandra.

(deftype CassandraStore [cache ttl]
  SessionStore
  (read-session [_ key]
    (or (and key
             (or (get (deref cache) key)
                 (read-session-data key)))
        {}))
  (write-session [this key data]
    (let [new-key (or key (str (UUID/randomUUID)))
          new-data (if-not (and (get data '_last_db_write)
                                (< (- (System/currentTimeMillis) (* ttl 60000)) (data '_last_db_write))
                                (= data (read-session this key)))
                     (assoc data '_last_db_write (System/currentTimeMillis))
                     data)]
      (when-not (= data new-data)
        (write-session-data new-key new-data ttl))
      (swap! cache assoc new-key new-data)
      new-key))
  (delete-session [_ key]
    (when key
      (remove-session-data key)
      (swap! cache dissoc key))
    nil))


(defn start
  [config systems]
  (println "Creating Cassandra Ring session store, using config:" (:ring config) ".")
  ;;---TODO: Try to connect here or something? Or automaticaly write schema if not existing?
  (let [session-ttl (-> config :ring :session-ttl)]
    (CassandraStore. (atom (ttl-cache-factory {} :ttl (* session-ttl 60000)))
                     session-ttl)))


(defn stop
  [_])
