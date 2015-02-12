(defproject putsh "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/tools.logging "0.2.6"]
                 [stylefruits/gniazdo "0.3.0"]
                 [org.clojure/data.json "0.2.5"]
                 [http-kit "2.1.6"]]
  :main ^:skip-aot putsh.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
