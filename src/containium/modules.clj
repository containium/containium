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
            [clojure.edn :as edn]
            [clojure.core.async :as async :refer (<!!)])
  (:import [containium.systems Startable Stoppable]
           [java.io File]
           [java.net URL]
           [java.util Collections Map$Entry]
           [java.util.jar Manifest]))


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

  (activate! [this name descriptor] [this name descriptor channel]
    "Try to deploy, redeploy or swap the module under the specified
    name. If a descriptor is already known from a previous deploy
    action, it may be nil. Returns an async channel on which the
    response messages are put. Optionally, one can supply this
    channel. When the activation is complete, a Response record is put
    on the channel and then the channel is closed.")

  (deactivate! [this name] [this name channel]
    "Try to undeploy the module with the specified name. Returns an
    async channel on which the response messages are put. Optionally,
    one can supply this channel. When the deactivation is complete, a
    Response record is put on the channel and then the channel is
    closed.")

  (kill! [this name] [this name channel]
    "Kills a module, whatever it's status. Returns an async channel on
    which the response messages are put. Optionally, one can supply
    this channel. When the deactivation is complete, a Response record
    is put on the channel and then the channel is closed.")

  (event-mult [this]
    "Returns an async mult on which tapped channels receive module
    management events. Please untap or close taps when they won't be
    read from anymore.")

  (versions [this name]
    "Prints the *-Version MANIFEST entries it can find in the classpath
    of the module. The following keys are filtered out:
    Manifest-Version Implementation-Version Ant-Version
    Specification-Version Archiver-Version Bundle-Version"))


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
  [agent ^Exception exception]
  (println "Exception in module agent:")
  (.printStackTrace exception))


(defn- new-agent
  [{:keys [agents] :as manager} name]
  (let [agent (agent (Module. name :undeployed nil nil nil false)
                     :error-handler agent-error-handler)]
    (swap! agents assoc name agent)
    agent))


(defn fire-event
  ([manager type name]
     (fire-event manager type name nil))
  ([manager type name data]
     (async/put! (:event-chan manager) (Event. type name data))))


(defn- channel-logger
  [channel]
  (fn [& msgs] (async/put! channel (apply str (interpose " " msgs)))))


;;; Agent processing functions.

(defn- finish-action
  [{:keys [name error status] :as module} manager channel]
  (let [response (Response. (not error) status)]
    (async/put! channel response)
    (async/close! channel)
    (fire-event manager :finished name response)
    (assoc module :error false)))


(defn- update-status
  ([module manager channel success-state]
     (update-status module manager channel success-state nil))
  ([{:keys [name error] :as module} manager channel success-state error-state]
     (let [status (if error error-state success-state)]
       (async/put! channel (str "Module '" name "' status has changed to "
                                (clojure.core/name status) "."))
       (fire-event manager :status name status)
       (assoc module :status status))))


(defn clean-descriptor [descriptor name]
  (->
    (if (-> descriptor :containium :ring)
        (update-in descriptor [:containium :ring] clean-ring-conf)
       #_else descriptor)
    (assoc :name name)))


(defn- do-deploy
  [{:keys [name descriptor error] :as module} manager channel new-descriptor]
  (if-not error
    (let [log (channel-logger channel)]
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
                (log (str "Module '" name "' successfully deployed."))
                (assoc module :box box :descriptor descriptor :ring-name ring-name))
              ;; else if box failed to start.
              (throw (Exception. (str "Box " name " failed to start.")))))
          (catch Throwable ex
            (.printStackTrace ex)
            (log (str "Module '" name "' failed to deploy: " (.getMessage ex)))
            (assoc module :error true :descriptor descriptor)))
        (do (log (str "Module '" name "' is new to containium, initial descriptor required."))
            (assoc module :error true))))
    module))


(defn- do-undeploy
  ([module manager channel]
     (do-undeploy module manager channel nil))
  ([{:keys [name box ring-name error] :as module} manager channel old]
     (let [log (channel-logger channel)]
       (if-not error
         (if (do (when (-> (or (:box old) box) :project :containium :ring)
                   (remove-box (-> manager :systems :ring) (or (:ring-name old) ring-name) log))
                 (stop-box name (or (:box old) box) log))
           (do (log (str "Module '" name "' successfully undeployed."))
               module)
           (do (log (str "Module '" name "' failed to undeployed."))
               (assoc module :error true)))
         module))))


;;; Protocol implementation functions.

(defn- activate-action
  [manager name agent channel {:keys [descriptor] :as args}]
  (fire-event manager :activate name descriptor)
  (let [agent (or agent (new-agent manager name))]
    (case (:status @agent)
      :undeployed
      (do (async/put! channel (str "Planning deployment action for module '" name "'..."))
          (send-off agent update-status manager channel :deploying)
          (await agent)
          (send-off agent do-deploy manager channel descriptor)
          (send-off agent update-status manager channel :deployed :undeployed)
          (send-off agent finish-action manager channel))

      :deployed
      (if (-> @agent :box :descriptor :containium :swappable?)
        (let [before-swap-state @agent]
          (async/put! channel (str "Planning swap action for module '" name "'..."))
          (send-off agent update-status manager channel :swapping)
          (await agent)
          (send-off agent do-deploy manager channel descriptor)
          (send-off agent do-undeploy manager channel before-swap-state)
          (send-off agent update-status manager channel :deployed :deployed)
          (send-off agent finish-action manager channel))
        (do
          (async/put! channel (str "Planning redeploy action for module '" name "'..."))
          (send-off agent update-status manager channel :redeploying)
          (await agent)
          (send-off agent do-undeploy manager channel)
          (send-off agent do-deploy manager channel descriptor)
          (send-off agent update-status manager channel :deployed :undeployed)
          (send-off agent finish-action manager channel))))))


