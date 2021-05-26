(ns tservice.setup
  (:require [clj-filesystem.core :as clj-fs]
            [tservice.plugin :as plugin]
            [tservice.plugin-jars :as plugin-jars]
            [clojure.tools.logging :as log]
            [mount.core :as mount]
            [tservice.config :refer [env]]))

(defn connect-fs!
  []
  (doseq [service (:fs-services env)]
    (log/info (format "Connect %s service" (:fs-service service)))
    (if (= (:default-fs-service env) (:fs-service service))
      (clj-fs/setup-connection (:fs-service service)
                               (:fs-endpoint service)
                               (:fs-access-key service)
                               (:fs-secret-key service))
      (clj-fs/setup-connection (:fs-service service)
                               (:fs-endpoint service)
                               (:fs-access-key service)
                               (:fs-secret-key service)))))

(mount/defstate setup-plugin-jars
  :start
  (plugin-jars/start-plugin-jars!)
  :stop
  (plugin-jars/stop-plugin-jars!))

; Must mount plugin before event
(mount/defstate setup-plugin
  :start
  (plugin/start-plugins!)
  :stop
  (plugin/stop-plugins!))

(mount/defstate setup-fs
  :start
  (connect-fs!)
  :stop
  (comment "May be we need to add disconnect function"))
