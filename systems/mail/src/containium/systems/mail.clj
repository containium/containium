;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.mail
  "A mail sending system."
  (:require [containium.systems :refer (require-system Startable)]
            [containium.systems.config :as config :refer (Config)]
            [containium.systems.logging :refer (SystemLogger info warn)]
            [postal.core :as postal]))


;;; The public API

(defprotocol Mail
  (send-message
    [this from to subject body]
    [this from to subject body options]))


;;; An SMTP implementation using Postal

(defrecord Postal [smtp]
  Mail
  (send-message [this from to subject body]
    (send-message this from to subject body nil))

  (send-message [this from to subject body opts]
    (postal/send-message smtp (merge {:from from, :to to, :subject subject, :body body} opts))))


(def ^{:doc "This Startable needs a Config system to in the systems. The
            configuration is read from the :postal key within that
            config. It should hold the SMTP data as specified by the
            postal library.

            The started Postal instance can be used to send e-mail
            messages. The body can be anything that the postal library
            supports. The optional `opts` parameter is merged with the
            send info, such as extra headers."}
  postal
  (reify Startable
    (start [_ systems]
      (let [config (config/get-config (require-system Config systems) :postal)
            logger (require-system SystemLogger systems)]
        (info logger "Starting Postal system, using config:" config)
        (warn (containium.systems.logging/get-logger logger "mail") "ALL YOUR BASE")
        (Postal. config)))))
