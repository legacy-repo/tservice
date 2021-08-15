(ns tservice.handler
  (:require
   [tservice.middleware :as middleware]
   [tservice.routes.services :refer [service-routes]]
   [reitit.ring :as ring]
   [ring.middleware.content-type :refer [wrap-content-type]]
   [ring.middleware.webjars :refer [wrap-webjars]]
   [tservice.env :refer [defaults]]
   [mount.core :as mount]
   [clojure.tools.logging :as log]
   [reitit.spec :as rs]
   [reitit.dev.pretty :as pretty]
   [tservice.plugin :as plugin]
   [tservice.plugin-jars :as plugin-jars]))

(mount/defstate init-app
  :start ((or (:init defaults) (fn [])))
  :stop  ((or (:stop defaults) (fn []))))

(defn init
  "init will be called once when
   app is deployed as a servlet on
   an app server such as Tomcat
   put any initialization code here"
  []
  (doseq [component (:started (mount/start))]
    (log/info component "started")))

(defn destroy
  "destroy will be called when your application
   shuts down, put any clean up code here"
  []
  (doseq [component (:stopped (mount/stop))]
    (log/info component "stopped"))
  (shutdown-agents)
  (log/info "tservice has shut down!"))

(mount/defstate app-routes
  :start
  (ring/ring-handler
   (ring/router
    [["/" {:get
           {:handler (constantly {:status 301 :headers {"Location" "/api/api-docs/index.html"}})}}]
     ; TODO: Duplicated routes?
     (concat (service-routes) (plugin/get-routes) (plugin-jars/get-routes))]

    {:validate  rs/validate
     :exception pretty/exception})

   (ring/routes
    (wrap-content-type (wrap-webjars (constantly nil)))
    (ring/create-default-handler))))

(defn app []
  (middleware/wrap-base #'app-routes))
