(ns cranker.utils
  "Utils for cranker - reversing the polairty of your HTTP and Websockets."
  (:require [taoensso.timbre :as timbre :refer (debug log info warn error fatal)]))

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

;;; utils.clj ends here