(defn- deactivate-action
  [manager name agent channel]
  (fire-event manager :deactivate name)
  (async/put! channel (str "Planning undeployment action for module '" name "'..."))
  (send-off agent update-status manager channel :undeploying)
  (await agent)
  (send-off agent do-undeploy manager channel)
  (send-off agent update-status manager channel :undeployed :undeployed)
  (send-off agent finish-action manager channel))


(defn- kill-action
  [{:keys [systems agents] :as manager} agent channel]
  (let [{:keys [box name status]} @agent
        log (channel-logger channel)]
    (fire-event manager :kill name)
    (log (str "Killing module '" name "'..."))
    (swap! agents dissoc name)
    (when box
      (when (-> box :project :containium :ring)
        (remove-box (:ring systems) (:ring-name name) log))
      (stop-box name box log))
    (log (str "Module '" name "' successfully killed."))
    (async/put! channel (Response. true nil))
    (async/close! channel)))


(defn- action
  ([command manager name channel]
     (action command manager name channel nil))
  ([command manager name channel args]
     (locking (.intern name)
       (let [agent (get @(:agents manager) name)
             status (when agent (:status @agent))]
         (cond (and (= command :activate) (or (nil? agent)
                                              (contains? #{:undeployed :deployed} status)))
               (activate-action manager name agent channel args)

               (and (= command :deactivate) (= status :deployed))
               (deactivate-action manager name agent channel)

               (and (= command :kill) agent)
               (async/thread (kill-action manager agent channel))

               agent
               (do (Thread/sleep 1000) ;; Minimize load, especially in deactivate-all.
                   (async/put! channel (str "Cannot " (clojure.core/name command) " module '" name
                                            "' while its " (clojure.core/name status)))
                   (async/put! channel (Response. false status))
                   (async/close! channel))

               :else
               (do (Thread/sleep 1000) ;; Minimize load, especially in deactivate-all.
                   (async/put! channel (str "No module named '" name "' is known. "
                                            "Activate it first."))
                   (async/put! channel (Response. false nil))
                   (async/close! channel)))))
     channel))


(defn- deactivate-all
  [manager names]
  (let [timeout (async/timeout 90000)   ;;---TODO Make this configurable?
        initial (into {} (for [name names] [(action :deactivate manager name (async/chan)) name]))]
    (<!! (async/go-loop [channel+names initial]
           (if-not (empty? channel+names)
             (let [[val channel] (async/alts! (conj (vec (keys channel+names)) timeout))
                   name (get channel+names channel)]
               (if (= channel timeout)
                 ;; Timeout, close all channels and return false.
                 (do (doseq [channel (keys channel+names)]
                       (async/close! channel))
                     false)
                 ;; Action channel message
                 (if (instance? containium.modules.Response val)
                   (if (= (:status val) :undeployed)
                     ;; Succesfully undeployed, remove this channel.
                     (recur (dissoc channel+names channel))
                     ;; Failed to undeploy, schedule another deactivate.
                     (recur (assoc (dissoc channel+names channel)
                              (action :deactivate manager name (async/chan)) name)))
                   ;; Ordinary message, just print it.
                   (do (println "Modules shutdown:" val)
                       (recur channel+names)))))
             ;; No more channels, which means all are undeployed.
             true)))))


(defn- versions*
  [module]
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
            (println (.. entry getKey toString) ":" (.getValue entry))))))
    (println "Module" (:name module) "not running.")))


(defrecord DefaultManager [config systems agents event-chan event-mult]
  Manager
  (list-installed [_]
    (keep (fn [[name agent]]
            (if-let [status (:status @agent)]
              {:name name :status status}))
          @agents))

  (activate! [this name descriptor]
    (action :activate this name (async/chan) {:descriptor descriptor}))

  (activate! [this name descriptor channel]
    (action :activate this name channel {:descriptor descriptor}))

  (deactivate! [this name]
    (action :deactivate this name (async/chan)))

  (deactivate! [this name channel]
    (action :deactivate this name channel))

  (kill! [this name]
    (action :kill this name (async/chan)))

  (kill! [this name channel]
    (action :kill this name channel))

  (event-mult [this]
    event-mult)

  (versions [_ name]
    (if-let [agent (@agents name)]
      (versions* @agent)
      (println "No module named" name "known.")))

  Stoppable
  (stop [this]
    (when-let [to-undeploy (keys (remove #(= :undeployed (:status (deref (val %)))) @agents))]
      (if (deactivate-all this to-undeploy)
        (println "All modules successfully undeployed on shutdown.")
        (let [not-deactivated (keys (remove #(= :undeployed (:status (deref (val %)))) @agents))]
          (println (str "Failed to undeploy some modules on shutdown ("
                        (apply str (interpose ", " not-deactivated))
                        ") within 90 seconds. Continuing shutdown.")))))))


;;; Constructor function.

(def default-manager
  (reify Startable
    (start [_ systems]
      (assert (:ring systems))
      (let [config (require-system Config systems)
            event-chan (async/chan)]
        (println "Started default Module manager.")
        (DefaultManager. config systems (atom {}) event-chan (async/mult event-chan))))))
