(ns cranker.testrig
  "Test rig for cranker - reversing the polairty of your HTTP and Websockets."
  (:require [cranker.utils :refer :all])
  (:require [clojure.string :as str])
  (:require [clojure.core.async :refer [>!! thread]])
  (:require [clojure.pprint :as pp])
  (:require [taoensso.timbre :as timbre :refer (debug log info warn error fatal)])
  (:require [org.httpkit.client :as http-client])
  (:require [gniazdo.core :as ws]) ; will need for socket connections
  (:require [clojure.walk])
  (:require [org.httpkit.server :as http-server])
  (:import [java.io File FileOutputStream]))

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
                    (http-client/request
                     {:url address
                      :method :post
                      :query-params query-params
                      :form-params form-params
                      :multipart multipart } nil)
                    ;; Else get
                    (http-client/get address { :query-params query-params }))
        { :keys [status headers body] } response]
    (when form-params (info id "lb-http-request the form-params are: " form-params))
    (when multipart (info id (format "lb-http-request the multipart is: %s" multipart)))
    (info id (format "lb-http-request[%s][status]: %s" address status))
    (info id (format "lb-http-request[%s][headers]: %s" address headers))
    (info id (format "lb-http-request[%s][body]: %s" address (trunc-str body 40)))
    (do
      (when status-assert
        (assert (== status-assert status)))
      (when headers-assert
        (doall
         (map (fn [[k v]]
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

;; Ends
