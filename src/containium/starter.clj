;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.starter
  (:gen-class))

(defn -main [& args]
  (eval `(do (require 'containium.core)
             (containium.core/-main ~@args))))
