;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.modules
  "Functions for managing the starting and stopping of modules.")


;;; Data definitions.

(defrecord Module [name state file])

(defrecord Response [success message])

(def ^:private agents (atom {}))

(def ^:private notifiers (atom {}))


;;; Private functions on the agents.

(defn- notify
  [type & args]
  (doseq [f (vals @notifiers)]
    (f type args)))


(defn- send-to-module
  [name f & args]
  (let [agent (get @agents name)]
    (apply send agent f args)))


(defn- do-deploy
  [name file promise]
  (send-to-module name
                  #(do (deliver promise (Response. true "Module successfully deployed."))
                       (notify :deployed name file)
                       (assoc % :state :deployed))))


(defn- handle-deploy
  [module file promise]
  (case (:state module)
    :deploying (do (deliver promise (Response. false "Module is already deploying.")) module)
    :deployed (do (deliver promise (Response. false "Module is already deployed.")) module)
    :redeploying (do (deliver promise (Response. false "Module is currently redeploying.")) module)
    :swapping (do (deliver promise (Response. false "Module is currently swapping.")) module)
    :undeploying (do (deliver promise (Response. false "Module is currently undeploying.")) module)
    :undeployed (do (do-deploy (:name module) file promise)
                    (assoc module :state :deploying))))


(defn- do-undeploy
  [name promise]
  (send-to-module name
                  #(do (deliver promise (Response. true "Module successfully undeployed."))
                       (notify :undeployed name)
                       (assoc % :state :undeployed))))


(defn- handle-undeploy
  [module promise]
  (case (:state module)
    :deploying (do (deliver promise (Response. false "Module is currently deploying.")) module)
    :redeploying (do (deliver promise (Response. false "Module is currently redeploying.")) module)
    :swapping (do (deliver promise (Response. false "Module is currently swapping.")) module)
    :undeploying (do (deliver promise (Response. false "Module is already undeploying.")) module)
    :undeployed (do (deliver promise (Response. false "Module is already undeployed.")) module)
    :deployed (do (do-undeploy (:name module) promise)
                  (assoc module :state :undeploying))))


(defn- handle-redeploy
  [module promise]
  (deliver promise (Response. false "Redeployments are not implemented yet."))
  module)



;;; Public functions.

(defn list-active
  []
  "Returns a map of name-state entries for the currently active modules."
  (into {} (keep (fn [[name agent]]
                   (let [state (:state @agent)]
                     (when (#{:deploying :deployed :undeploying :redeploying :swapping} state)
                       [name state]))))))


(defn deploy!
  "Try to deploy the file under the specified name. A promise is
  returned, which will eventually hold a Response record."
  [name file]
  (swap! agents (fn [current name]
                  (if-not (current name)
                    (assoc current name (agent (Module. name :undeployed nil)))
                    current)) name)
  (let [promise (promise)]
    (send-to-module name handle-deploy file promise)
    promise))


(defn undeploy!
  "Try to undeploy the module with the specified name. A promise is
  returned, which will eventually hold a Response record."
  [name]
  (let [promise (promise)]
    (send-to-module name handle-undeploy promise)
    promise))


(defn redeploy!
  "Try to redeploy the module with the specified name. A promise is
  returned, which will eventually hold a Response record."
  [name]
  (let [promise (promise)]
    (send-to-module name handle-redeploy promise)
    promise))


(defn register-notifier!
  "Register a notifier function by name, which is called on events
  happening regarding the modules. The function takes two arguments. The
  first is the kind of event, and the second is a sequence of extra data
  for that event. The following events are send:

  kind         | data
  ------------------------------------------
  :deployed    | [module-name file]
  :undeployed  | [module-name]"
  [name f]
  (swap! notifiers assoc name f))


(defn unregister-notifier!
  "Remove a notifier by name."
  [name]
  (swap! notifiers dissoc name))
