;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.ring-session-cassandra
  "This namespace contains the Containium system offering a Ring
  SessionStore backed by Cassandra and Nippy."
  (:require [containium.systems :refer (require-system)]
            [containium.systems.config :refer (Config get-config)]
            [containium.systems.cassandra :refer (EmbeddedCassandra prepare do-prepared
                                                                   bytebuffer->bytes)]
            [ring.middleware.session.store :refer (SessionStore read-session)]
            [taoensso.nippy :refer (freeze thaw)]
            [clojure.core.cache :refer (ttl-cache-factory)])
  (:import [containium.systems Startable]
           [org.apache.cassandra.cql3 QueryProcessor UntypedResultSet]
           [java.util UUID]))


;;; SessionStore implementation based on an embedded Cassandra.

(def ^:private session-consistency :one)

(defn- read-session-data
  [cassandra read-query key]
  (when-let [^UntypedResultSet data (do-prepared cassandra read-query session-consistency [key])]
    (when-not (.isEmpty data)
      (-> (.. data one (getBytes "data") slice)
          bytebuffer->bytes
          thaw))))


(defn- write-session-data
  [cassandra write-query key data]
  (do-prepared cassandra write-query session-consistency [(freeze data) key]))


(defn- remove-session-data
  [cassandra remove-query key]
  (do-prepared cassandra remove-query session-consistency [key]))


(defrecord EmbeddedCassandraStore [cache ttl cassandra read-q write-q remove-q]
  SessionStore
  (read-session [_ key]
    ;; If the key is non-nil, try the cache or if that fails, try Cassandra.
    ;; If both fail, or the key was nil, an empty session is returned.
    (or (and key
             (or (get (deref cache) key)
                 (read-session-data cassandra read-q key)))
        {}))

  (write-session [this key data]
    ;; Create a key, if necessary.
    ;; Update the data in the database, except if it already contains a _last_db_write entry,
    ;; and the time it specifies is younger than the current time minus TTL,
    ;; and the session data has not changed within the handling of the request.
    (let [new-key (or key (str (UUID/randomUUID)))
          new-data (if-not (and (get data ::last-db-write)
                                (< (- (System/currentTimeMillis) (* ttl 60000))
                                   (get data ::last-db-write))
                                (= data (read-session this key)))
                     (assoc data ::last-db-write (System/currentTimeMillis))
                     data)]
      (when-not (= data new-data)
        (write-session-data cassandra write-q new-key new-data))
      (swap! cache assoc new-key new-data)
      new-key))

  (delete-session [_ key]
    ;; Remove the session data from the cache and the database. Return nil, in
    ;; order to have the session cookie removed.
    (when key
      (remove-session-data cassandra remove-q key)
      (swap! cache dissoc key))
    nil))


(def embedded
  (reify Startable
    (start [_ systems]
      (let [cassandra (require-system EmbeddedCassandra systems)
            config (get-config (require-system Config systems) :session-store)
            _ (println "Starting embedded Cassandra Ring session store, using config"
                       config "...")
            ttl (:ttl config)
            read-q (prepare cassandra "SELECT data FROM ring.sessions WHERE key = ?;")
            write-q (prepare cassandra (str "UPDATE ring.sessions USING TTL " (* ttl 60 2)
                                            " SET data = ? WHERE key = ?;"))
            remove-q (prepare cassandra "DELETE FROM ring.sessions WHERE key = ?;")]
        (println "Embedded Cassandra Ring session store started.")
        (EmbeddedCassandraStore. (atom (ttl-cache-factory {} :ttl (* ttl 60000)))
                                 ttl cassandra read-q write-q remove-q)))))
