(ns cranker.core
  "Cranker - reverse the polairty of your HTTP and Websockets."
  (:gen-class)
  (:require [cranker.utils :refer :all])
  (:require [cranker.testrig :refer :all])
  (:require [clojure.test :refer :all])
  (:require [clojure.string :as str])
  (:require [clojure.pprint :as pp])
  (:require
   [clojure.core.async
    :refer [>! <! >!! <!! go go-loop chan close!  alts! alts!! timeout thread]])
  (:require [taoensso.timbre :as timbre :refer (debug log info warn error fatal)])
  (:require [org.httpkit.client :as http-client])
  (:require [gniazdo.core :as ws])
  (:require [clojure.data.json :as json])
  (:require [clojure.walk])
  (:require [org.httpkit.server :as http-server])
  (:import [java.io File FileOutputStream]))

(def channels
  "The list of channels from cranker.

The list is a map, the keys are the cranker channels. The value is
either nil or a committed load balancer channel."
  (ref {}))

(defn frame
  "Frame an http-kit request into JSON." [request]
  (let [{ :keys [request-method headers uri query-string form-data body]} request
        ;;{ method :request-method headers :headers  uri :uri query :query-string body :body } request
        ]
    (info "frame " uri request-method headers query-string form-data body request)
    { :http-request
     { :uri uri
      :method request-method
      :headers headers
      :body (if (= (type body) org.httpkit.BytesInputStream)
              (slurp body)
              (or body query-string)) }}))

(defn de-frame 
  "De-frame a JSON response into an http-kit response."
  [response-json]
  (let [{ status :status headers :headers body :body } response-json]
    { :status status
     :headers (clojure.walk/stringify-keys headers)
     :body body }))

(defn lb-server
  "Handle requests from the load balancer.

Requests are sent over the first free cranker websocket.  We wrap the
initial HTTP request up in a JSON structure and send it over the
channel established by `cranker-server'."
  [request]
  (http-server/with-channel request channel
    (info "lb-server request " request)
    (http-server/on-close channel (fn [status] (debug "lb-server closed " status)))
    (http-server/on-receive
     channel (fn [data] ; get the cranker channel from @channels and send it the data
               (warn "lb-server data from a lb con: " data)))
    (let [first-free (first (filter #(nil? (% 1)) @channels))
          cranker-chan (when first-free (first-free 0))
          framed-req (frame request)]
      (when cranker-chan
        (dosync (alter channels assoc cranker-chan channel))
        (info "lb-server sending to cranker " (trunc-str (str framed-req) 50))
        (http-server/send! cranker-chan (json/write-str framed-req))))))

(defn cranker-server
  "Handle requests from the app-server side of cranker.

A websocket is established from the app-server side. Any data we
receive on it is the response from a request we made on behalf of the
load balancer."
  [request]
  (http-server/with-channel request channel
    (debug "cranker-server got a websocket")
    (dosync (alter channels assoc channel nil))
    (http-server/on-close ; remove the socket on close - we should start a new one?
     channel (fn [status] (dosync (alter channels dissoc channel))))
    (http-server/on-receive
     channel
     (fn [data]    ; Send the data back to the load balancer
       (let [lb-channel (@channels channel)]
         (if lb-channel
           (let [response-json (json/read-str data :key-fn keyword)
                 response (de-frame response-json)]
             (http-server/send! lb-channel response))
           ;; else
           (error "cranker-server received without an lb channel")))))))

(defn cranker-make-ws [chan endpoint]
  (let [socket (promise)
        callback (fn [data]
                   (thread (>!! chan [@socket data])))]
    (deliver socket (ws/connect endpoint :on-receive callback))
    @socket))

(defn cranker-connector
  "The app server side of cranker.

`cranker-lb' which is the ws uri of the cranker server

`app-server-uri' which is the uri of the app server.

`number' - the number of connections to open to the cranker server.

Returns a promise which we might wait on."
  [ctrl app-server-uri cranker-lb number]
  (thread
   (let [ch (chan)
         sockets (doseq [n (range number)]
                   (cranker-make-ws ch cranker-lb))]
     (debug "cranker-connector: started cranker")
     (go-loop
      [[socket data] (<! ch)] ;; data is the http request
      (let [request (try (json/read-str data) (catch Exception e { :json-error e }))
            { err :json-error http-request "http-request" } request]
        (if err
          (ws/send-msg socket (json/write-str err)) ; need to do something more?
          ;; else it's a request
          (let [{ :strs [uri method headers body] } http-request
                ;; need to test if app-server-uri ends in /
                request-uri (str app-server-uri (or uri "/"))]
            (info "cranker-connector ws uri: "
                  request-uri "[" method "] {" (trunc-str body 10) "}")
            (http-client/request
             { :url request-uri :method (keyword method)
              :headers headers :body body
              :timeout 200 }
             (fn [{ :keys [status headers body error] }]
               (let [out-frame { :status status :headers headers :body body }]
                 (ws/send-msg socket (json/write-str out-frame)))))))
        (recur (<! ch))))
     (loop [[msg & args] (<!! ctrl)]
       (info "cranker-connector loop " msg)
       (when (case msg
               (:stop (do (doseq [socket sockets] (ws/close socket))
                          true)))
         (recur (<!! ctrl)))))
   :app-prox-ended))

(defn start-lb
  "Start the load balancer side of cranker.

Two listeners are started, the load balancer listener and the cranker
listener.

Returns a promise which will be set when end is received on the ctrl
channel."
  [ctrl & { :keys [lb-port cranker-port] :or { lb-port 8001 cranker-port 8000 } }]
  (thread
   (let [stops [(http-server/run-server cranker-server { :port cranker-port })
                (http-server/run-server lb-server { :port lb-port })]]
     (debug "start-lb: started load balancer")
     (loop [[msg & args] (<!! ctrl)]
       (when
           (case msg
             :stop (do
                     (info "start-lb got a stop")
                     (doseq [stop stops] (stop))
                     (info "start-lb stopped servers"))
             true)
         (recur (<!! ctrl)))))
   :lb-ended))

(def app-server-default "http://localhost:8003")
(def lb-server-default "ws://localhost:8000")

(defn -main
  "Start cranker." [& args]
  (timbre/set-config! [:fmt-output-fn] cranker-formatter)
  (timbre/set-config! [:timestamp-pattern] "yyyy-MM-dd HH:mm:ss")
  (timbre/set-level! :info)
  (let [mode :test
        lb-ctrl (chan)
        ap-ctrl (chan)
        app-server app-server-default
        lb-prox lb-server-default
        service-channels
        (case mode
          :test
          { (start-lb lb-ctrl) :lb
            (cranker-connector
             ap-ctrl app-server lb-prox 10) :app-prox }
          :lb
          { (start-lb lb-ctrl) :lb }
          :app-prox
          { (cranker-connector
             ap-ctrl app-server lb-prox 10) :app-prox })]
    ;; Tests
    (when (= mode :test)
      (test-lb lb-ctrl ap-ctrl))
    ;; Collect the endings of the things as they signal the service-channel threads
    (loop [channels service-channels]
      (when (not-empty channels)
        (let [chans (keys channels)
              [end ch] (alts!! chans)]
          (recur (dissoc channels ch)))))
    (info "end")
    (System/exit 0)))

;; Ends
