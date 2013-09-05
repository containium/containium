;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems
  "Logic for starting and stopping systems.")


;; This record contains a reference to the start function, the stop function and the isolate regex
;; String. The start function takes the spec config and systems as an argument. That what is
;; returned by the start function is put in the 'systems' map, which will be made available to
;; the systems started after the current one, and all the boxes that will be started. The stop
;; function takes that what is returned by the start function. The isolate String is used for
;; letting the Containium know which package names should be isolated inside the boxes (all
;; Clojure namespaces loaded by this system). The start field is mandatory, the other two may
;; be nil.
(defrecord AppSystem [start stop isolate])


(defn with-systems
  "This function starts the root systems for the containium. The
  `system-components` argument is a sequence of tuples, where each
  tuple contains an identifier (likely a keyword) for the root system
  and a reference to an AppSystem record. The config argument contains
  the configuration map in the spec. The systems map contains the
  initial systems (probably {}). The isolates is a sequence of regex
  strings, which is used for isolating the boxes. The f argument is
  the function that is called after all systems have started
  successfully. That function takes the systems map and the isolates
  sequence. When that function returns, the systems are stopped in
  reverse order."
  [system-components config systems isolates f]
  (if-let [[key {:keys [start stop isolate]}] (first system-components)]
    (try
      (let [system (start config systems)]
        (with-systems
          (rest system-components)
          config
          (assoc systems key system)
          (cons isolate isolates)
          f)
        (when stop
          (try
            (stop system)
            (catch Throwable ex
              (println "Exception while stopping system component" key ":" ex)))))
      (catch Throwable ex
        (println "Exception while starting system component" key ":" ex)))
    (f systems isolates)))
