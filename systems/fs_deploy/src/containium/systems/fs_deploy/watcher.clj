;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.fs-deploy.watcher
  "A simple filesystem watcher. It currently supports only one handler
  for all watchers, and paths cannot be unwatched."
  (:import [java.nio.file FileSystems StandardWatchEventKinds WatchService WatchKey WatchEvent
            ClosedWatchServiceException]
           [java.io File]
           [java.util.concurrent TimeUnit]))


(defn mk-watchservice
  []
  (-> (FileSystems/getDefault)
      .newWatchService))


(defn- unwrap-event
  [^WatchEvent event]
  (let [kind (condp = (.kind event)
               StandardWatchEventKinds/ENTRY_CREATE :create
               StandardWatchEventKinds/ENTRY_DELETE :delete
               StandardWatchEventKinds/ENTRY_MODIFY :modify)
        path (.context event)]
    [kind path]))


(let [handler (atom nil)]

  (defn set-handler!
    [f]
    (reset! handler f))

  (defn- handle-event
    [^WatchEvent event]
    (when @handler
      (apply @handler (unwrap-event event)))))



(defn run-watchservice
  [^WatchService watchservice]
  (letfn [(run []
            (if (try
                  (if-let [^WatchKey key (.poll watchservice 500 TimeUnit/MILLISECONDS)]
                    (do (doseq [event (.pollEvents key)]
                          (when-not (= (.kind event) StandardWatchEventKinds/OVERFLOW)
                            (handle-event event)))
                        (.reset key))
                    :continue)
                  (catch ClosedWatchServiceException cwse
                    (println "Stopping filesystem watcher."))
                  (catch Exception e
                    (println "Exception while polling for file system events:" e)
                    (println "Stopping watching. Maybe improve this.")))
              (recur)
              (println "Filesystem watcher stopped.")))]
    (doto (Thread. run "watchservice") .start))
  watchservice)


(defn watch
  [^WatchService watchservice ^File file]
  (.. file toPath (register watchservice
                            (into-array [StandardWatchEventKinds/ENTRY_CREATE
                                         StandardWatchEventKinds/ENTRY_DELETE
                                         StandardWatchEventKinds/ENTRY_MODIFY])))
  watchservice)
