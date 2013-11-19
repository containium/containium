;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.cassandra.alia1
  "The Alia 1.0 implementation of the Cassandra system.")


(defrecord Alia [cluster]
  Cassandra
  (prepare [this query-str])
  (do-prepared [this prepared consistency args])
  (has-keyspace? [this name])
  (write-schema [this schema-str])

  Stoppable
  (stop [this]))


(def alia
  (reify Startable
    (start [_ systems]
      (let [config (get-config (require-system Config systems) :alia)]
        (Alia. (create-cluster config))))))
