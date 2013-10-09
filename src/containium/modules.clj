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
  (list-active [this]
    "Returns a sequence of maps with :name and :state entries for the
  currently active modules.")

  (deploy! [this name file]
    "Try to deploy the file under the specified name. A promise is
  returned, which will eventually hold a Response record.")

  (undeploy! [this name]
    "Try to undeploy the module with the specified name. A promise is
  returned, which will eventually hold a Response record.")

  (redeploy! [this name]
    "Try to redeploy the module with the specified name. A promise is
  returned, which will eventually hold a Response record.")

  (register-notifier! [this name f]
    "Register a notifier function by name, which is called on events
  happening regarding the modules. The function takes two arguments. The
  first is the kind of event, and the second is a sequence of extra data
  for that event. The following events are send:

  kind         | data
  ------------------------------------------
  :deployed    | [module-name file]
  :failed      | [module-name]
  :undeployed  | [module-name]")

  (unregister-notifier! [this name]
    "Remove a notifier by name."))


(defrecord Response [success message])


;;; Default implementation.

(defrecord Module [name state file box])


(defn- notify
  [manager type & args]
  (doseq [f (vals @(:notifiers manager))]
    (f type args)))


(defn- send-to-module
  [manager name f & args]
  (if-let [agent (get @(:agents manager) name)]
    (apply send agent f args)
    (println "No module named" name "available.")))


(defn- invalid-state
  [{:keys [name] :as module} promise occupation-str]
  (deliver promise (Response. false (str "Module " name " is " occupation-str ".")))
  module)


(defn- module-file
  "Checks whether the file is a module file. This function returns a
  triple. The first item is the (possibly updated) file. The second
  item is the :containium map that needs to merged onto the project map
  of the project map, but it may be nil. The third item is a vector of
  profiles to apply when loading the module, which may be nil as well."
  [^File file]
  (if (.isDirectory file)
    [file nil nil]
    (if-let [module-map (try (edn/read-string (slurp file)) (catch Exception ex))]
      [(File. file (str (:file module-map))) (:containium module-map) (:profiles module-map)]
      [file nil nil])))


(defn- do-deploy
  [manager {:keys [name] :as module} file promise]
  (let [[file containium-merge profiles] (module-file file)
        boxure-config (-> (get-config (-> manager :systems :config) :modules)
                          (assoc :profiles profiles))]
    (if-let [box (start-box name file boxure-config (:systems manager))]
      (let [box (assoc-in box [:project :containium]
                          (merge (-> box :project :containium) containium-merge))]
        (when (-> box :project :containium :ring)
          (upstart-box (-> manager :systems :ring) name box))
        (send-to-module manager name
                        #(do (deliver promise (Response. true (str "Module " name
                                                                   " successfully deployed.")))
                             (notify manager :deployed name file)
                             (assoc % :state :deployed :file file :box box))))
      ; Else:
      (send-to-module manager name
                      #(do (deliver promise (Response. false (str "Error while deploying module "
                                                                  name ".")))
                           (notify manager :failed name)
                           (assoc % :state :undeployed))))))


(defn- handle-deploy
  [{:keys [name state] :as module} manager file promise]
  (case state
    :deploying (invalid-state module promise "already deploying.")
    :deployed (invalid-state module promise "already deployed.")
    :redeploying (invalid-state module promise "currently redeploying.")
    :swapping (invalid-state module promise "currently swapping.")
    :undeploying (invalid-state module promise "currently undeploying.")
    :undeployed (do (future (do-deploy manager module file promise))
                    (notify manager :deploying name)
                    (assoc module :state :deploying, :file file))))


(defn- do-undeploy
  [manager {:keys [name box] :as module} promise]
  (let [response (if (do (when (-> box :project :containium :ring)
                           (remove-box (-> manager :systems :ring) name))
                         (stop-box name box))
                   (Response. true (str "Module " name " successfully undeployed."))
                   (Response. false (str "Error while undeploying module " name ".")))]
    (send-to-module manager name
                    #(do (deliver promise response)
                         (notify manager :undeployed name)
                         (assoc % :state :undeployed :box nil)))))


