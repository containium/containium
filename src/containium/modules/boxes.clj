;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.modules.boxes
  "Logic for starting and stopping boxes."
  (:require [boxure.core :refer (boxure) :as boxure]))


(defn- check-project
  [project]
  (->> (list (when-not (:containium project)
               "Missing :containium configuration in project.clj.")
             (when-not (-> project :containium :start)
               "Missing :start configuration in :containium of project.clj.")
             (when (and (-> project :containium :start)
                        (not (namespace (-> project :containium :start))))
               "Missing namespace for :start configuration in :containium of project.clj.")
             (when-not (-> project :containium :stop)
               "Missing :stop configuration in :containium of project.clj.")
             (when (and (-> project :containium :stop)
                        (not (namespace (-> project :containium :stop))))
               "Missing namespace for :stop configuration in :containium of project.clj.")
             (when (-> project :containium :ring)
               (when-not (-> project :containium :ring :handler)
                 "Missing :handler configuration in :ring of :containium of project.clj.")
               (when (and (-> project :containium :ring :handler)
                          (not (namespace (-> project :containium :ring :handler))))
                 "Missing namespace for :handler configuration in :ring of :containium of project.clj.")))
       (remove nil?)))


(defn start-box
  "The logic for starting a box. Returns the started box."
  [name file boxure-config systems]
  (println "Starting module" name "using file" file "...")
  (try
    (let [project (boxure/file-project file)]
      (if-let [errors (seq (check-project project))]
        (apply println "Could not start module" name "for the following reasons:\n  "
               (interpose "\n  " errors))
        (let [box (boxure boxure-config (.getClassLoader clojure.lang.RT) file)
              module-config (:containium project)
              start-fn @(boxure/eval box `(do (require '~(symbol (namespace (:start module-config))))
                                              ~(:start module-config)))]
          (if (instance? Throwable start-fn)
            (do (boxure/clean-and-stop box)
                (throw start-fn))
            (try
              (let [start-result (boxure/call-in-box box start-fn systems)]
                (println "Module" name "started.")
                (assoc box :start-result start-result))
              (catch Throwable ex
                (boxure/clean-and-stop box)
                (throw ex)))))))
    (catch Throwable ex
      (println "Exception while starting module" name ":" ex)
      (.printStackTrace ex))))


(defn stop-box
  [name box]
  (let [module-config (-> box :project :containium)]
    (println "Stopping module" name "...")
    (try
      (let [stop-fn @(boxure/eval box
                                  `(do (require '~(symbol (namespace (:stop module-config))))
                                       ~(:stop module-config)))]
        (boxure/call-in-box box stop-fn (:start-result box))
        :stopped)
      (catch Throwable ex
        (println "Exception while stopping module" name ":" ex)
        (.printStackTrace ex))
      (finally
        (boxure/clean-and-stop box)
        (println "Module" name "stopped.")))))
