;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.modules.boxes
  "Logic for starting and stopping boxes."
  (:require [boxure.core :refer (boxure) :as boxure]
            [leiningen.core.project]
            [clojure.core.async :as async]
            [containium.exceptions :as ex]))

(def meta-merge #'leiningen.core.project/meta-merge)

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
  [{:keys [name file] :as descriptor} boxure-config systems log-fn]
  (log-fn "Starting module" name "using file" file "...")
  (try
    (let [project (boxure/file-project file (:profiles descriptor))]
      (if-let [errors (seq (check-project project))]
        (apply log-fn "Could not start module" name "for the following reasons:\n  "
               (interpose "\n  " errors))
        (let [module-config (meta-merge (:containium project) (:containium descriptor))
              boxure-config (if-let [module-isolates (:isolates module-config)]
                              (update-in boxure-config [:isolates] (partial apply conj) module-isolates)
                              #_else boxure-config)
              box (boxure (assoc boxure-config :debug? false) (.getClassLoader clojure.lang.RT) file)
              active-profiles (-> (meta project) :active-profiles set)
              descriptor (merge {:dev? (not (nil? (active-profiles :dev)))} ; implicit defaults
                                descriptor ; descriptor overrides implicits
                                {:project project
                                 :active-profiles active-profiles
                                 :containium module-config})
              start-fn @(boxure/eval box `(do (require '~(symbol (namespace (:start module-config))))
                                              ~(:start module-config)))
              forward-fn @(boxure/eval box '(do (require 'containium.systems)
                                                containium.systems/forward-systems))]
          (when (instance? Throwable start-fn) (boxure/clean-and-stop box) (throw start-fn))
          (when (instance? Throwable forward-fn) (boxure/clean-and-stop box) (throw forward-fn))
          (try
            (boxure/call-in-box box forward-fn systems)
            (let [start-result (boxure/call-in-box box start-fn systems descriptor)]
              (println "Module" name "started.")
              (assoc box :start-result start-result, :descriptor descriptor))
            (catch Throwable ex
              (ex/exit-when-fatal ex)
              (boxure/clean-and-stop box)
              (throw ex))))))
    (catch Throwable ex
      (ex/exit-when-fatal ex)
      (println "Exception while starting module" name ":" ex)
      (.printStackTrace ex))))


(defn stop-box
  [name box log-fn]
  (let [module-config (-> box :project :containium)]
    (log-fn "Stopping module" name "...")
    (try
      (let [stop-fn @(boxure/eval box
                                  `(do (require '~(symbol (namespace (:stop module-config))))
                                       ~(:stop module-config)))]
        (boxure/call-in-box box stop-fn (:start-result box))
        :stopped)
      (catch Throwable ex
        (log-fn "Exception while stopping module" name ":" ex)
        (ex/exit-when-fatal ex)
        (.printStackTrace ex))
      (finally
        (boxure/clean-and-stop box)
        (log-fn "Module" name "stopped.")))))
