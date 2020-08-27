(ns tservice.setup
  (:require [clj-filesystem.core :as clj-fs]
            [tservice.plugin :as plugin]
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