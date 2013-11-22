;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.cassandra
  "The public API to Cassandra systems."
  (:refer-clojure :exclude [replace])
  (:require [clojure.java.io :refer (copy)]
            [clojure.string :refer (replace split)])
  (:import [containium ByteBufferInputStream]
           [java.io ByteArrayOutputStream]
           [java.nio ByteBuffer]
           [java.util Arrays]))


(defprotocol Cassandra
  (prepare [this query]
    "Prepares a CQL query. The returned object is not to be used
    directly, as it differs per implementation. It can be used for the
    `do-prepared` function.")

  (do-prepared [this statement opts values]
    "Executes a prepared query. The values argument is a sequence
    containing the arguments for the statement, or nil. The opts
    argument needs to be a map, with the following keys:

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
    "Returns an instance that is set to the given keyspace.")

  (write-schema [this schema-str]
    "Writes a CQL schema String to the database. Comments are filtered
    out automatically and the statements are executed in sequence. The
    return value is not defined, and is better ignored."))


;;; Helper functions.

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


(defn cql-statements
  "Returns the CQL statement Strings from the specified schema String
  in a sequence."
  [s]
  (let [no-comments (-> s
                        (replace #"(?s)/\*.*?\*/" "")
                        (replace #"--.*$" "")
                        (replace #"//.*$" ""))]
    (map #(str % ";") (split no-comments #"\s*;\s*"))))
