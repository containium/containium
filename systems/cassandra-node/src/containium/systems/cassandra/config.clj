;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.cassandra.config
  (:import [org.apache.cassandra.config Config Config$CommitLogSync Config$DiskFailurePolicy SeedProviderDef])
  (:gen-class
  	:implements [org.apache.cassandra.config.ConfigurationLoader]))

(def ^:dynamic *system-config* nil)

(defn -loadConfig [this]
  ;(if-not *compile-files* (assert *system-config*))
  (println "  Constructing Cassandra config from: " (pr-str *system-config*))
  (let [c*conf (Config.)]
    ; Default values from cassandra.yaml, set in order of compile error occurence
    (set! (.commitlog_sync              c*conf) Config$CommitLogSync/periodic)
    (set! (.commitlog_sync_period_in_ms c*conf) (int 10000))
    (set! (.partitioner                 c*conf) "org.apache.cassandra.dht.Murmur3Partitioner")
    (set! (.data_file_directories       c*conf) (into-array ["target/test-cassandra/data"]))
    (set! (.endpoint_snitch             c*conf) "SimpleSnitch")
    (set! (.commitlog_directory         c*conf) "target/test-cassandra/commitlog")
    (set! (.saved_caches_directory      c*conf) "target/test-cassandra/saved_caches")
    (set! (.seed_provider               c*conf) (SeedProviderDef. (java.util.LinkedHashMap. {"class_name" "org.apache.cassandra.locator.SimpleSeedProvider", "parameters" [{"seeds" "127.0.0.1"}]})))

    ; Additional defaults copied from YAML
    (set! (.disk_failure_policy         c*conf) Config$DiskFailurePolicy/stop)
    (set! (.num_tokens                  c*conf) (int 256))
    (set! (.max_hint_window_in_ms       c*conf) (int 10800000)) ; 3 hours
    (set! (.max_hints_delivery_threads  c*conf) (int 2))
    (set! (.start_native_transport      c*conf) true)

    (doseq [[k v] *system-config*]
      (.set (.getDeclaredField Config (if (keyword? k) (clojure.lang.Compiler/munge (name k)) (str k))) c*conf v))
    c*conf))
