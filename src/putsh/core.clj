(ns putsh.core
  (:gen-class)
  (:require
   [clojure.core.async
    :refer [>! <! >!! <!! go go-loop chan close!  alts! alts!! timeout thread]])
  (:require [org.httpkit.client :as http-client])
  (:require [gniazdo.core :as ws])
  (:require [clojure.data.json :as json])
  (:require [org.httpkit.server :as http-server]))

(def channels (ref {}))

(def http-keys [:remote-addr
                :headers 
                :server-port 
                :content-length
                :content-type
                :character-encoding
                :uri 
                :server-name
                :query-string
                :body 
                :scheme
                :request-method])

(defn lb
  "Handle requests from the load balancer." 
  [req]
  (http-server/with-channel req channel
    (http-server/on-close channel (fn [status] #_(println "lb - " status)))
    (http-server/on-receive
     channel (fn [data]
               (println "ooer! data from a lb con: " data)))
    (let [first-free (first (filter #(nil? (% 1)) @channels))
          putsh-chan (when first-free (first-free 0))]
      (when putsh-chan
        (let [req-keys (set (keys req))
              keys-used (set (clojure.set/intersection req-keys http-keys))
              req-keys-used (filter #(contains? keys-used (first %)) (seq req))
              req-map (into {} req-keys-used)] 
          (dosync (alter channels assoc putsh-chan channel))
          (http-server/send! putsh-chan (json/write-str req-map)))))))

(defn putsh
  "Handle requests from the other side of putsh." 
  [req]
  (http-server/with-channel req channel
    (dosync (alter channels assoc channel nil))
    (http-server/on-close
     channel (fn [status] (dosync (alter channels dissoc channel))))
    (http-server/on-receive
     channel
     (fn [data]
       (let [lb-channel (@channels channel)]
         (if lb-channel
           (let [response (json/read-str data :key-fn keyword)]
             (http-server/send! lb-channel response))
           ;; else
           (println "whoops! putsh-server received without an lb channel")))))))

;; this is actually the beginnings of the app server side of putsh
(defn putsh-connect []
  (let [ch (chan)
        socket (ws/connect
                "ws://localhost:8000"
                :on-receive (fn [data] (thread (>!! ch data))))]
    (go-loop
     [data (<! ch)] ;; data is the http request
     (let [response { :status 200 :body "<h1>hello!</h1>" }]
       (ws/send-msg socket (json/write-str response))
       (recur (<! ch))))
    (Thread/sleep 5000)
    (ws/close socket)))

(defn lb-http-request []
  (Thread/sleep 3000)
  (println "lb-request response: " @(http-client/get "http://localhost:8001/blah")))

(defn -main
  "Start the putsh load balancer side processes."
  [& args]
  (let [putsh-stop (http-server/run-server putsh { :port 8000 })
        lb-stop (http-server/run-server lb { :port 8001 })]
    (thread (putsh-connect))
    (thread (lb-http-request))
    (Thread/sleep 8000)
    (println "stopping")
    (lb-stop)
    (putsh-stop)))

;; Ends
