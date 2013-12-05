;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.ring-test
  (:require [clojure.test :refer :all]
            [containium.systems.ring :as ring]))

(deftest config
  (let [clean-ring-conf #'ring/clean-ring-conf]
    (is (= (clean-ring-conf {})                      {:context-path ""}))
    (is (= (clean-ring-conf {:context-path nil})     {:context-path ""}))
    (is (= (clean-ring-conf {:context-path ""})      {:context-path ""}))
    (is (= (clean-ring-conf {:context-path "/"})     {:context-path ""}))
    (is (= (clean-ring-conf {:context-path "/asd"})  {:context-path "/asd"}))
    (is (= (clean-ring-conf {:context-path "/asd/"}) {:context-path "/asd"}))
))
