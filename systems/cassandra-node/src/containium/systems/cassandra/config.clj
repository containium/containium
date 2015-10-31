;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.cassandra.config
  (:refer-clojure :exclude [munge])
  (:require [clojure.java.io :as io])
  (:import [org.apache.cassandra.config Config Config$CommitLogSync Config$DiskFailurePolicy
            ParameterizedClass]
           [java.util LinkedHashMap]
           [java.net URL])
  (:gen-class :extends org.apache.cassandra.config.YamlConfigurationLoader
              :exposes-methods {loadConfig superLoadConfig}))


(defonce system-config (promise))
(defonce logger (promise))

(defn munge
  [key]
  (assert (or (string? key) (keyword? key)))
  (if (keyword? key) (clojure.lang.Compiler/munge (name key)) key))


(defmulti override
  (fn [^Config config name value] name))

(defmethod override :default
  [^Config config name value]
  (.set (.getDeclaredField Config name) config value))

(defmethod override "commitlog_sync"
  [^Config config _ value]
  (set! (.commitlog_sync config)
        (case (munge value)
          "periodic" Config$CommitLogSync/periodic
          "batch" Config$CommitLogSync/batch)))

(defmethod override "data_file_directories"
  [^Config config _ value]
  (set! (.data_file_directories config) (into-array value)))

(defmethod override "disk_failure_policy"
  [^Config config _ value]
  (set! (.disk_failure_policy config)
        (case (munge value)
          "best_effort" Config$DiskFailurePolicy/best_effort
          "stop" Config$DiskFailurePolicy/stop
          "ignore" Config$DiskFailurePolicy/ignore
          "stop_paranoid" Config$DiskFailurePolicy/stop_paranoid)))

(defmethod override "seed_provider"
  [^Config config _ value]
  (set! (.seed_provider config)
        (-> {"class_name" (:class-name value (value "class_name"))
             "parameters" (:parameters value (value "parameters"))}
            LinkedHashMap.
            ParameterizedClass.)))


(defn -loadConfig
  [this]
  (assert (realized? logger)
          "Logger not realized; config loaded before containium was able to set it.")
  (assert (realized? system-config)
          "Configuration not realized; config loaded before containium was able to set it.")
  (println "[INFO] Constructing Cassandra config from:" (pr-str @system-config))
  (let [config (if-let [url (:config-file @system-config)]
                 (.superLoadConfig this ^URL (io/resource url))
                 (Config.))]
    (when-let [triggers-dir (:triggers-dir @system-config)]
      (System/setProperty "cassandra.triggers_dir", (str triggers-dir)))
    (doseq [[k v] (dissoc @system-config :config-file :triggers-dir)]
      (override config (munge k) v))
    config))
