;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.modules
  "Functions for managing the starting and stopping of modules."
  (:require [containium.systems])
  (:import [containium.systems Startable]))


;;; Public system definitions.

(defprotocol Manager
  (list-active [this]
    "Returns a map of name-state entries for the currently active modules.")

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
  :undeployed  | [module-name]")

  (unregister-notifier! [this name]
    "Remove a notifier by name."))


(defrecord Response [success message])


;;; Default implementation.

(defrecord Module [name state file])


(defn- notify
  [manager type & args]
  (doseq [f (vals @(:notifiers manager))]
    (f type args)))


(defn- send-to-module
  [manager name f & args]
  (let [agent (get @(:agents manager) name)]
    (apply send agent f args)))


(defn- do-deploy
  [manager name file promise]
  (send-to-module manager name
                  #(do (deliver promise (Response. true "Module successfully deployed."))
                       (notify manager :deployed name file)
                       (assoc % :state :deployed))))


(defn- handle-deploy
  [module manager file promise]
  (case (:state module)
    :deploying (do (deliver promise (Response. false "Module is already deploying.")) module)
    :deployed (do (deliver promise (Response. false "Module is already deployed.")) module)
    :redeploying (do (deliver promise (Response. false "Module is currently redeploying.")) module)
    :swapping (do (deliver promise (Response. false "Module is currently swapping.")) module)
    :undeploying (do (deliver promise (Response. false "Module is currently undeploying.")) module)
    :undeployed (do (do-deploy manager (:name module) file promise)
                    (assoc module :state :deploying))))


(defn- do-undeploy
  [manager name promise]
  (send-to-module manager name
                  #(do (deliver promise (Response. true "Module successfully undeployed."))
                       (notify manager :undeployed name)
                       (assoc % :state :undeployed))))


(defn- handle-undeploy
  [module manager promise]
  (case (:state module)
    :deploying (do (deliver promise (Response. false "Module is currently deploying.")) module)
    :redeploying (do (deliver promise (Response. false "Module is currently redeploying.")) module)
    :swapping (do (deliver promise (Response. false "Module is currently swapping.")) module)
    :undeploying (do (deliver promise (Response. false "Module is already undeploying.")) module)
    :undeployed (do (deliver promise (Response. false "Module is already undeployed.")) module)
    :deployed (do (do-undeploy manager (:name module) promise)
                  (assoc module :state :undeploying))))


(defn- handle-redeploy
  [module manager promise]
  (deliver promise (Response. false "Redeployments are not implemented yet."))
  module)


(defrecord DefaultManager [config systems agents notifiers]
  Manager
  (list-active [_]
    (into {} (keep (fn [[name agent]]
                     (let [state (:state agent)]
                       (when-not (= :undeployed state)
                         [name state])))
                   @agents)))

  (deploy! [this name file]
    (swap! agents (fn [current name]
                    (if-not (current name)
                      (assoc current name (agent (Module. name :undeployed nil)))
                      current)) name)
    (let [promise (promise)]
      (send-to-module this name handle-deploy this file promise)
      promise))

  (undeploy! [this name]
    (let [promise (promise)]
      (send-to-module this name handle-undeploy this promise)
      promise))

  (redeploy! [this name]
    (let [promise (promise)]
      (send-to-module this name handle-redeploy this promise)
      promise))

  (register-notifier! [_ name f]
    (swap! notifiers assoc name f))

  (unregister-notifier! [_ name]
    (swap! notifiers dissoc name)))


(def default-manager
  (reify Startable
    (start [_ systems]
      (DefaultManager. (:config systems) systems (atom {}) (atom {})))))
