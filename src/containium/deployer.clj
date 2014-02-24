;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.deployer
  (:require [containium.systems :refer (require-system)]
            [containium.systems.config :as config :refer (Config)]
            [containium.modules :as modules :refer (Manager)]
            [containium.deployer.watcher :as watcher]
            [clojure.java.io :refer (file as-file)]
            [clojure.core.async :as async :refer (<!)]
            [clojure.edn :as edn])
  (:import [containium.systems Startable Stoppable]
           [java.nio.file Path WatchService]
           [java.io File]))


;;; Public API for Deployer systems.

(defprotocol Deployer
  (bootstrap-modules [this]
    "Deploys all modules that should be started on Containium boot."))


;;; File system implementation.

;;---TODO Replace regexes with simpler/faster .endsWith calls?
(def ^:private descriptor-files-re #"[^\.]((?!\.(activate|status|deactivated)$).)+")
(def ^:private activate-files-re   #"[^\.].*\.activate")


(defn- fs-event-handler
  [manager dir kind ^Path path]
  (let [filename (.. path getFileName toString)]
    (cond
     ;; Creation or touch of .activate file.
     (and (#{:create :modify} kind) (re-matches activate-files-re filename))
     (do (.delete (file dir filename))
         (let [name (subs filename 0 (- (count filename) (count ".activate")))
               file (file dir name)]
           (if (.exists file)
             (try
               (println "Activating" name "by filesystem event.")
               (let [descriptor (-> (slurp file) (edn/read-string) (update-in [:file] as-file))]
                 (->> (modules/activate! manager name descriptor)
                      (async/remove< (constantly true))
                      ;;---TODO Improve above sink. Command-only messages now get lost. Problem is,
                      ;;        many messages are also send to the console channel...
                      ))
               (catch Exception ex
                 (println "Could not activate" name "-" ex)))
             (println "Could not find" (str file) "in deployments directory."))))
     ;; Deletion of descriptor file
     (and (= :delete kind) (re-matches descriptor-files-re filename))
     (try
       (println "Deactivating" filename "by filesystem event.")
       (->> (modules/deactivate! manager filename)
            (async/remove< (constantly true)))
       (catch Exception ex
         (println "Could not deactivate" filename "-" ex))))))


(defn- module-event-loop
  ([dir ] (module-event-loop dir (async/chan 10)))
  ([dir chan]
     (let [descriptors (atom {})]
       (async/go-loop []
         (when-let [msg (<! chan)]
           (let [name (:name msg)]
             (case (:type msg)
               :activate
               (do (when-let [descriptor (:data msg)]
                     (swap! descriptors assoc name (update-in descriptor [:file] str)))
                   (spit (file dir name) (get @descriptors name)))

               :deactivate
               (.delete (file dir name))

               :status
               (spit (file dir (str name ".status"))
                     (clojure.core/name (:data msg)))

               :kill
               (.delete (file dir name))

               nil))
           (recur))))
     chan))


(defrecord DirectoryDeployer [manager ^File dir watcher module-event-tap]
  Deployer
  (bootstrap-modules [_]
    (doseq [^File file (.listFiles dir)
            :let [name (.getName file)]
            :when (re-matches descriptor-files-re name)]
      (try
        (println "Filesystem deployer now bootstrapping module" (str file))
        (let [descriptor (-> (slurp file) (edn/read-string) (update-in [:file] as-file))]
          (->> (modules/activate! manager name descriptor)
               (async/remove< (constantly true))))
        (catch Exception ex
          (println "Could not activate" name "-" ex)))))

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
                module-event-tap (async/tap (modules/event-mult manager) (module-event-loop dir))]
            (println "Filesystem deployment watcher started.")
            (DirectoryDeployer. manager dir watcher module-event-tap)))))))
