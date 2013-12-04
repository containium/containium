;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.modules
  "Functions for managing the starting and stopping of modules."
  (:require [containium.systems :refer (require-system)]
            [containium.systems.config :refer (Config get-config)]
            [containium.systems.ring :refer (Ring upstart-box remove-box)]
            [containium.modules.boxes :refer (start-box stop-box)]
            [clojure.edn :as edn])
  (:import [containium.systems Startable Stoppable]
           [java.io File]))


;;; Public system definitions.

(defprotocol Manager
  (list-modules [this]
    "Returns a sequence of maps with at least :name, :state and
    :descriptor entries for the currently known modules.")

  (activate! [this name] [this name descriptor]
    "Try to deploy, redeploy or swap the module under the specified
    name. A promise is returned, which will eventually hold a Response
    record. When the activation means a deployment of a previously
    unknown module, the descriptor argument is required. Otherwise, it
    is optional.")

  (deactivate! [this name]
    "Try to undeploy the module with the specified name. A promise is
    returned, which will eventually hold a Response record.")

  (kill! [this name]
    "Kills a module, whatever it's state.")

  (register-notifier! [this name f]
    "Register a notifier function by name, which is called on events
    happening regarding the modules. The function takes two arguments. The
    first is the kind of event, and the second is a sequence of extra data
    for that event. The following events are send:

    kind         | data
    ------------------------------------------
    :deploying   | [module-name descriptor]
    :deployed    | [module-name descriptor]
    :undeploying | [module-name descriptor]
    :undeployed  | [module-name descriptor]
    :redeploying | [module-name descriptor]
    :failed      | [module-name descriptor]")

  (unregister-notifier! [this name]
    "Remove a notifier by name."))


(defrecord Response [success message])


(defn module-descriptor
  "This function returns a module descriptor map, which at minimum contains:

  {:file (File. \"/path/to/module\"), :profiles [:default]}

  When `file` is a module file, it's contents is merged into the descriptor map.
  In a later stage (i.e. start-box) the :name, :project and :profiles keys can be conj'd."
  [^File file]
  (assert file "Path to module, or module descriptor File required!")
  (let [descriptor-defaults {:file file :profiles [:default]}]
    (if (.isDirectory file)
      descriptor-defaults
      ;; else if not a directory.
      (if (.exists file)
        (if-let [module-map (try (edn/read-string (slurp file)) (catch Throwable ex))]
          (let [file-str (str (:file module-map))
                file (File. file file-str)]
            (when-not (.exists file) (throw (IllegalArgumentException. (str file " does not exist."))))
            (merge descriptor-defaults module-map {:file file}))
          ;; else if not a module descriptor file.
          descriptor-defaults)
        (throw (IllegalArgumentException. (str file " does not exist.")))))))


;;; Default implementation.

(defrecord Module [name state descriptor box ring-name report])


;;---FIXME: Make sure notify works again.

(defn- notify
  [manager type & args]
  (doseq [f (vals @(:notifiers manager))]
    (f type args)))


(defn- invalid-state
  [{:keys [name] :as module} promise occupation-str]
  (deliver promise (Response. false (str "Module " name " is " occupation-str ".")))
  module)


(defn- agent-error-handler
  [agent ^Exception exception]
  (println "Exception in module agent:")
  (.printStackTrace exception))


;;; Agent processing functions.

(defn- update-state
  [{:keys [report] :as module} manager success-state error-state]
  (assoc module :state (if (:error report) error-state success-state)))


(defn- report-state
  [{:keys [report state] :as module} manager promise]
  (deliver promise (Response. (boolean (:success report))
                              (str (or (:success report) (:error report))
                                   " (new state is " state ")")))

  (assoc module :report nil))


(defn- do-deploy
  [{:keys [name descriptor report] :as module} manager new-descriptor]
  (if-not (:error report)
    (if-let [descriptor (or new-descriptor descriptor)]
      (try
        (let [{:keys [containium profiles] :as descriptor} (assoc descriptor :name name)
              boxure-config (-> (get-config (-> manager :systems :config) :modules)
                                (assoc :profiles profiles))]
          ;; Try to start the box.
          (if-let [box (start-box descriptor boxure-config (:systems manager))]
            (let [box (assoc-in box [:project :containium] (-> box :descriptor :containium))
                  ring-name (gensym name)]
              ;; Register it with ring, if applicable.
              (when (-> box :project :containium :ring)
                (upstart-box (-> manager :systems :ring) ring-name box))
              (assoc module
                :box box
                :descriptor descriptor
                :ring-name ring-name
                :report {:success (str "Module " name " successfully deployed.")}))
            ;; else if box failed to start.
            (throw (Exception. (str "Box " name " failed to start.")))))
        (catch Throwable ex
          (println ex)
          (.printStackTrace ex)
          (assoc module
            :report {:error (str "Error while deploying module " name ".\n" (.getMessage ex))}
            :descriptor descriptor)))
      (assoc module :report {:error (str "Module " name
                                         " is new for containium, initial descriptor required.")}))
    module))


(defn- do-undeploy
  ([module manager]
     (do-undeploy module manager nil))
  ([{:keys [name box ring-name report] :as module} manager old]
     (if-not (:error report)
       (if (do (when (-> (or (:box old) box) :project :containium :ring)
                 (remove-box (-> manager :systems :ring) (or (:ring-name old) ring-name)))
               (stop-box name (or (:box old) box)))
         (assoc module :report {:success (str "Module " name " successfully undeployed.")})
         (assoc module :report {:error (str "Error while undeploying module " name ".")}))
       module)))


;;; Protocol implementation functions.

(defn- list-modules*
  [{:keys [agents] :as manager}]
  (map (fn [[name agent]] (assoc @agent :name name)) @agents))


(defn- activate
  [{:keys [agents] :as manager} name descriptor]
  (if-let [agent (get @agents name)]
    (locking agent
      (let [promise (promise)]
        (case (:state @agent)
          :undeployed (do (send-off agent assoc :state :deploying)
                          (send-off agent do-deploy manager descriptor)
                          (send-off agent update-state manager :deployed :undeployed)
                          (send-off agent report-state manager promise))
          :deploying (invalid-state @agent promise "already deploying")
          :redeploying (invalid-state @agent promise "already redeploying")
          :swapping (invalid-state @agent promise "already swapping")
          :deployed (if (-> @agent :descriptor :swappable?)
                      (let [before-swap-state @agent]
                        (do (send-off agent assoc :state :swapping)
                            (send-off agent do-deploy manager descriptor)
                            (send-off agent do-undeploy manager before-swap-state)
                            (send-off agent update-state manager :deployed :deployed)
                            (send-off agent report-state manager promise)))
                      (do (send-off agent assoc :state :redeploying)
                          (send-off agent do-undeploy manager)
                          (send-off agent do-deploy manager descriptor)
                          (send-off agent update-state manager :deployed :undeployed)
                          (send-off agent report-state manager promise)))
          :undeploying (invalid-state @agent promise "currently undeploying"))
        promise))
    (locking agents
      (if-not (get @agents name)
        (let [agent (agent (Module. name :undeployed descriptor nil nil nil)
                           :error-handler agent-error-handler)
              promise (promise)]
          (swap! agents #(assoc % name agent))
          (send-off agent assoc :state :deploying)
          (send-off agent do-deploy manager descriptor)
          (send-off agent update-state manager :deployed :undeployed)
          (send-off agent report-state manager promise)
          promise)
        (activate manager name descriptor)))))


(defn- deactivate
  [{:keys [agents] :as manager} name]
  (let [promise (promise)]
    (if-let [agent (get @agents name)]
      (locking agent
        (case (:state @agent)
          :undeployed (invalid-state @agent promise "already undeployed")
          :deploying (invalid-state @agent promise "currently deploying")
          :redeploying (invalid-state @agent promise "currently redeploying")
          :swapping (invalid-state @agent promise "currently swapping")
          :deployed (do (send-off agent assoc :state :undeploying)
                        (send-off agent do-undeploy manager)
                        (send-off agent update-state manager :undeployed :undeployed)
                        (send-off agent report-state manager promise))
          :undeploying (invalid-state @agent promise "already undeploying")))
      (deliver promise (Response. false (str "Module " name " unknown."))))
    promise))


(defn- kill
  [{:keys [agents systems] :as manager} name]
  (let [promise (promise)]
    (if-let [agent (get @agents name)]
      (let [box (:box @agent)]
        (swap! agents dissoc name)
        (when (-> box :project :containium :ring)
          (remove-box (:ring systems) (:ring-name name)))
        (stop-box name box)
        (deliver promise (Response. true (str "Module " name " successfully killed."))))
      (deliver promise (Response. false (str "Module " name " unknown."))))
    promise))


(defrecord DefaultManager [config systems agents notifiers]
  Manager
  (list-modules [this]
    (list-modules* this))

  (activate! [this name]
    (activate this name nil))

  (activate! [this name descriptor]
    (activate this name descriptor))

  (deactivate! [this name]
    (deactivate this name))

  (kill! [this name]
    (kill this name))

  (register-notifier! [_ name f]
    (swap! notifiers assoc name f))

  (unregister-notifier! [_ name]
    (swap! notifiers dissoc name))

  Stoppable
  (stop [this]
    (if-let [to-undeploy (seq (remove #(= :undeployed (:state (deref (val %)))) @agents))]
      (let [names+promises (for [[name agent] to-undeploy] [name (deactivate! this name)])
            timeout (* 1000 30)]
        (doseq [[name promise] names+promises]
          (println (:message (deref promise timeout
                                    (Response. false (str "Response for undeploying " name
                                                          " timed out."))))))
        (Thread/sleep 1000)
        (recur))
      (println "All modules are undeployed."))))


;;; Constructor function.

(def default-manager
  (reify Startable
    (start [_ systems]
      (require-system Ring systems)
      (let [config (require-system Config systems)]
        (println "Started default Module manager.")
        (DefaultManager. config systems (atom {}) (atom {}))))))
