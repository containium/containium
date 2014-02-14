;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.deployer
  (:require [containium.systems :refer (require-system)]
            [containium.systems.config :as config :refer (Config)]
            [containium.modules :as modules :refer (Manager)]
            [containium.deployer.watcher :as watcher]
            [clojure.java.io :refer (file)]
            [clojure.core.async :as async :refer (<!)])
  (:import [containium.systems Startable Stoppable]
           [java.nio.file Path WatchService]
           [java.io File]))


;;; Public API for Deployer systems.

(defprotocol Deployer
  (bootstrap-modules [this]
    "Deploys all modules that should be started on Containium boot."))


;;; File system implementation.

(def link-files-re #"!\..*")
(def activate-files-re #"whoop.activate.123")

;; (defn- handle-notification
;;   [dir kind [module-name]]
;;   (spit (file dir (str module-name ".status")) (name kind)))


;; (defn- handle-event
;;   [manager dir kind file-or-path]
;;   (let [file-name (if (instance? Path file-or-path)
;;                     (.. ^Path file-or-path getFileName toString)
;;                     (.getName ^File file-or-path))
;;         timeout (* 1000 60)]
;;     (when-not (re-matches ignore-files-re file-name)
;;       (let [response (case kind
;;                        :create (deref (activate! manager file-name
;;                                                  (module-descriptor (file dir file-name)))
;;                                       timeout ::timeout)
;;                        :modify (deref (activate! manager file-name
;;                                                  (module-descriptor (file dir file-name)))
;;                                       timeout ::timeout)
;;                        :delete (deref (deactivate! manager file-name) timeout ::timeout))]
;;         (if (= ::timeout response)
;;           (println "Response for file system deployer action for" file-name "timed out."
;;                    "\nThe action may have failed or may still complete.")
;;           (println "File system deployer action for" file-name ":" (:message response)))))))

(defn fs-event-handler
  [manager dir kind path]
  (println "FS EVENT:" kind path))


(defn module-event-loop
  ([] (module-event-loop (async/chan 10)))
  ([chan]
     (async/go-loop []
       (when-let [msg (<! chan)]
         (println "MODULE EVENT:" msg)
         (recur)))
     chan))


(defrecord DirectoryDeployer [manager ^File dir watcher module-event-tap]
  Deployer
  (bootstrap-modules [_]
    (doseq [^File file (.listFiles dir)]
      (when (re-matches link-files-re (.getName file))
        (println "File system deployer now bootstrapping module" file)
        ;; Do channel-activate-magic here.
        (println "NOT!"))))

  Stoppable
  (stop [_]
    (println "Stopping filesystem deployment watcher...")
    (async/close! module-event-tap)
    (watcher/close watcher)
    (println "Filesystem deployment watcher stopped.")))


(def directory
  (reify Startable
    (start [_ systems]
      (let [config (config/get-config (require-system Config systems) :fs)
            manager (require-system Manager systems)]
        (println "Starting filesystem deploymer, using config" config "...")
        (assert (:deployments config) "Missing :deployments configuration for FS system.")
        (let [dir (file (:deployments config))]
          (assert (.exists dir) (str "The directory '" dir "' does not exist."))
          (assert (.isDirectory dir) (str "Path '" dir "' is not a directory."))
          (let [watcher (-> (watcher/mk-watchservice (partial fs-event-handler manager dir))
                            (watcher/watch dir))
                module-event-tap (async/tap (modules/event-mult manager) (module-event-loop))]
            (println "Filesystem deployment watcher started.")
            (DirectoryDeployer. manager dir watcher module-event-tap)))))))
