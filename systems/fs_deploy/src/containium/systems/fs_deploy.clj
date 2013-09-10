;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.fs-deploy
  (:require [containium.systems :refer (->AppSystem)]
            [containium.systems.fs-deploy.watcher
             :refer (mk-watchservice run-watchservice watch set-handler!)])
  (:import [java.nio.file Path WatchService]
           [java.io File]))

;;; Event handling.

(defn handle-event
  [kind ^Path path]
  (println "Filesystem event -" kind "on" path))


;;; Containium system definitions.

(defn start
  [config systems]
  (println "Starting filesystem deployment watcher...")
  (assert (:deployments config) "Missing :deployments configuration for FS system.")
  (let [dir (File. (:deployments config))]
    (assert (.exists dir) ("The directory " dir " does not exist."))
    (assert (.isDirectory dir) (str "Path " dir " is not a directory."))
    (set-handler! handle-event)
    (let [ws (-> (mk-watchservice)
                 (watch dir)
                 (run-watchservice))]
      (println "Filesystem deployment watcher started.")
      ws)))


(defn stop
  [^WatchService watchservice]
  (println "Stopping filesystem deployment watcher...")
  (.close watchservice))


(def system (->AppSystem start stop nil))
