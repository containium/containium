;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.deployer
  (:require [containium.systems :refer (require-system)]
            [containium.systems.config :as config :refer (Config)]
            [containium.modules :as modules :refer (Manager)]
            [containium.systems.logging :as logging
             :refer (SystemLogger refer-logging refer-command-logging)]
            [containium.deployer.watcher :as watcher]
            [clojure.java.io :refer (file as-file)]
            [clojure.core.async :as async :refer (<!)]
            [clojure.edn :as edn])
  (:import [containium.systems Startable Stoppable]
           [java.nio.file Path WatchService]
           [java.io File]))
(refer-logging)
(refer-command-logging)


;;; Public API for Deployer systems.

(defprotocol Deployer
  (bootstrap-modules [this]
    "Deploys all modules that should be started on Containium boot."))


;;; File system implementation.

;;---TODO Replace regexes with simpler/faster .endsWith calls?
(def ^:private descriptor-files-re #"[^\.]((?!\.(activate|status|deactivated)$).)+")
(def ^:private activate-files-re   #"[^\.].*\.activate")


(defn- fs-event-handler
  [manager logger dir kind ^Path path]
  (let [filename (.. path getFileName toString)]
    (cond
     ;; Creation or touch of .activate file.
     (and (#{:create :modify} kind) (re-matches activate-files-re filename))
     (do (.delete (file dir filename))
         (let [name (subs filename 0 (- (count filename) (count ".activate")))
               file (file dir name)]
           (if (.exists file)
             (try
               (info logger "Activating" name "by filesystem event.")
               (let [descriptor (-> (slurp file) (edn/read-string) (update-in [:file] as-file))
                     command-logger (logging/stdout-command-logger logger "activate")]
                 (modules/activate! manager name descriptor command-logger))
               (catch Exception ex
                 (error logger "Could not activate" name "-" ex)))
             (error logger "Could not find" (str file) "in deployments directory."))))
     ;; Deletion of descriptor file
     (and (= :delete kind) (re-matches descriptor-files-re filename))
     (try
       (info logger "Deactivating" filename "by filesystem event.")
       (modules/deactivate! manager filename (logging/stdout-command-logger logger "deactivate"))
       (catch Exception ex
         (error logger "Could not deactivate" filename "-" ex))))))


(defn- module-event-loop
  ([dir ] (module-event-loop dir (async/chan 10)))
  ([dir chan]
     (let [descriptors (atom {})]
       (async/go-loop []
         (when-let [msg (<! chan)]
           (let [name (:name msg)]
             (case (:type msg)
               :activate
               (when-let [descriptor (:data msg)]
                 (swap! descriptors assoc name (update-in descriptor [:file] str)))

               :deactivate
               (loop [postfix nil]
                 (when (.exists (file dir name))
                   (when-not (.renameTo (file dir name)
                                        (file dir (str name
                                                       (when postfix (str "-" postfix))
                                                       ".deactivated")))
                     (recur (inc (or postfix 0))))))

               :status
               (spit (file dir (str name ".status"))
                     (clojure.core/name (:data msg)))

               :kill
               (loop [postfix nil]
                 (when (.exists (file dir name))
                   (when-not (.renameTo (file dir name)
                                        (file dir (str name
                                                       (when postfix (str "-" postfix))
                                                       ".deactivated")))
                     (recur (inc (or postfix 0))))))

               :finished
               (let [response (:data msg)]
                 (when (and (= :deployed (:status response)) (:success? response))
                   (spit (file dir name) (get @descriptors name))))))
           (recur))))
     chan))


(defrecord DirectoryDeployer [manager logger ^File dir watcher module-event-tap]
  Deployer
  (bootstrap-modules [_]
    (doseq [^File file (.listFiles dir)
            :let [name (.getName file)]
            :when (re-matches descriptor-files-re name)]
      (try
        (info logger "Filesystem deployer now bootstrapping module" (str file))
        (let [descriptor (-> (slurp file) (edn/read-string) (update-in [:file] as-file))
              command-logger (logging/stdout-command-logger logger "bootstrap")]
          (modules/activate! manager name descriptor command-logger))
        (catch Exception ex
          (error logger "Could not activate" name "-" ex)))))

  Stoppable
  (stop [_]
    (info logger "Stopping filesystem deployment watcher...")
    (async/close! module-event-tap)
    (watcher/close watcher)
    (info logger "Filesystem deployment watcher stopped.")))


(def directory
  (reify Startable
    (start [_ systems]
      (let [config (config/get-config (require-system Config systems) :fs)
            manager (require-system Manager systems)
            logger (require-system SystemLogger systems)]
        (info logger "Starting filesystem deployer, using config" config "...")
        (assert (:deployments config) "Missing :deployments configuration for FS system.")
        (let [dir (file (:deployments config))]
          (assert (.exists dir) (str "The directory '" dir "' does not exist."))
          (assert (.isDirectory dir) (str "Path '" dir "' is not a directory."))
          (let [watcher (-> (partial fs-event-handler manager logger dir)
                            (watcher/mk-watchservice logger)
                            (watcher/watch dir))
                module-event-tap (async/tap (modules/event-mult manager) (module-event-loop dir))]
            (info logger "Filesystem deployment watcher started.")
            (DirectoryDeployer. manager logger dir watcher module-event-tap)))))))
