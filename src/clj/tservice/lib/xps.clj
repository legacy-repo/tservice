(ns tservice.lib.xps
  (:require [clojure.java.shell :as shell]
            [clojure.tools.logging :as log]
            [clojure.string :as str]))

;; Need to install libgxps tools
;; For Mac, `brew install libgxps`, more details in https://formulae.brew.sh/formula/libgxps
;; For Linux, `yum install libgxps-tools`

(defn- convertor
  "Common convertor dispather.
   Bug fixes: sh is implemented using Clojure futures.  See examples for 'future'
   for discussion of an undesirable 1-minute wait that can occur before
   your standalone Clojure program exits if you do not use shutdown-agents.
   More details: https://clojuredocs.org/clojure.java.shell/sh#example-542692d6c026201cdc3270e7

   Fix Bug: 
     MUST NOT use shutdown-agents, it will cause the problem which the FutureTask was not completed but agent is down.'
  "
  [cmd from to]
  (try
    (shell/sh cmd from to)
    (catch Exception e
      (log/debug (str "Exception: " (.toString e))))))

(defn xps2png
  "Convert XPS to PDF."
  [from to]
  (convertor "xpstopng" from to))

(defn xps2pdf
  "Convert XPS to PNG."
  [from to]
  (convertor "xpstopdf" from to))

(defn xps2jpeg
  "Convert XPS to JPEG."
  [from to]
  (convertor "xpstojpeg" from to))

(defn xps2svg
  "Convert XPS to SVG."
  [from to]
  (convertor "xpstosvg" from to))

(def command-map
  {:pdf xps2pdf
   :svg xps2svg
   :jpeg xps2jpeg
   :png xps2png})

(defn command
  "Dispatch to different function."
  [{:keys [input output type]}]
  (println "Convert XPS to" (str/upper-case type) ":" input)
  (((keyword type) command-map) input output))