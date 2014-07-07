;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.deployer.watcher
  "A simple filesystem watcher."
  (:require [containium.exceptions :as ex]
            [containium.systems.logging :as logging :refer (refer-logging)])
  (:import [java.nio.file FileSystems StandardWatchEventKinds WatchService WatchKey WatchEvent
            ClosedWatchServiceException]
           [java.io File]
           [java.util.concurrent TimeUnit]))
(refer-logging)


;;; The API of a watcher.

(defprotocol Watcher
  (set-handler [this f]
    "Register the handler function for this watcher. The function is
  expected to take two arguments - the type of the event (:create,
  :delete or :modify) and the Path of the event. The set-handler
  returns the watcher.")

  (watch [this file]
    "Start watching the specified File. This function returns the
  watcher.")

  (unwatch [this file]
    "Stop watching the specified File. This function returns the
  watcher.")

  (close [this]
    "Stop the watcher altogether and stop close its Thread. This Watcher
  instance cannot be reused."))


;;; An implementation using the java.nio.WatchService.

(defn- unwrap-event
  [^WatchEvent event]
  (let [kind (condp = (.kind event)
               StandardWatchEventKinds/ENTRY_CREATE :create
               StandardWatchEventKinds/ENTRY_DELETE :delete
               StandardWatchEventKinds/ENTRY_MODIFY :modify)
        path (.context event)]
    [kind path]))


(defrecord WatchServiceWatcher [^WatchService watchservice handler watch-keys]
  Watcher
  (set-handler [this f]
    (reset! handler f)
    this)
  (watch [this file]
    (let [watch-key (.. ^File file toPath
                        (register watchservice
                                  (into-array [StandardWatchEventKinds/ENTRY_CREATE
                                               StandardWatchEventKinds/ENTRY_DELETE
                                               StandardWatchEventKinds/ENTRY_MODIFY])))]
      (swap! watch-keys assoc (.getAbsolutePath ^File file) watch-key))
    this)
  (unwatch [this file]
    (when-let [^WatchKey watch-key (@watch-keys (.getAbsolutePath ^File file))]
      (.cancel watch-key)
      (swap! watch-keys dissoc (.getAbsolutePath ^File file)))
    this)
  (close [this]
    (.close watchservice)))


(defn mk-watchservice
  "Creates a Watcher using a java.nio.WatchService. It starts it in a
  new Thread. One can optionally supply a handler function (see
  `set-handler` in the Watcher protocol)."
  ([logger] (mk-watchservice logger nil))
  ([handler logger]
     (let [watchservice (-> (FileSystems/getDefault) .newWatchService)
           handler (atom handler)
           handler-fn (fn [event]
                        (when-let [handler @handler] (apply handler (unwrap-event event))))
           run (fn []
                 (if (try
                       (if-let [^WatchKey key (.poll watchservice 500 TimeUnit/MILLISECONDS)]
                         (do (doseq [^WatchEvent event (.pollEvents key)]
                               (when-not (= (.kind event) StandardWatchEventKinds/OVERFLOW)
                                 (future (handler-fn event))))
                             (.reset key))
                         :continue)
                       (catch ClosedWatchServiceException cwse
                         (info logger "Stopping filesystem watcher."))
                       (catch Throwable e
                         (ex/exit-when-fatal e)
                         (error logger "Exception while polling for file system events:" e)
                         (error logger "Stopping watching. Maybe improve this.")))
                   (recur)
                   (info logger "Filesystem watcher stopped.")))]
       (doto (Thread. run "watchservice") .start)
       (WatchServiceWatcher. watchservice handler (atom {})))))
