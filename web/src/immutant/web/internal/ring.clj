;; Copyright 2014-2017 Red Hat, Inc, and individual contributors.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;; http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ^{:no-doc true} immutant.web.internal.ring
    (:require [from.potemkin                 :refer [def-map-type]]
              [clojure.java.io               :as io]
              [immutant.web.async            :as async]
              [immutant.web.internal.headers :as hdr]
              [immutant.internal.util        :refer [try-resolve]])
    (:import [java.io File InputStream OutputStream]
             clojure.lang.PersistentHashMap
             [io.undertow.io Sender]
             [clojure.lang ISeq]
             [java.nio ByteBuffer]
             [java.nio.channels Channels]))

(defprotocol Session
  (attribute [session key])
  (set-attribute! [session key value])
  (get-expiry [session])
  (set-expiry [session timeout]))

(def ring-session-key "ring-session-data")

(defn ring-session [session]
  (if session (attribute session ring-session-key)))
(defn set-ring-session! [session, data]
  (set-attribute! session ring-session-key data))

(defn set-session-expiry
  [session timeout]
  (when (not= timeout (get-expiry session))
    (set-expiry session timeout))
  session)

(def-map-type LazyMap [^java.util.Map m]
  (get [_ k default-value]
    (if (.containsKey m k)
      (let [v (.get m k)]
        (if (delay? v)
          @v
          v))
      default-value))
  (assoc [_ k v]
    (LazyMap.
      (assoc
          (if (instance? PersistentHashMap m)
            m
            (PersistentHashMap/create m)) k v)))
  (dissoc [_ k]
    (LazyMap.
      (dissoc
        (if (instance? PersistentHashMap m)
          m
          (PersistentHashMap/create m)) k)))
  (keys [_]
    (keys m)))

(defprotocol RingRequest
  (server-port [x])
  (server-name [x])
  (remote-addr [x])
  (uri [x])
  (query-string [x])
  (scheme [x])
  (request-method [x])
  (headers [x])
  (content-type [x])
  (content-length [x])
  (character-encoding [x])
  (ssl-client-cert [x])
  (body [x])
  (context [x])
  (path-info [x]))

(defn ring-request-map
  ([request & extra-entries]
     (->LazyMap
       (let [m (doto (java.util.HashMap. 24)
                 (.put :server-port        (delay (server-port request)))
                 (.put :server-name        (delay (server-name request)))
                 (.put :remote-addr        (delay (remote-addr request)))
                 (.put :uri                (delay (uri request)))
                 (.put :query-string       (delay (query-string request)))
                 (.put :scheme             (delay (scheme request)))
                 (.put :request-method     (delay (request-method request)))
                 (.put :headers            (delay (headers request)))
                 (.put :content-type       (delay (content-type request)))
                 (.put :content-length     (delay (content-length request)))
                 (.put :character-encoding (delay (character-encoding request)))
                 (.put :ssl-client-cert    (delay (ssl-client-cert request)))
                 (.put :body               (delay (body request)))
                 (.put :context            (delay (context request)))
                 (.put :path-info          (delay (path-info request))))]
         (doseq [[k v] extra-entries]
           (.put m k v))
         m))))

(defprotocol RingResponse
  (header-map [x])
  (set-status [x status])
  (write-sync-response [x status headers body])
  (resp-character-encoding [x]))

(defprotocol WriteBody
  (write-body-sync [this outputstream exchange])
  (write-body-async [this sender exchange]))

(extend-protocol WriteBody
  (Class/forName "[B")
  (write-body-sync [body ^OutputStream os _]
    (let [channel (Channels/newChannel os)]
      (.write channel (ByteBuffer/wrap body))))
  (write-body-async [body ^Sender sender _]
    (.send sender (ByteBuffer/wrap body)))

  String
  (write-body-sync [body ^OutputStream os exchange]
    (.write os (.getBytes body ^String (resp-character-encoding exchange))))
  (write-body-async [body ^Sender sender _]
    (.send sender body))

  ByteBuffer
  (write-body-sync [body ^OutputStream os _]
    (let [channel (Channels/newChannel os)]
      (.write channel body)))
  (write-body-async [body ^Sender sender _]
    (.send sender body))

  File
  (write-body-sync [body ^OutputStream os _]
    (io/copy body os))

  ISeq
  (write-body-sync [body os exchange]
    (doseq [fragment body]
      (write-body-sync fragment os exchange)))

  InputStream
  (write-body-sync [body ^OutputStream os _]
    (with-open [body body]
      (io/copy body os)))

  Object
  (write-body-async [body _]
    (throw (IllegalStateException. (str "Can't coerce body of type " (class body)))))
  (write-body-sync [body _]
    (throw (IllegalStateException. (str "Can't coerce body of type " (class body)))))

  nil
  (write-body-async [_ _])
  (write-body-sync [_ _]))

(defprotocol WriteTo
  (write-to [this body exchange]))

(extend-protocol WriteTo
  Sender
  (write-to [sender body exchange]
    (write-body-async body sender exchange))

  OutputStream
  (write-to [os body exchange]
    (write-body-sync body os exchange)))

(defn handle-write-error [request-map response {:keys [write-error-handler] :as response-map} e]
  (when write-error-handler
    ;; if the error isn't rethrown, the status will be
    ;; whatever was set in the response map, so we
    ;; try to set it to 500. This will fail if the body has
    ;; been partially sent, hence the swallow
    (try
      (set-status response 500)
      (catch Exception _))
    (write-error-handler e request-map response-map)))

(defn write-response
  "Set the status, write the headers and the content"
  [exchange response-map]
  (let [body (:body response-map)
        headers (:headers response-map)
        status (:status response-map)]
    (if (async/streaming-body? body)
      (async/open-stream body response-map
                         (partial set-status exchange)
                         (partial hdr/set-headers (header-map exchange)))
      (write-sync-response exchange status headers body))))

;; ring 1.3.2 introduced a change that causes wrap-resource to break
;; in-container because it doesn't know how to handle vfs: urls. ring
;; 1.4.0 will include a multi-method that allows us to provide the
;; correct data for vfs: urls, so we provide an implementation here
;; see IMMUTANT-525 for more details
(when (try-resolve 'ring.util.response/resource-data)
  (eval
    '(defmethod ring.util.response/resource-data :vfs
       [url]
       (let [conn (.openConnection url)
             vfile (.getContent conn)]
         (when-not (.isDirectory vfile)
           {:content (.getInputStream conn)
            :content-length (.getContentLength conn)
            :last-modified (-> vfile
                             .getPhysicalFile
                             ring.util.io/last-modified-date)})))))
