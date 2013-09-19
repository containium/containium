;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.deployer
  (:require [containium.systems :refer (require-system)]
            [containium.systems.config :refer (Config get-config)]
            [containium.modules :refer (Manager deploy! redeploy! undeploy!
                                                       register-notifier! unregister-notifier!)]
            [containium.deployer.watcher :refer (mk-watchservice watch close)]
            [clojure.java.io :refer (file)])
  (:import [containium.systems Startable Stoppable]
           [java.nio.file Path WatchService]
           [java.io File]))


;;; Public API for Deployer systems.

(defprotocol Deployer
  (bootstrap-modules [this]
    "Deploys all modules that should be started on Containium boot."))


;;; File system implementation.

(defn- handle-notification
  [dir kind [module-name]]
  (spit (file dir (str module-name ".status")) (name kind)))


(defn- handle-event
  [manager dir kind ^Path path]
  (let [file-name (.. path getFileName toString)
        timeout (* 1000 60)]
    (when-not (re-matches #".*\.status" file-name)
      (let [response (case kind
                       :create (deref (deploy! manager file-name (file dir (.toFile path)))
                                      timeout ::timeout)
                       :modify (deref (redeploy! manager file-name) timeout ::timeout)
                       :delete (deref (undeploy! manager file-name) timeout ::timeout))]
        (if (= ::timeout response)
          (println "Response for file system deployer action for" file-name "timed out."
                   "\nThe action may have failed or may still complete.")
          (println "File system deployer action for" file-name ":" (:message response)))))))


(defrecord DirectoryDeployer [manager ^File dir watcher]
  Deployer
  (bootstrap-modules [_]
    (assert false "Not implemented yet."))

  Stoppable
  (stop [_]
    (println "Stopping filesystem deployment watcher...")
    (close watcher)
    (unregister-notifier! manager "fs-deployer")
    (println "Filesystem deployment watcher stopped.")))


(def directory
  (reify Startable
    (start [_ systems]
      (let [config (get-config (require-system Config systems) :fs)
            manager (require-system Manager systems)]
        (println "Starting filesystem deployment watcher, using config" config "...")
        (assert (:deployments config) "Missing :deployments configuration for FS system.")
        (let [dir (file (:deployments config))]
          (assert (.exists dir) ("The directory " dir " does not exist."))
          (assert (.isDirectory dir) (str "Path " dir " is not a directory."))
          (register-notifier! manager "fs-deployer" (partial handle-notification dir))
          (let [watcher (-> (mk-watchservice (partial handle-event manager dir))
                            (watch dir))]
            (println "Filesystem deployment watcher started.")
            (DirectoryDeployer. manager dir watcher)))))))
