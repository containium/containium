;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.modules
  "Functions for managing the starting and stopping of modules."
  (:require [containium.exceptions :as ex]
            [containium.systems :refer (require-system)]
            [containium.systems.config :refer (Config get-config)]
            [containium.systems.ring :refer (Ring upstart-box remove-box clean-ring-conf)]
            [containium.modules.boxes :refer (start-box stop-box)]
            [containium.systems.logging :as logging
             :refer (SystemLogger refer-logging refer-command-logging)]
            [clojure.edn :as edn]
            [clojure.core.async :as async :refer (<!!)]
            [clojure.stacktrace :refer (print-cause-trace)])
  (:import [containium.systems Startable Stoppable]
           [java.io File]
           [java.net URL]
           [java.util Collections Map$Entry]
           [java.util.jar Manifest]))
(refer-logging)
(refer-command-logging)


;;; Public system definitions.

(defrecord Response [success? status])

;; Events are put on the event-mult. Current events are:
;;  :activate <name> descriptor-map
;;  :deactivate <name>
;;  :kill <name>
;;  :status <name> status-keyword
;;  :finished <name> Response-record
(defrecord Event [type name data])


(defprotocol Manager
  (list-installed [this]
    "Returns a sequence of maps with :name and :state entries for the
    currently installed modules.")

  (activate! [this name descriptor command-logger]
    "Try to deploy, redeploy or swap the module under the specified
    name. If a descriptor is already known from a previous deploy
    action, it may be nil. When the activation is complete, `done` is
    called on the command-logger.")

  (deactivate! [this name command-logger]
    "Try to undeploy the module with the specified name. When the
    deactivation is complete, `done` is called on the command-logger.")

  (kill! [this name] [this name channel]
    "Kills a module, whatever it's status. When the kill is complete,
    `done` is called on the command-logger.")

  (event-mult [this]
    "Returns an async mult on which tapped channels receive module
    management events. Please untap or close taps when they won't be
    read from anymore.")

  (versions [this name] [this name channel]
    "Prints the *-Version MANIFEST entries it can find in the classpath
    of the module. The following keys are filtered out:
    Manifest-Version Implementation-Version Ant-Version
    Specification-Version Archiver-Version Bundle-Version. Returns an
    async channel on which the response messages are put. Optionally,
    one can supply this channel. The channel is closed after the
    printing of the versions."))


(defn module-descriptor
  "This function returns a module descriptor map, which at minimum contains:

  {:file (File. \"/path/to/module\"), :profiles [:default]}

  When `file` is a module file, it's contents is merged into the descriptor map.
  In a later stage (i.e. start-box) the :name, :project and :profiles keys can be conj'd."
  [^File file]
  (assert file "Path to module, or module descriptor File required!")
  (let [descriptor-defaults {:file file, :profiles [:default]}]
    (assert (.exists file) (str file " does not exist."))
    (if (.isDirectory file)
      descriptor-defaults
      ;; else if not a directory.
      (if-let [module-map (try (let [data (edn/read-string {:readers *data-readers*} (slurp file))]
                                 (when (map? data) data))
                               (catch Throwable ex (ex/exit-when-fatal ex)))]
        (let [file-str (str (:file module-map))
              file (if-not (.startsWith file-str "/")
                     (File. (.getParent file) file-str)
                     (File. file-str))]
          (assert (.exists file) (str file " does not exist."))
          (merge descriptor-defaults module-map {:file file}))
        ;; else if not a module descriptor file.
        descriptor-defaults))))


;;; Default implementation.

(defrecord Module [name status descriptor box ring-name error])


(defn- agent-error-handler
  [logger agent ^Exception exception]
  (error logger "Exception in module agent:")
  (error logger (with-out-str (print-cause-trace exception))))


(defn- new-agent
  [{:keys [agents systems] :as manager} name]
  (let [agent (agent (Module. name :undeployed nil nil nil false)
                     :error-handler (partial agent-error-handler (:logging systems)))]
    (swap! agents assoc name agent)
    agent))


(defn fire-event
  ([manager type name]
     (fire-event manager type name nil))
  ([manager type name data]
     (async/put! (:event-chan manager) (Event. type name data))))


;;; Agent processing functions.

(defn- finish-action
  [{:keys [name error status] :as module} manager command-logger]
  (logging/done command-logger)
  (fire-event manager :finished name (Response. (not error) status))
  (assoc module :error false))


(defn- update-status
  ([module manager command-logger success-state]
     (update-status module manager command-logger success-state nil))
  ([{:keys [name error] :as module} manager command-logger success-state error-state]
     (let [status (if error error-state success-state)]
       (info-all command-logger "Module" name "status has changed to " (clojure.core/name status))
       (fire-event manager :status name status)
       (assoc module :status status))))


(defn clean-descriptor [descriptor name]
  (->
    (if (-> descriptor :containium :ring)
        (update-in descriptor [:containium :ring] clean-ring-conf)
       #_else descriptor)
    (assoc :name name)))


(defn- do-deploy
  [{:keys [name descriptor error] :as module} {:keys [systems] :as manager} command-logger
   new-descriptor]
  (if-not error
    (let [log (partial logging/log-all command-logger :info)]
      (if-let [descriptor (or new-descriptor descriptor)]
        (try
          (let [{:keys [containium profiles] :as descriptor} (clean-descriptor descriptor name)
                boxure-config (-> (get-config (-> manager :systems :config) :modules)
                                  (assoc :profiles profiles))]
            ;; Try to start the box.
            (if-let [box (start-box descriptor boxure-config (:systems manager) log)]
              (let [box (assoc-in box [:project :containium] (-> box :descriptor :containium))
                    ring-name (gensym name)]
                ;; Register it with ring, if applicable.
                (when (-> box :project :containium :ring)
                  (upstart-box (-> manager :systems :ring) ring-name box log))
                (info-all command-logger "Module" name "successfully deployed.")
                (assoc module :box box :descriptor descriptor :ring-name ring-name))
              ;; else if box failed to start.
              (throw (Exception. (str "Box " name " failed to start.")))))
          (catch Throwable ex
            (error (:logging systems) (with-out-str (print-cause-trace ex)))
            (error-all command-logger "Module" name "failed to deploy:" (.getMessage ex))
            (assoc module :error true :descriptor descriptor)))
        (do (error-command command-logger "Module" name
                           "is new to containium, initial descriptor required.")
            (assoc module :error true))))
    module))


(defn- do-undeploy
  ([module manager command-logger]
     (do-undeploy module manager command-logger nil))
  ([{:keys [name box ring-name error] :as module} manager channel old]
     (let [log (partial logging/log-all command-logger :info)]
       (if-not error
         (if (do (when (-> (or (:box old) box) :project :containium :ring)
                   (remove-box (-> manager :systems :ring) (or (:ring-name old) ring-name) log))
                 (stop-box name (or (:box old) box) log))
           (do (info-all command-logger "Module" name "successfully undeployed.")
               (if old module (dissoc module :box)))
           (do (error-all command-logger "Module" name "failed to undeploy.")
               (-> (if old module (dissoc module :box)) (assoc :error true))))
         module))))


;;; Protocol implementation functions.

(defn- activate-action
  [manager name agent command-logger {:keys [descriptor] :as args}]
  (fire-event manager :activate name descriptor)
  (let [agent (or agent (new-agent manager name))]
    (case (:status @agent)
      :undeployed
      (do (info-all command-logger "Planning deployment action for module" name "...")
          (send-off agent update-status manager command-logger :deploying)
          (await agent)
          (send-off agent do-deploy manager command-logger descriptor)
          (send-off agent update-status manager command-logger :deployed :undeployed)
          (send-off agent finish-action manager command-logger))

      :deployed
      (if (-> @agent :box :descriptor :containium :swappable?)
        (let [before-swap-state @agent]
          (info-all command-logger "Planning swap action for module" name "...")
          (send-off agent update-status manager command-logger :swapping)
          (await agent)
          (send-off agent do-deploy manager command-logger descriptor)
          (send-off agent do-undeploy manager command-logger before-swap-state)
          (send-off agent update-status manager command-logger :deployed :deployed)
          (send-off agent finish-action manager command-logger))
        (do
          (info-all command-logger "Planning redeploy action for module" name "...")
          (send-off agent update-status manager command-logger :redeploying)
          (await agent)
          (send-off agent do-undeploy manager command-logger)
          (send-off agent do-deploy manager command-logger descriptor)
          (send-off agent update-status manager command-logger :deployed :undeployed)
          (send-off agent finish-action manager command-logger))))))


(defn- deactivate-action
  [manager name agent command-logger]
  (fire-event manager :deactivate name)
  (info-all command-logger "Planning undeployment action for module" name "...")
  (send-off agent update-status manager command-logger :undeploying)
  (await agent)
  (send-off agent do-undeploy manager command-logger)
  (send-off agent update-status manager command-logger :undeployed :undeployed)
  (send-off agent finish-action manager command-logger))


(defn- kill-action
  [{:keys [systems agents] :as manager} agent command-logger]
  (let [{:keys [box name status]} @agent
        log (partial log-all command-logger :info)]
    (fire-event manager :kill name)
    (info-all command-logger "Killing module" name "...")
    (swap! agents dissoc name)
    (when box
      (when (-> box :project :containium :ring)
        (remove-box (:ring systems) (:ring-name name) log))
      (stop-box name box log))
    (info-all command-logger  "Module" name "successfully killed.")
    (logging/done command-logger)))


(defn- action
  ([command manager name command-logger]
     (action command manager name command-logger nil))
  ([command manager name command-logger args]
     (locking (.intern ^String name)
       (let [agent (get @(:agents manager) name)
             status (when agent (:status @agent))]
         (cond (and (= command :activate) (or (nil? agent)
                                              (contains? #{:undeployed :deployed} status)))
               (activate-action manager name agent command-logger args)

               (and (= command :deactivate) (= status :deployed))
               (deactivate-action manager name agent command-logger)

               (and (= command :kill) agent)
               (async/thread (kill-action manager agent command-logger))

               agent
               (do (Thread/sleep 1000) ;; Minimize load, especially in deactivate-all.
                   (error-command command-logger "Cannot" (clojure.core/name command) "module" name
                                  "while its" (clojure.core/name status))
                   (logging/done command-logger))

               :else
               (do (Thread/sleep 1000) ;; Minimize load, especially in deactivate-all.
                   (error-command command-logger "No module named" name "known.")
                   (logging/done command-logger)))))))


(defn- deactivate-all
  [manager names]
  (let [logger (-> manager :systems :logging)
        timeoutc (async/timeout 90000) ;;---TODO Make this configurable?
        eventsc (async/chan)]
    (async/tap (event-mult manager) eventsc)
    (doseq [name names] (deactivate! manager name))
    (<!! (async/go-loop [left (set names)]
           (if (empty? left)
             (info logger "Deactivated all modules.")
             (let [[val channel] (async/alts! eventc timeoutc)]
               (if (= channel timeoutc)
                 (warn logger "Deactivation of all modules timed out. The following modules could"
                       "not be undeployed:" (apply str (interpose ", " left)))
                 (let [{:keys [type name data] :as event} val]
                   (if (= type :finished)
                     (case (:status data)
                       :undeployed (recur (disj left name))
                       :deployed (do (deactivate! manager name) (recur (conj left name))))
                     (recur left))))))))))


(defn- versions*
  [module command-logger]
  (if-let [^ClassLoader box-cl (-> module :box :box-cl)]
    (doseq [^URL resource (Collections/list (.getResources box-cl "META-INF/MANIFEST.MF"))]
      (with-open [stream (.openStream resource)]
        (let [mf (Manifest. stream)
              version-entries (filter (fn [^Map$Entry e]
                                        (let [name (.. e getKey toString)]
                                          (and (.endsWith name "-Version")
                                               (not (#{"Manifest-Version"
                                                       "Implementation-Version"
                                                       "Ant-Version"
                                                       "Specification-Version"
                                                       "Archiver-Version"
                                                       "Bundle-Version"} name)))))
                                      (.entrySet (.getMainAttributes mf)))]
          (doseq [^Map$Entry entry version-entries]
            (info-command command-logger (.. entry getKey toString) ":" (.getValue entry))))))
    (error-command command-logger "Module" (:name module) "not running."))
  (logging/done command-logger))


(defrecord DefaultManager [config systems agents event-chan event-mult]
  Manager
  (list-installed [_]
    (keep (fn [[name agent]]
            (if-let [status (:status @agent)]
              {:name name :status status}))
          @agents))

  (activate! [this name descriptor command-logger]
    (action :activate this name command-logger {:descriptor descriptor}))

  (deactivate! [this name command-logger]
    (action :deactivate this name command-logger))

  (kill! [this name command-logger]
    (action :kill this name command-logger))

  (event-mult [this]
    event-mult)

  (versions [_ name command-logger]
    (if-let [agent (@agents name)]
      (versions* @agent command-logger)
      (do (error-command command-logger "No module named" name "known.")
          (logging/done command-logger))))

  Stoppable
  (stop [this]
    (when-let [to-undeploy (keys (remove #(= :undeployed (:status (deref (val %)))) @agents))]
      (deactivate-all this to-undeploy))))


;;; Constructor function.

(def default-manager
  (reify Startable
    (start [_ systems]
      (assert (:ring systems))
      (let [config (require-system Config systems)
            logger (require-system SystemLogger systems)
            event-chan (async/chan)]
        (info logger "Started default Module manager.")
        (DefaultManager. config systems (atom {}) event-chan (async/mult event-chan))))))
