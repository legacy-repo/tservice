(ns tservice.env
  (:require
    [selmer.parser :as parser]
    [clojure.tools.logging :as log]
    [tservice.dev-middleware :refer [wrap-dev]]))

(def defaults
  {:init
   (fn []
     (parser/cache-off!)
     (log/info "-=[tservice started successfully using the development profile]=-"))
   :stop
   (fn []
     (log/info "-=[tservice has shut down successfully]=-"))
   :middleware wrap-dev})
