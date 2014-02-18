;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.exceptions
  (:import [java.lang OutOfMemoryError]))


(defn fatal?
  "Returns a boolean indicating whether the cause is regarded as
  fatal."
  [cause]
  (boolean (#{OutOfMemoryError} (class cause))))


(defn exit-when-fatal
  "Executes a System/exit when the given cause is regarded as fatal."
  [cause]
  (when (fatal? cause)
    (println "Killing JVM because of fatal error:" cause)
    (System/exit 1)))


(defn register-default-handler
  "Registers a default UncaughtExceptionHandler for all threads,
  performing a System/exit on a fatal exception or error."
  []
  (println "Registering default uncaught exception handler.")
  (let [handler (reify java.lang.Thread$UncaughtExceptionHandler
                  (^void uncaughtException [this ^Thread thread ^Throwable cause]
                    (exit-when-fatal cause)))]
    (Thread/setDefaultUncaughtExceptionHandler handler)))
