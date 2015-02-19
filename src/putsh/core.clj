(ns putsh.core
  (:gen-class)
  (:require [clojure.string :as str])
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

(defn putsh-formatter
  "A log formatter."
  [{ :keys [level throwable message timestamp hostname ns] }]
  (format "[%s] %s%s"
          timestamp
          (or message "")
          (or (timbre/stacktrace throwable "\n" ) "")))

(defn lb-http-request
  "Make a request to the fake load balancer.

This is test code to allow us to run all our tests internally.

`address' is the http address to talk to.

`status', `headers' and `body-regex' are values to use to do
assertions on. If present the assertions are performed."
  [address &{ :keys [data
                     method
                     status-assert
                     headers-assert
                     body-regex-assert]}]
  (let [response @(if (= method :post)
                    (do
                      (info "the data is: " data)
                      (http-client/post
                       address
                       { :method :post :form-params data }))
                    ;; Else get
                    (http-client/get address))
        { :keys [status headers body] } response]
    #_()
    (info (format "lb-request[%s][status]: %s" address status))
    (info (format "lb-request[%s][headers]: %s" address headers))
    (info (format "lb-request[%s][body]: %s" address body))
    (do
      (when status-assert
        (assert (== status-assert status)))
      (when headers-assert
        (doall (map (fn [[k v]] (when (headers k) (assert (= (headers k) v))))
                    headers-assert)))
      (when body-regex-assert
        (assert (re-matches body-regex-assert body))))))

(defn appserv-handler
  "Handle requests from putsh as if we are an app server.

This is test code. It fakes a real app server so we can do all our
tests of putsh flow internally." [req]
  (http-server/with-channel req channel
    (http-server/on-close channel (fn [status] (warn "appserv close - " status)))
    (http-server/on-receive
     channel (fn [data] (warn "ooer! data from an appserv con: " data)))
    (let [response { :status 200
                    :headers { "content-type" "text/html"
                               "server" "fake-appserver-0.0.1" }
                    :body (str "<h1>my fake appserver!</h1>"
                               (if (req :body)
                                 (format "<div>%S</div>" (slurp (req :body)))
                                 "")) }]
      (http-server/send! channel response))))

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

(defn lb-server
  "Handle requests from the load balancer.

Requests are sent over the first free putsh websocket.  We wrap the
initial HTTP request up in a JSON structure and send it over the
channel established by `putsh-server'." [request]
  (http-server/with-channel request channel
    (http-server/on-close channel (fn [status] (debug "lb-server closed " status)))
    (http-server/on-receive
     channel (fn [data] ; get the putsh channel from @channels and send it the data
               (warn "lb-server data from a lb con: " data)))
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
load balancer."
  [request]
  (http-server/with-channel request channel
    (debug "putsh-server got a websocket")
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
           (error "putsh-server received without an lb channel")))))))

(defn putsh-make-ws [chan endpoint]
  (let [socket (promise)
        callback (fn [data]
                   (thread (>!! chan [@socket data])))]
    (deliver socket (ws/connect endpoint :on-receive callback))
    @socket))

(defn putsh-connector
  "The app server side of putsh.

`putsh-lb' which is the ws uri of the putsh server

`app-server-uri' which is the uri of the app server.

`number' - the number of connections to open to the putsh server.

Returns a promise which we might wait on."
  [ctrl app-server-uri putsh-lb number]
  (thread
   (let [ch (chan)
         sockets (doseq [n (range number)]  (putsh-make-ws ch putsh-lb))]
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
     (loop [[msg & args] (<!! ctrl)]
       (info "putsh-connector loop " msg)
       (when (case msg
               (:stop (do (doseq [socket sockets] (ws/close socket))
                          true)))
         (recur (<!! ctrl)))))
   :approx-ended))

(defn start-lb
  "Start the load balancer side of putsh.

Two listeners are started, the load balancer listener and the putsh
listener.

Returns a promise which will be set when end is received on the ctrl
channel."
  [ctrl & { :keys [lb-port putsh-port] :or { lb-port 8001 putsh-port 8000 } }]
  (thread
   (let [stops [(http-server/run-server putsh-server { :port putsh-port })
                (http-server/run-server lb-server { :port lb-port })]]
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
  "Start putsh." [& args]
  (timbre/set-config! [:fmt-output-fn] putsh-formatter)
  (timbre/set-config! [:timestamp-pattern] "yyyy-MM-dd HH:mm:ss")
  (timbre/set-level! :info)
  (let [mode :test
        app-server app-server-default
        lb-prox lb-server-default
        lb-ctrl (chan)
        ap-ctrl (chan)
        lb-chan (start-lb lb-ctrl)
        approx-chan (putsh-connector ap-ctrl app-server lb-prox 10)]
    ;; Tests
    (when (= mode :test)
      (let [fake-appserv-stop (http-server/run-server appserv-handler { :port 8003 })]
        (thread
         (Thread/sleep 1000)
         ;; Show that the direct request works

         ;; We actually need a ton of different requests here
         ;; - with and without
         ;;  parameters
         ;;  headers
         ;;  uploaded files
         (lb-http-request
          "http://localhost:8003/blah"
          :data { :a 1 :b 2 }
          :method :post
          :status-assert 200
          :headers-assert { :server "http-kit" }
          :body-regex-assert #"<h1>my fake.*")
         ;; Show that a putch request works
         (lb-http-request
          "http://localhost:8001/blah"
          :data { :a 1 :b 2 }
          :method :post))
        (Thread/sleep 2000)
        (fake-appserv-stop)
        (>!! lb-ctrl [:stop])
        (>!! ap-ctrl [:stop])))
    (let [[end & args] (alts!! [approx-chan lb-chan])]
      (debug "-main stopped "
             end 
             (<!! (case end
                    :lb-ended approx-chan
                    :approx-ended lb-chan))))
    (info "end")
    (System/exit 0)))

;; Ends
