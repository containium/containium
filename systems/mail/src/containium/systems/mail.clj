;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.systems.mail
  "A mail sending system."
  (:require [containium.systems :refer (require-system Startable)]
            [containium.systems.config :as config :refer (Config)]
            [containium.systems.logging :refer (SystemLogger refer-logging)]
            [postal.core :as postal]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.java.io :as io :refer (resource)])
  (:import [java.io ByteArrayInputStream]))
(refer-logging)


;;; The public API

(defprotocol Mail
  (send-message
    [this from to subject body]
    [this from to subject body options]))


;;; An SMTP implementation using Postal

(defrecord Postal [logger smtp]
  Mail
  (send-message [this from to subject body]
    (send-message this from to subject body nil))

  (send-message [this from to subject body opts]
    (debug logger "Sending email from" from "to" to "with subject" subject "using options" opts)
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
        (Postal. logger config)))))


(defn- make-inline
  [html-str src-root src-map]
  (let [src-root (str src-root (when-not (= (last src-root) \/) "/"))]
    (loop [loc (zip/xml-zip (xml/parse (ByteArrayInputStream. (.getBytes (.trim html-str)))))
           contents []]
      (if (zip/end? loc)
        (cons {:type "text/html; charset=utf-8"
               :content (with-out-str (xml/emit-element (zip/node loc)))}
              contents)
        (let [node (zip/node loc)]
          (if (= :img (:tag node))
            (let [cid (str (gensym "img-"))
                  src (-> node :attrs :src)
                  content (or (get src-map src) (resource (str src-root src)))]
              (recur (zip/next (zip/edit loc assoc-in [:attrs :src] (str "cid:" cid)))
                     (cond-> contents
                             content (conj {:type :inline, :content content, :content-id cid}))))
            (recur (zip/next loc) contents)))))))


(defn send-html-message
  "Use this function to send a HTML mail with the Postal SMTP Mail
  system. It needs at least the mail system, the from address, the to
  address, a subject, and the HTML string. The following options are
  also available:

  :text - You can supply a plain text version of the message, which
    will be included as an alternative.

  :src-root - The root directory/package in which the image resources
    can be found on the classpath for inlining. Note that due to a
    limitation in javax.mail/Postal the actual resource must be
    unpacked, i.e. not in a JAR.

  :src-map - This is a map holding files for image tags that, taking
    the 'src' attribute as key, contains resource values for inline
    use. This map overrides the default lookup when inlining. Note
    that the values in this map must support being passed to
    clojure.java.io/file."
  [mail-system from to subject html & {:keys [text src-root src-map]}]
  (let [html-contents (make-inline html src-root src-map)
        contents (remove nil? [:alternative
                               (when text {:type "text/plain" :content text})
                               (cons :related html-contents)])]
    (send-message mail-system from to subject contents)))