(defn- handle-undeploy
  [{:keys [name state] :as module} manager promise]
  (case state
    :deploying (invalid-state module promise "currently deploying.")
    :redeploying (invalid-state module promise "currently redeploying.")
    :swapping (invalid-state module promise "currently swapping.")
    :undeploying (invalid-state module promise "already undeploying.")
    :undeployed (invalid-state module promise "already undeployed.")
    :deployed (do (future (do-undeploy manager module promise))
                  (notify manager :undeploying name)
                  (assoc module :state :undeploying))))


(defn- do-redeploy
  [manager {:keys [name file] :as module} promise]
  (let [timeout (* 1000 30)
        {:keys [success message] :as response} (deref (undeploy! manager name) timeout ::timeout)]
    (if success
      (let [{:keys [success message] :as response} (deref (deploy! manager name file)
                                                          timeout ::timeout)]
        (if success
          (deliver promise (Response. true (str "Module " name " successfully redeployed.")))
          (if (= response ::timeout)
            (deliver promise (Response. false (str "Response for deployment while redeploying "
                                                   "timed out. Deploying may still succeed.")))
            (deliver promise (Response. false (str "Error while redeploying module " name
                                                   ", reason: " message))))))
      (if (= response ::timeout)
        (deliver promise (Response. false (str "Response for undeployment while redeploying "
                                               "timed out. Undeploying may still succeed.")))
        (deliver promise (Response. false (str "Error while redeploying module " name
                                                    ", reason: " message)))))))


(defn- handle-redeploy
  [{:keys [name state] :as module} manager promise]
  (case state
    :deploying (invalid-state module promise "already deploying")
    :redeploying (invalid-state module promise "already redeploying")
    :swapping (invalid-state module promise "already swapping")
    :undeploying (invalid-state module promise "currently undeploying")
    :undeployed (handle-deploy module manager (:file module) promise)
    :deployed (do (future (do-redeploy manager module promise))
                  (notify manager :redeploying name)
                  ;; (assoc module :state :redeploying)
                  ;; --- TODO, above will be possible when queuing is implemented.
                  module)))


(defn- agent-error-handler
  [agent ^Exception exception]
  (println "Exception in module agent:")
  (.printStackTrace exception))


(defrecord DefaultManager [config systems agents notifiers]
  Manager
  (list-active [_]
    (keep (fn [[name agent]]
            (let [state (:state @agent)]
              (when (not= :undeployed state)
                {:name name :state state})))
          @agents))

  (deploy! [this name file]
    (swap! agents (fn [current name]
                    (if-not (current name)
                      (assoc current name (agent (Module. name :undeployed nil nil)
                                                 :error-handler agent-error-handler))
                      current)) name)
    (let [promise (promise)]
      (send-to-module this name handle-deploy this file promise)
      promise))

  (undeploy! [this name]
    (if (@agents name)
      (let [promise (promise)]
        (send-to-module this name handle-undeploy this promise)
        promise)
      (future (Response. false (str "No module named " name " known.")))))

  (redeploy! [this name]
    (if (@agents name)
      (let [promise (promise)]
        (send-to-module this name handle-redeploy this promise)
        promise)
      (future (Response. false (str "No module named " name " known.")))))

  (register-notifier! [_ name f]
    (swap! notifiers assoc name f))

  (unregister-notifier! [_ name]
    (swap! notifiers dissoc name))

  Stoppable
  (stop [this]
    (if-let [to-undeploy (seq (remove #(= :undeployed (:state (deref (val %)))) @agents))]
      (let [names+promises (for [[name agent] to-undeploy] [name (undeploy! this name)])
            timeout (* 1000 30)]
        (doseq [[name promise] names+promises]
          (println (:message (deref promise timeout
                                    (Response. false (str "Response for undeploying " name
                                                          " timed out."))))))
        (Thread/sleep 1000)
        (recur))
      (println "All modules are undeployed."))))


(def default-manager
  (reify Startable
    (start [_ systems]
      (require-system Ring systems)
      (let [config (require-system Config systems)]
        (println "Started default Module manager.")
        (DefaultManager. config systems (atom {}) (atom {}))))))
