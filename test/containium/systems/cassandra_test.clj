;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.cassandra-test
  (:require [clojure.test :refer :all]
            [containium.systems :refer (with-systems)]
            [containium.systems.config :as config]
            [containium.systems.cassandra :as api]
            [containium.systems.cassandra.embedded12 :as embedded]
            [containium.systems.cassandra.alia1 :as alia])
  (:import [java.math BigInteger BigDecimal]
           [java.net InetAddress]
           [java.nio ByteBuffer]
           [java.util UUID Date]))


;;; Helper functions.

(defn instances?
  [c x & xs]
  (every? (partial instance? c) (conj xs x)))


(deftest types
  ;; Open both an embedded Cassandra instance, and an Alia instance connecting to the
  ;; embedded instance.
  (with-systems sys [:config (config/map-config {:cassandra {:config-file "cassandra.yaml"}
                                                 :alia {:contact-points ["localhost"]}})
                     :embedded embedded/embedded12
                     :alia (alia/alia1 :alia)]
    (let [embedded (:embedded sys)
          alia (:alia sys)]

      ;; Drop possibly existing test keyspace.
      (when (api/has-keyspace? embedded "test")
        (api/write-schema embedded "DROP KEYSPACE test;"))

      ;; Write part of schema using embedded.
      (api/write-schema embedded "CREATE KEYSPACE test WITH
                                   replication = {'class': 'SimpleStrategy',
                                                  'replication_factor': 1};")

      ;; Write part of schema using alia.
      (api/write-schema alia "CREATE TABLE test.types
                                   (key ASCII PRIMARY KEY, bi BIGINT, bl BLOB, bo BOOLEAN,
                                    de DECIMAL, do DOUBLE, fl FLOAT, it INET, i INT,
                                    li LIST<BIGINT>, ma MAP<TEXT, BIGINT>, se SET<BIGINT>, te TEXT,
                                    ti TIMESTAMP, uu UUID, tiuu TIMEUUID, vc VARCHAR, vi VARINT);")


      (let [update-cql "UPDATE test.types SET bi=?, bl=?, bo=?, de=?, do=?, fl=?, it=?, i=?, li=?,
                                              ma=?, se=?, te=?, ti=?, uu=?, tiuu=?, vc=?, vi=?
                                          WHERE key=?;"
            embed-update (api/prepare embedded update-cql)
            alia-update (api/prepare alia update-cql)
            mk-values (fn [] [1 (ByteBuffer/wrap (.getBytes "foo")) true (BigDecimal. "1.1")
                              1.2 (Float. 1.3) (InetAddress/getByName "127.0.0.1") (Integer. 2)
                              [3 4] {"bar" 5 "baz" 6} #{7 8} "text" (new Date)
                              (UUID/randomUUID) #uuid "FE2B4360-28C6-11E2-81C1-0800200C9A66"
                              "varchar" (BigInteger. "9") "key"])]

        ;; Test update using embedded.
        (api/do-prepared embedded embed-update {:consistency :one, :values (mk-values)})

        ;; Test update using alia.
        (api/do-prepared alia alia-update {:consistency :one, :values (mk-values)})

        ;; Retrieve data from both embedded and alia, and test the types.
        (let [select-cql "SELECT * FROM test.types;"
              embed-select (api/prepare embedded select-cql)
              alia-select (api/prepare alia select-cql)
              embed-result (first (api/do-prepared embedded embed-select
                                                   {:consistency :one, :keywordize? true}))
              alia-result (first (api/do-prepared embedded embed-select
                                                   {:consistency :one, :keywordize? true}))]
          (is (instances? java.net.Inet4Address (:it embed-result) (:it alia-result)))
          (is (instances? java.lang.String (:vc embed-result) (:vc alia-result)))
          (is (instances? clojure.lang.PersistentArrayMap (:ma embed-result) (:ma alia-result)))
          (is (instances? java.lang.Float (:fl embed-result) (:fl alia-result)))
          (is (instances? java.lang.Long (:bi embed-result) (:bi alia-result)))
          (is (instances? java.lang.String (:key embed-result) (:key alia-result)))
          (is (instances? java.lang.Double (:do embed-result) (:do alia-result)))
          (is (instances? java.math.BigInteger (:vi embed-result) (:vi alia-result)))
          (is (instances? java.math.BigDecimal (:de embed-result) (:de alia-result)))
          (is (instances? java.lang.Integer (:i embed-result) (:i alia-result)))
          (is (instances? java.util.UUID (:tiuu embed-result) (:tiuu alia-result)))
          (is (instances? clojure.lang.PersistentHashSet (:se embed-result) (:se alia-result)))
          (is (instances? java.nio.HeapByteBuffer (:bl embed-result) (:bl alia-result)))
          (is (instances? java.util.UUID (:uu embed-result) (:uu alia-result)))
          (is (instances? java.lang.Boolean (:bo embed-result) (:bo alia-result)))
          (is (instances? java.util.Date (:ti embed-result) (:ti alia-result)))
          (is (instances? java.lang.String (:te embed-result) (:te alia-result)))
          (is (instances? clojure.lang.IteratorSeq (:li embed-result) (:li alia-result))))))))
