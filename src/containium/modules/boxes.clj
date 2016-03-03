;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.modules.boxes
  "Logic for starting and stopping boxes."
  (:require [boxure.core :refer (boxure) :as boxure]
            [leiningen.core.project]
            [clojure.core.async :as async]
            [containium.systems.logging :as logging :refer (refer-logging refer-command-logging)]
            [containium.exceptions :as ex]
            [ring.util.codec] ; load codec, because it is shared with boxes
            [clj-time.format] ; load format, because it is shared with boxes
            [postal.core]))
(refer-logging)
(refer-command-logging)

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


(defn- report-non-isolates
  [boxure-config logging]
  (let [isolated-re (->> (conj (:isolates boxure-config) boxure.BoxureClassLoader/ISOLATE)
                         (interpose "|")
                         (apply str)
                         (re-pattern))
        loaded (map str (all-ns))
        not-isolated (remove (comp (partial re-matches isolated-re) str) loaded)]
    (when (seq not-isolated)
      (warn logging "Some loaded namespaces are not isolated:"
            (apply str (interpose ", " not-isolated))))))


(defn start-box
  "The logic for starting a box. Returns the started box."
  [{:keys [name file] :as descriptor} boxure-config {:keys [logging] :as systems} command-logger]
  (info-all command-logger "Starting module" name "using file" file "...")
  (try
    (let [project (boxure/file-project file (:profiles descriptor))]
      (if-let [errors (seq (check-project project))]
        (error-all command-logger "Could not start module" name "for the following reasons:\n  "
                   (apply str (interpose "\n  " errors)))
        (let [module-config (meta-merge (:containium project) (:containium descriptor))
              boxure-config (if-let [module-isolates (:isolates module-config)]
                              (update-in boxure-config [:isolates] (partial apply conj) module-isolates)
                              #_else boxure-config)
              _ (report-non-isolates boxure-config logging)
              box-debug (System/getenv "BOXDEBUG")
              box (boxure (assoc boxure-config :debug? box-debug) (.getClassLoader clojure.lang.RT) file)
              injected (boxure/eval box '(let [injected (clojure.lang.Namespace/injectFromRoot
                                                          (str "containium\\.(?!core|utils).*"
                                                               "|clojure.(java|xml).*"
                                                               "|ring\\.middleware.*"
                                                               "|ring\\.util\\.codec.*"
                                                               "|org\\.httpkit.*"
                                                               "|clj.time.*"))]
                                            (dosync (commute @#'clojure.core/*loaded-libs*
                                                             #(apply conj % (keys injected))))
                                            injected))
              _ (trace logging "Loaded after namespace injection: "
                       (boxure/eval box @#'clojure.core/*loaded-libs*))
              active-profiles (-> (meta project) :active-profiles set)
              descriptor (merge {:dev? (not (nil? (active-profiles :dev)))} ; implicit defaults
                                descriptor ; descriptor overrides implicits
                                {:project project
                                 :active-profiles active-profiles
                                 :containium module-config})
              start-fn (boxure/eval box `(do (require '~(symbol (namespace (:start module-config))))
                                              ~(:start module-config)))]
          (when (instance? Throwable start-fn) (boxure/clean-and-stop box) (throw start-fn))
          (try
            (let [start-result (boxure/call-in-box box (start-fn systems descriptor))]
              (info-all command-logger "Module" name "started.")
              (assoc box :start-result start-result, :descriptor descriptor))
            (catch Throwable ex
              (ex/exit-when-fatal ex)
              (boxure/clean-and-stop box)
              (throw ex))))))
    (catch Throwable ex
      (ex/exit-when-fatal ex)
      (error-all command-logger "Exception while starting module" name ":" ex)
      (error logging ex))))


(defn stop-box
  [name box command-logger {:keys [logging] :as systems}]
  (let [module-config (-> box :project :containium)]
    (info-all command-logger "Stopping module" name "...")
    (try
      (let [stop-fn (boxure/eval box
                                  `(do (require '~(symbol (namespace (:stop module-config))))
                                       ~(:stop module-config)))]
        (boxure/call-in-box box (stop-fn (:start-result box)))
        :stopped)
      (catch Throwable ex
        (error-all command-logger "Exception while stopping module" name ":" ex)
        (ex/exit-when-fatal ex)
        (error ex))
      (finally
        (boxure.BoxureClassLoader/cleanThreadLocals)
        (boxure/clean-and-stop box)
        (info-all command-logger "Module" name "stopped.")))))
