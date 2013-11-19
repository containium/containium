;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.cassandra
  "The public API to Cassandra systems.")


(defprotocol Cassandra
  (prepare [this query]
    "Prepares a CQL query. The returned object is not to be used
    directly, as it differs per implementation. It can be used for the
    `do-prepared` function.")

  (do-prepared [this prepared consistency args]
    "Executer a prepared query. A result-map is returned. The arguments
    needs to be a sequence of arguments, or nil. The consistency is
    one of :any, :one, :two, :three, :quorum, :all, :local-quorum or
    :each-quorum.")

  (has-keyspace? [this name]
    "Returns a boolean indicating whether the named keyspace exists.")

  (write-schema [this schema-str]
    "Writes a CQL schema String to the database. Comments are filtered
    out automatically and the statements are executed in sequence. The
    return value is not specified, and is better ignored."))
