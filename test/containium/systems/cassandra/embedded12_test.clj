;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.cassandra.embedded12-test
  (:require [clojure.test :refer :all]
            [containium.systems :refer (with-systems)]
            [containium.systems.config :as config]
            [containium.systems.cassandra :as api]
            [containium.systems.cassandra.embedded12 :as cassandra])
  (:import [java.net InetAddress]
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
                                    de DECIMAL, do DOUBLE, fl FLOAT, it INET, i INT, li LIST<INT>,
                                    ma MAP<TEXT, INT>, se SET<INT>, te TEXT, ti TIMESTAMP, uu UUID,
                                    tiuu TIMEUUID, vc VARCHAR, vi VARINT);")
      (let [update-pq (api/prepare cassandra "UPDATE test.types SET bi=?, bl=?, bo=?, de=?, do=?,
                                              fl=?, it=?, i=?, li=?, ma=?, se=?, te=?, ti=?, uu=?,
                                              tiuu=?, vc=?, vi=? WHERE key=?;")
            select-pq (api/prepare cassandra "SELECT * FROM test.types;")]
        (api/do-prepared cassandra update-pq
                         {:consistency :one
                          :values [1 (ByteBuffer/wrap (.getBytes "foo")) true 1.1 1.2 (Float. 1.3)
                                   (InetAddress/getByName "127.0.0.1") (Integer. 2) [3 4]
                                   {"bar" 5 "baz" 6} #{7 8} "text" (System/currentTimeMillis)
                                   (UUID/randomUUID) #uuid "FE2B4360-28C6-11E2-81C1-0800200C9A66"
                                   "varchar" 9 "keyz"]})
        (println (api/do-prepared cassandra select-pq {:consistency :one :keywordize? true}))))))
