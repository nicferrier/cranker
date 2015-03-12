(ns cranker.core
  "cranker - connection HTTP in reverse for better scaling."
  (:gen-class)
  (:require [clojure.test :refer :all])
  (:require [clojure.string :as str])
  (:require [clojure.pprint :as pp])
  (:require
   [clojure.core.async
    :refer [>! <! >!! <!! go go-loop chan close!  alts! alts!! timeout thread]])
  ;;(:require [clojure.tools.logging :as log])
  (:require [taoensso.timbre :as timbre :refer (debug log info warn error fatal)])
  (:require [org.httpkit.client :as http-client])
  (:require [gniazdo.core :as ws])
  (:require [clojure.data.json :as json])
  (:require [clojure.walk])
  (:require [org.httpkit.server :as http-server])
  (:import [java.io File FileOutputStream]))

(defn cranker-formatter
  "A log formatter."
  [{ :keys [level throwable message timestamp hostname ns] }]
  (format "[%s] %s%s"
          timestamp
          (or message "")
          (or (timbre/stacktrace throwable "\n" ) "")))

(defn trunc-str [s c]
  (if (> (count s) c)
    (str (subs s 0 c) "...")
    s))

(defn lb-http-request
  "Make a request to the fake load balancer.

This is test code to allow us to run all our tests internally.

`address' is the http address to talk to. 

`query-params' is a query string.

`form-data' is POST form data to pass, mutually exclusive with
`multipart'???

`multipart' is a file to pass.


`status', `headers' and `body-regex' are values to use to do
assertions on. If present the assertions are performed."
  [address &{ :keys [query-params
                     form-params
                     multipart
                     id
                     method
                     status-assert
                     headers-assert
                     body-regex-assert]}]
  (let [response @(if (= method :post)
                    (do
                      (when form-params
                        (info id "lb-http-request the form-params are: " form-params))
                      (when multipart
                        (info id (format "lb-http-request the multipart is: %s" multipart)))
                      (http-client/request
                       {:url address
                        :method :post
                        :query-params query-params
                        :form-params form-params
                        :multipart multipart } nil))
                    ;; Else get
                    (http-client/get address { :query-params query-params }))
        { :keys [status headers body] } response]
    (info id (format "lb-http-request[%s][status]: %s" address status))
    (info id (format "lb-http-request[%s][headers]: %s" address headers))
    (info id (format "lb-http-request[%s][body]: %s" address (trunc-str body 40)))
    (do
      (when status-assert
        (assert (== status-assert status)))
      (when headers-assert
        (doall (map (fn [[k v]]
                      (info id "headers [" k "] == " v " -> " (pp/cl-format nil "~s" (headers k)))
                      (when (headers k) (assert (= (headers k) v))))
                    headers-assert)))
      (when body-regex-assert
        (debug (pp/cl-format nil "~s" body-regex-assert))
        (assert (re-matches body-regex-assert body))))))

(defn appserv-handler
  "Handle requests from cranker as if we are an app server.

This is test code. It fakes a real app server so we can do all our
tests of cranker flow internally." [req]
  (http-server/with-channel req channel
    (http-server/on-close channel (fn [status] (debug "appserv close - " status)))
    (http-server/on-receive
     channel (fn [data] (warn "ooer! data from an appserv con: " data)))
    (info "appserv-handler request -> " req)
    (let [response { :status 200
                    :headers { "content-type" "text/html"
                               "server" "fake-appserver-0.0.1" }
                    :body (str "<h1>my fake appserver!</h1>"
                               (if (req :body)
                                 (format "<div>%s</div>" (slurp (req :body)))
                                 "")) }]
      (http-server/send! channel response))))

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
         sockets (doseq [n (range number)]  (cranker-make-ws ch cranker-lb))]
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

(defn ^File gen-tempfile
  "Generate a tempfile, the file will be deleted before jvm shutdown."
  ([size extension]
     (let [string-80k
           (fn []
             (apply
              str
              (map char
                   (take (* 8 1024)
                         (apply concat (repeat (range (int \a) (int \z))))))))
           const-string (let [tmp (string-80k)]
                          (apply str (repeat 1024 tmp)))
           tmp (doto (File/createTempFile "tmp_" extension)
                 (.deleteOnExit))]
       (with-open [w (FileOutputStream. tmp)]
         (.write w ^bytes (.getBytes (subs const-string 0 size))))
       tmp)))

(defn test-lb [lb-ctrl ap-ctrl]
  (let [fake-appserv-stop (http-server/run-server appserv-handler { :port 8003 })
        tempfile (gen-tempfile 5000 ".jpg")]
    (thread
     (Thread/sleep 1000)
     ;; Multipart request
     (lb-http-request
      "http://localhost:8003/blah"
      :id "straight#1"
      :form-params { :a 1 :b 2 }
      ;; Looks to me like multipart is not supported by http-kit/client
      ;; check the http-client code
      ;; FIXME - looks like it's just out version!!!
      :multipart [{ :name "image" :content tempfile}]
      :method :post
      :status-assert 200
      :headers-assert { :server "fake-appserver-0.0.1,http-kit" }
      :body-regex-assert #"<h1>my fake.*<div>-+HttpKitFormBoundary.*\r
Content-Disposition: form-data; name=\"image\"\r
.*\r
.*\r
.*\r
.*\r
</div>")
     ;; Show that the direct request works
     (lb-http-request
      "http://localhost:8003/blah"
      :id "straight#2"
      :form-params { :a 1 :b 2 }
      :method :post
      :status-assert 200
      :headers-assert { :server "fake-appserver-0.0.1,http-kit" }
      :body-regex-assert #"<h1>my fake.*<div>b=2&a=1</div>")
     ;; Show a cranker request works - we actually need a ton of
     ;; different requests here
     
     ;; - with and without
     ;;  parameters
     ;;  headers
     ;;  uploaded files
     (lb-http-request
      "http://localhost:8001/blah"
      :id "cranker#1"
      :status-assert 200
      :headers-assert { :server "fake-appserver-0.0.1,http-kit,http-kit" }
      :body-regex-assert #"<h1>my fake.*</h1>$")

     ;; Show that a cranker request with data works
     (lb-http-request
      "http://localhost:8001/blah"
      :id "cranker#2"
      :form-params { "a" 1 "b" 2 }
      :method :post
      :status-assert 200
      :headers-assert { :server "fake-appserver-0.0.1,http-kit,http-kit" }
      :body-regex-assert #"<h1>my fake.*</h1><div>a=1&b=2</div>$")

     ;; And now cranker with a file...
     (lb-http-request
      "http://localhost:8001/blah"
      :id "cranker#3"
      :multipart [{ :name "image" :content tempfile}]
      :form-params { "a" 1 "b" 2 }
      :method :post
      :status-assert 200
      :headers-assert { :server "fake-appserver-0.0.1,http-kit,http-kit" }
      :body-regex-assert #"<h1>my fake.*<div>-+HttpKitFormBoundary.*\r
Content-Disposition: form-data; name=\"image\"\r
.*\r
.*\r
.*\r
.*\r
</div>"))
    (Thread/sleep 2000)
    (fake-appserv-stop)
    (>!! lb-ctrl [:stop])
    (>!! ap-ctrl [:stop])))

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
