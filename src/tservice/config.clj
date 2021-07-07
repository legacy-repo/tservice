(ns tservice.config
  (:require
   [cprop.core :refer [load-config]]
   [cprop.source :as source]
   [mount.core :refer [args defstate]]))

(defonce ^:private run-mode*
  (atom nil))

(defn setup-run-mode!
  [env]
  (reset! run-mode* (:tservice-run-mode env)))

(defn is-test?
  "Are we running in `test` mode (i.e. via `lein test`)?"
  []
  (= "test" @run-mode*))

(defn is-dev?
  "Are we running in `dev` mode (i.e. in a REPL or via `lein ring server`)?"
  []
  (= "dev" @run-mode*))

(defn is-prod?
  "Are we running in `prod` mode (i.e. from a JAR)?"
  []
  (= "prod" @run-mode*))

(defstate env
  :start
  (load-config
   :merge
   [(args)
    (source/from-system-props)
    (source/from-env)]))
