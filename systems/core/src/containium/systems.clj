;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems
  "Logic for starting and stopping systems.")


(defprotocol Startable
  "Implement this protocol, if you want to have the system started by
  `with-systems` below. This way, one gains access to the previously
  started systems. It is the returned value of the the `start`
  function that is considered to be the actual system."
  (start [this systems]))


(defprotocol Stoppable
  "Implement this protocol, if you want to have the system stopped by
  the `with-systems` function below. Stoppables are stopped in reverse
  order in which they were started."
  (stop [this]))


(defn- with-systems*
  [system-components f systems]
  (if-let [[name system] (first system-components)]
    (try
      (let [system (if (satisfies? Startable system) (start system systems) system)]
        (with-systems* (rest system-components) f (assoc systems name system))
        (when (satisfies? Stoppable system)
          (try
            (stop system)
            (catch Throwable ex
              (println "Exception while stopping system component" name ":" ex)
              (.printStackTrace ex)))))
      (catch Throwable ex
        (println "Exception while starting system component" name ":" ex)
        (.printStackTrace ex)))
    (try
      (f systems)
      (catch Throwable ex
        (println "Exception while running `with-systems` function. Stopping systems.")
        (.printStackTrace ex)))))


(defn with-systems
  "This function starts the root systems for the containium. The
  `system-components` argument is a sequence of alternating
  name-system pairs. Each pair is an identifier (likely a keyword) for
  the root system and a reference to the system implementation and/or
  Startable. If it is a Startable, the return value of the `start`
  function is considered to be the system. It is also that system on
  which `stop` is called, if it satisfies Stoppable."
  [system-components f]
  (assert (even? (count system-components))
          "System components vector needs to have an even number of forms.")
  (with-systems* (partition 2 system-components) f {}))
