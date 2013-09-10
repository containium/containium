;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.fs-deploy
  (:require [containium.systems :refer (->AppSystem)]
            [containium.systems.fs-deploy.watcher
             :refer (mk-watchservice run-watchservice watch set-handler!)]
            [containium.modules :refer (deploy! undeploy! redeploy! register-notifier!)]
            [clojure.java.io :refer (file)])
  (:import [java.nio.file Path WatchService]
           [java.io File]))


(def ^:private deployments-dir (atom nil))


;;; Module notifications handling.

(defn- handle-notification
  [kind [module-name]]
  (spit (file @deployments-dir (str module-name ".status")) (name kind)))


;;; Filesystem event handling.

(defn- handle-create
  [^Path path]
  (prn @(deploy! (.. path getFileName toString)
                 (File. (.. path toAbsolutePath toString)))))


(defn handle-modify
  [^Path path]
  (prn @(redeploy! (.. path getFileName toString))))


(defn handle-delete
  [^Path path]
  (prn @(undeploy! (.. path getFileName toString))))


(defn- handle-event
  [kind ^Path path]
  (when-not (re-matches #".*\.status" (.. path getFileName toString))
    (case kind
      :create (handle-create path)
      :modify (handle-modify path)
      :delete (handle-delete path))))


;;; Containium system definitions.

(defn start
  [config systems]
  (println "Starting filesystem deployment watcher...")
  (assert (:deployments config) "Missing :deployments configuration for FS system.")
  (let [dir (File. (:deployments config))]
    (assert (.exists dir) ("The directory " dir " does not exist."))
    (assert (.isDirectory dir) (str "Path " dir " is not a directory."))
    (reset! deployments-dir dir)
    (set-handler! handle-event)
    (register-notifier! "fs-deployer" handle-notification)
    (let [ws (-> (mk-watchservice)
                 (watch dir)
                 (run-watchservice))]
      (println "Filesystem deployment watcher started.")
      ws)))


(defn stop
  [^WatchService watchservice]
  (println "Stopping filesystem deployment watcher...")
  (.close watchservice)
  (println "Filesystem deployment watcher stopped."))


(def system (->AppSystem start stop nil))
