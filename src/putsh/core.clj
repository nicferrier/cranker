(ns putsh.core
  (:gen-class)
  (:require
   [clojure.core.async
    :refer [>! <! >!! <!! go go-loop chan close!  alts! alts!! timeout thread]])
  ;;(:require [clojure.tools.logging :as log])
  (:require [org.httpkit.client :as http-client])
  (:require [gniazdo.core :as ws])
  (:require [clojure.data.json :as json])
  (:require [clojure.walk])
  (:require [org.httpkit.server :as http-server]))

(def channels
  "The list of channels from putsh.

The list is a map, the keys are the putsh channels. The value is
either nil or a committed load balancer channel."
  (ref {}))

(defn frame
  "Frame an http-kit request into JSON." [request]
  (let [{ method :request-method headers :headers
         uri :uri query :query-string body :body } request]
    { :http-request
     { :uri uri
      :method method
      :headers headers
      :query-string query
      :body body }}))

(defn de-frame 
  "De-frame a JSON response into an http-kit response."
  [response-json]
  (let [{ status :status headers :headers body :body } response-json]
    { :status status
     :headers (clojure.walk/stringify-keys headers)
     :body body }))

(defn lb
  "Handle requests from the load balancer.

Requests are sent over the first free putsh websocket.  We wrap the
initial HTTP request up in a JSON structure and send it over the
channel established by `putsh-server'." [request]
  (http-server/with-channel request channel
    (http-server/on-close channel (fn [status] #_(println "lb - " status)))
    (http-server/on-receive
     channel (fn [data] ; get the putsh channel from @channels and send it the data
               (println "ooer! data from a lb con: " data)))
    (let [first-free (first (filter #(nil? (% 1)) @channels))
          putsh-chan (when first-free (first-free 0))]
      (when putsh-chan
        (dosync (alter channels assoc putsh-chan channel))
        (http-server/send!
         putsh-chan (json/write-str (frame request)))))))

(defn putsh-server
  "Handle requests from the app-server side of putsh.

A websocket is established from the app-server side. Any data we
receive on it is the response from a request we made on behalf of the
load balancer." [request]
  (http-server/with-channel request channel
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
           (println "whoops! putsh-server received without an lb channel")))))))

(defn putsh-make-ws [chan endpoint]
  (let [socket (promise)]
    (deliver
     socket
     (ws/connect
      endpoint
      :on-receive (fn [data] (thread (>!! chan [@socket data])))))
    @socket))

(defn putsh-connect
  "The app server side of putsh.

`putsh-lb' which is the ws uri of the putsh server

`app-server-uri' which is the uri of the app server.

`n' - the number of connections to open to the putsh server."
  [putsh-lb app-server-uri]
  (let [ch (chan)
        sockets (map (fn [n] (putsh-make-ws ch putsh-lb)) (range 10))]
    (go-loop
     [[socket data] (<! ch)] ;; data is the http request
     (let [request (try (json/read-str data) (catch Exception e { :json-error e }))
           { err :json-error http-request :http-request } request]
       (if err
         (ws/send-msg socket (json/write-str err)) ; need to do something more?
         ;; else it's a request
         (let [{ uri :uri headers :headers } http-request
               ;; need to test if app-server-uri ends in /
               request-uri (str app-server-uri (or uri "/"))]
           (http-client/get
            request-uri
            { :headers headers :timeout 200 }
            (fn [{ :keys [status headers body error] }]
              (let [out-frame { :status status :headers headers :body body }]
                (ws/send-msg socket (json/write-str out-frame)))))))
       (recur (<! ch))))
    (Thread/sleep 5000)
    (map #(ws/close %) sockets)))

(defn lb-http-request
  "Make a request to the fake load balancer.

This is test code to allow us to run all our tests internally."
  [address method]
  (Thread/sleep 1000)
  (let [response @(http-client/get address)
        { status :status headers :headers body :body } response]
    (println (format "lb-request[%s][status]: %s" method status))
    (println (format "lb-request[%s][headers]: %s" method headers))
    (println (format "lb-request[%s][body]: %s" method body))))

(defn appserv-handler
  "Handle requests from putsh as if we are an app server.

This is test code. It fakes a real app server so we can do all our
tests of putsh flow internally." [req]
  (http-server/with-channel req channel
    (http-server/on-close channel (fn [status] #_(println "appserv - " status)))
    (http-server/on-receive
     channel (fn [data]
               (println "ooer! data from an appserv con: " data)))
    (http-server/send!
     channel { :status 200
              :headers { "content-type" "text/html"
                         "server" "fake-appserver-0.0.1" }
              :body "<h1>my fake appserver!</h1>" })))

(defn -main
  "Start putsh." [& args]
  (let [putsh-stop (http-server/run-server putsh-server { :port 8000 })
        lb-stop (http-server/run-server lb { :port 8001 })
        fake-appserver-stop (http-server/run-server appserv-handler { :port 8003 })]
    ;;(log.log "started")
    ;; Connect the app-server proxy to the lb proxy
    (thread (putsh-connect "ws://localhost:8000" "http://localhost:8003"))
    ;; Tests
    (thread
     ;; Show that the direct request works 
     (lb-http-request "http://localhost:8003/blah" "direct")
     ;; Show that a putch request works
     (lb-http-request "http://localhost:8001/blah" "putsh"))
    (Thread/sleep 8000)
    (lb-stop)
    (putsh-stop)
    (fake-appserver-stop)
    (println "stopped.")))

;; Ends
