;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.cassandra.embedded12-test
  (:require [clojure.test :refer :all]
            [containium.systems :refer (with-systems)]
            [containium.systems.config :as config]
            [containium.systems.cassandra :as api]
            [containium.systems.cassandra.embedded12 :as cassandra])
  (:import [java.math BigInteger BigDecimal]
           [java.net InetAddress]
           [java.nio ByteBuffer]
           [java.util UUID]))


(deftest types
  (with-systems sys [:config (config/map-config {:cassandra {:config-file "cassandra.yaml"}})
                     :cassandra cassandra/embedded12]
    (let [cassandra (:cassandra sys)]
      (when (api/has-keyspace? cassandra "test")
        (api/write-schema cassandra "DROP KEYSPACE test;"))
      (api/write-schema cassandra "CREATE KEYSPACE test WITH
                                   replication = {'class': 'SimpleStrategy',
                                                  'replication_factor': 1};")
      (api/write-schema cassandra "CREATE TABLE test.types
                                   (key ASCII PRIMARY KEY, bi BIGINT, bl BLOB, bo BOOLEAN,
                                    de DECIMAL, do DOUBLE, fl FLOAT, it INET, i INT,
                                    li LIST<BIGINT>, ma MAP<TEXT, BIGINT>, se SET<BIGINT>, te TEXT,
                                    ti TIMESTAMP, uu UUID, tiuu TIMEUUID, vc VARCHAR, vi VARINT);")
      (let [update-pq (api/prepare cassandra "UPDATE test.types SET bi=?, bl=?, bo=?, de=?, do=?,
                                              fl=?, it=?, i=?, li=?, ma=?, se=?, te=?, ti=?, uu=?,
                                              tiuu=?, vc=?, vi=? WHERE key=?;")
            select-pq (api/prepare cassandra "SELECT * FROM test.types;")]
        (api/do-prepared cassandra update-pq
                         {:consistency :one
                          :values [1 (ByteBuffer/wrap (.getBytes "foo")) true (BigDecimal. "1.1")
                                   1.2 (Float. 1.3) (InetAddress/getByName "127.0.0.1") (Integer. 2)
                                   [3 4] {"bar" 5 "baz" 6} #{7 8} "text" (System/currentTimeMillis)
                                   (UUID/randomUUID) #uuid "FE2B4360-28C6-11E2-81C1-0800200C9A66"
                                   "varchar" (BigInteger. "9") "keyz"]})
        (let [result (first (api/do-prepared cassandra select-pq {:consistency :one
                                                                  :keywordize? true}))]
          (is (instance? java.net.Inet4Address (:it result)))
          (is (instance? java.lang.String (:vc result)))
          (is (instance? clojure.lang.PersistentArrayMap (:ma result)))
          (is (instance? java.lang.Float (:fl result)))
          (is (instance? java.lang.Long (:bi result)))
          (is (instance? java.lang.String (:key result)))
          (is (instance? java.lang.Double (:do result)))
          (is (instance? java.math.BigInteger (:vi result)))
          (is (instance? java.math.BigDecimal (:de result)))
          (is (instance? java.lang.Integer (:i result)))
          (is (instance? java.util.UUID (:tiuu result)))
          (is (instance? clojure.lang.PersistentHashSet (:se result)))
          (is (instance? java.nio.HeapByteBuffer (:bl result)))
          (is (instance? java.util.UUID (:uu result)))
          (is (instance? java.lang.Boolean (:bo result)))
          (is (instance? java.util.Date (:ti result)))
          (is (instance? java.lang.String (:te result)))
          (is (instance? clojure.lang.PersistentVector (:li result))))))))
