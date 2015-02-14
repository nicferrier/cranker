(ns putsh.core
  (:gen-class)
  (:require
   [clojure.core.async
    :refer [>! <! >!! <!! go go-loop chan close!  alts! alts!! timeout thread]])
  ;;(:require [clojure.tools.logging :as log])
  (:require [taoensso.timbre :as timbre :refer (debug log info warn error fatal)])
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
  (debug "got a websocket")
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
  (let [socket (promise)
        callback (fn [data]
                   (thread (>!! chan [@socket data])))]
    (deliver socket (ws/connect endpoint :on-receive callback))
    @socket))

(defn putsh-connect
  "The app server side of putsh.

`putsh-lb' which is the ws uri of the putsh server

`app-server-uri' which is the uri of the app server.

`number' - the number of connections to open to the putsh server."
  [app-server-uri putsh-lb number]
  (let [ch (chan)
        sockets (doall
                 (map (fn [n] (putsh-make-ws ch putsh-lb)) (range number)))]
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
    (doall (map #(ws/close %) sockets))))

(defn lb-http-request
  "Make a request to the fake load balancer.

This is test code to allow us to run all our tests internally.

`address' is the http address to talk to.

`status', `headers' and `body-regex' are values to use to do
assertions on. If present the assertions are performed."
  [address &{ :keys [status-assert
                     headers-assert
                     body-regex-assert]}]
  (let [response @(http-client/get address)
        { :keys [status headers body] } response]
    (info (format "lb-request[%s][status]: %s" address status))
    (info (format "lb-request[%s][headers]: %s" address headers))
    (info (format "lb-request[%s][body]: %s" address body))
    (do
      (when status-assert
        (assert (== status-assert status)))
      (when headers-assert
        (doall (map (fn [[k v]]
                      (when (headers k)
                        (assert (= (headers k) v))))
                    headers-assert)))
      (when body-regex-assert
        (assert (re-matches body-regex-assert body))))))

(defn appserv-handler
  "Handle requests from putsh as if we are an app server.

This is test code. It fakes a real app server so we can do all our
tests of putsh flow internally." [req]
  (http-server/with-channel req channel
    (http-server/on-close channel (fn [status] #_(println "appserv - " status)))
    (http-server/on-receive
     channel (fn [data]
               (println "ooer! data from an appserv con: " data)))
    (let [response { :status 200
                    :headers { "content-type" "text/html"
                               "server" "fake-appserver-0.0.1" }
                    :body "<h1>my fake appserver!</h1>" }]
      (http-server/send! channel response))))

(defn -main
  "Start putsh." [& args]
  (let [putsh-stop (http-server/run-server putsh-server { :port 8000 })
        lb-stop (http-server/run-server lb { :port 8001 })
        tests true]
    ;; Connect the app-server proxy to the lb proxy
    (thread
     (putsh-connect
      "http://localhost:8003"
      "ws://localhost:8000" 10))
    ;; Tests
    (when tests
      (let [fake-appserver-stop (http-server/run-server appserv-handler { :port 8003 })]
        (thread
         (Thread/sleep 1000)
         ;; Show that the direct request works 
         (lb-http-request
          "http://localhost:8003/blah"
          :status-assert 200
          :headers-assert { :server "http-kit" }
          :body-regex-assert #"<h1>my fake.*")
         ;; Show that a putch request works
         (lb-http-request "http://localhost:8001/blah"))
        (Thread/sleep 8000)
        (fake-appserver-stop)))
    (lb-stop)
    (putsh-stop)
    (info "stopped.")))

;; Ends
