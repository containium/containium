;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.ring-session-cassandra
  "This namespace contains the Containium system offering a Ring
  SessionStore backed by Cassandra and Nippy."
  (:require [publizr.cassandra-util :refer (prepare do-prepared bytebuffer->bytes)]
            [containium.systems :refer (->AppSystem)]
            [ring.middleware.session.store :refer (SessionStore read-session)]
            [taoensso.nippy :refer (freeze thaw)]
            [clojure.core.cache :refer (ttl-cache-factory)])
  (:import [org.apache.cassandra.cql3 QueryProcessor UntypedResultSet]
           [java.util UUID]))


;;; Session Cassandra definitions.

(def ^:private read-query
  (delay (prepare "SELECT data FROM ring.sessions WHERE key = ?;")))

(defn- write-query*
  "Create a prepared update query, using the configured TTL (in
  minutes). The TTL is actually doubled for the query, such that as
  long as the session is in the local core.cache, it is also in the
  database, without requiring updates in the database each time for
  the sake of extending the TTL."
  [ttl]
  (prepare (str "UPDATE ring.sessions USING TTL " (* ttl 60 2) " SET data = ? WHERE key = ?;")))

(def ^:private write-query (memoize write-query*))

(def ^:private remove-query
  (delay (prepare "DELETE FROM ring.sessions WHERE key = ?;")))


(def ^:private session-consistency :one)

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
    ;; If the key is non-nil, try the cache or if that fails, try Cassandra.
    ;; If both fail, or the key was nil, an empty session is returned.
    (or (and key
             (or (get (deref cache) key)
                 (read-session-data key)))
        {}))

  (write-session [this key data]
    ;; Create a key, if necessary.
    ;; Update the data in the database, except if it already contains a _last_db_write entry,
    ;; and the time it specifies is younger than the current time minus TTL,
    ;; and the session data has not changed within the handling of the request.
    (let [new-key (or key (str (UUID/randomUUID)))
          new-data (if-not (and (get data '_last_db_write)
                                (< (- (System/currentTimeMillis) (* ttl 60000))
                                   (data '_last_db_write))
                                (= data (read-session this key)))
                     (assoc data '_last_db_write (System/currentTimeMillis))
                     data)]
      (when-not (= data new-data)
        (write-session-data new-key new-data ttl))
      (swap! cache assoc new-key new-data)
      new-key))

  (delete-session [_ key]
    ;; Remove the session data from the cache and the database. Return nil, in
    ;; order to have the session cookie removed.
    (when key
      (remove-session-data key)
      (swap! cache dissoc key))
    nil))


(defn start
  [config systems]
  (if (:cassandra systems)
    (let [session-ttl (:ttl config)]
      ;;---TODO: Try to connect here or something? Or automaticaly write schema if not existing?
      (println "Creating Cassandra Ring session store, using config:" config ".")
      (CassandraStore. (atom (ttl-cache-factory {} :ttl (* session-ttl 60000)))
                       session-ttl))
    (throw (Exception. (str "Could not start Cassandra Ring session store, as no :cassandra "
                            "system is registered.")))))


(def system (->AppSystem start nil (str "taoensso\\.nippy.*"
                                        "|ring.*"
                                        "|publizr\\.cassandra_util.*")))
