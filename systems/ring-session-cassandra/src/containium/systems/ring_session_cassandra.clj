;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.ring-session-cassandra
  "This namespace contains the Containium system offering a Ring
  SessionStore backed by Cassandra and Nippy."
  (:require [containium.systems :refer (Startable require-system)]
            [containium.systems.config :refer (Config get-config)]
            [containium.systems.cassandra :refer (Cassandra prepare do-prepared
                                                           has-keyspace? write-schema
                                                           bytebuffer->bytes
                                                           bytes->bytebuffer)]
            [ring.middleware.session.store :refer (SessionStore read-session)]
            [taoensso.nippy :refer (freeze thaw)]
            [clojure.java.io :refer (resource)])
  (:import [java.util UUID]))


;;; SessionStore implementation based on a Cassandra system.

(def ^:private session-consistency :one)

(defn- read-session-data
  [cassandra read-query key]
  (when-let [result (first (do-prepared cassandra read-query
                                        {:consistency session-consistency} [key]))]
    (-> (get result "data")
        .slice
        bytebuffer->bytes
        thaw)))


(defn- write-session-data
  [cassandra write-query key data]
  (do-prepared cassandra write-query {:consistency session-consistency}
               [(bytes->bytebuffer (freeze data)) key]))


(defn- remove-session-data
  [cassandra remove-query key]
  (do-prepared cassandra remove-query {:consistency session-consistency} [key]))


(defrecord CassandraStore [ttl-mins cassandra read-q write-q remove-q]
  SessionStore
  (read-session [_ key]
    ;; If the key is non-nil, try Cassandra. If cassandra fails, or the key was nil, an empty
    ;; session is returned.
    (or (and key (read-session-data cassandra read-q key)) {}))

  (write-session [this key data]
    ;; Create a key, if necessary.
    ;; Update the data in the database, except if it already contains a ::last_db_write entry,
    ;; and the time it specifies is younger than the current time minus TTL,
    ;; and the session data has not changed within the handling of the request.
    (let [new-key (or key (str (UUID/randomUUID)))
          new-data (if-not (and (get data ::last-db-write)
                                (< (- (System/currentTimeMillis) (* ttl-mins 60000))
                                   (get data ::last-db-write))
                                (= data (read-session this key)))
                     (assoc data ::last-db-write (System/currentTimeMillis))
                     data)]
      (when-not (= data new-data)
        (write-session-data cassandra write-q new-key new-data))
      new-key))

  (delete-session [_ key]
    ;; Remove the session data from the database. Return nil, in
    ;; order to have the session cookie removed.
    (when key
      (remove-session-data cassandra remove-q key))
    nil))


(defn- ensure-schema
  [cassandra]
  (when-not (has-keyspace? cassandra "ring")
    (println "No `ring` keyspace detected; writing schema.")
    (write-schema cassandra (slurp (resource "cassandra-session-store.cql")))))


(def default
  (reify Startable
    (start [_ systems]
      (let [cassandra (:cassandra systems)
            _ (assert cassandra (str "Cassandra Ring session store requires a "
                                     "Cassandra system, under the :cassandra key."))
            config (get-config (require-system Config systems) :session-store)
            _ (println "Starting Cassandra Ring session store, using config"
                       config "...")
            _ (ensure-schema cassandra)
            ttl-mins (:ttl-mins config)
            _ (assert ttl-mins "Missing :ttl-mins config")
            read-q (prepare cassandra "SELECT data FROM ring.sessions WHERE key = ?;")
            ;; TTL in the database queure is twice as what is configured, as unchanged session
            ;; data is only written once in TTL minutes to the database.
            write-q (prepare cassandra (str "UPDATE ring.sessions USING TTL " (* 2 60 ttl-mins)
                                            " SET data = ? WHERE key = ?;"))
            remove-q (prepare cassandra "DELETE FROM ring.sessions WHERE key = ?;")]
        (println "Cassandra Ring session store started.")
        (CassandraStore. ttl-mins cassandra read-q write-q remove-q)))))
