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

  (do-prepared [this statement args]
    "Executes a prepared query. The args argument needs to be a map,
    with the following keys:

    :values - A vector containing the arguments for the statement.
                   This key is optional when the statement does not
                   need any values.

    :consistency - The value is one of :any, :one, :two, :three,
                   :quorum, :all, :local-quorum or :each-quorum. This
                   key is optional when the implementation's
                   *consistency* dynamic variable is bound.

    :raw? - When set to true, the implementation specific result is
                   returned. Otherwise, a Clojuresque sequence of maps
                   is returned. Default is false.

    :keywordize? - When set to true, the column names are keywordized.
                   Default is false. One can also bind the
                   implementation's *keywordize* dynamic variable.
                   This option is ignored when :raw? is set to true.")

  (has-keyspace? [this name]
    "Returns a boolean indicating whether the named keyspace exists.")

  (keyspaced [this name]
    "Returns a new instance that is set to the given keyspace.")

  (write-schema [this schema-str]
    "Writes a CQL schema String to the database. Comments are filtered
    out automatically and the statements are executed in sequence. The
    return value is not defined, and is better ignored."))
