(defproject cranker "0.1.0-SNAPSHOT"
  :description "Connect HTTP in reverse to scale."
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [com.taoensso/timbre "3.3.1"]
                 ;;[org.clojure/tools.logging "0.2.6"]
                 [stylefruits/gniazdo "0.3.0"]
                 [org.clojure/data.json "0.2.5"]
                 [http-kit "2.1.19"]]
  ;;:main ^:skip-aot cranker.core
  :main cranker.core
  :target-path "target/%s"
  :aot :all
  :profiles {:uberjar {:aot :all} })
