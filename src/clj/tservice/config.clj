(ns tservice.config
  (:require
   [cprop.core :refer [load-config]]
   [cprop.source :as source]
   [tservice.lib.fs :as fs]
   [mount.core :refer [args defstate]]))

(def ^:private app-defaults
  "Global application defaults"
  {:tservice-run-mode            "prod"})

(defstate env
  :start
  (load-config
   :merge
   [app-defaults                  ; Priority Lowest
    (args)
    (source/from-system-props)
    (source/from-env)]))          ; Priority Highest

(def ^Boolean is-dev?  "Are we running in `dev` mode (i.e. in a REPL or via `lein ring server`)?" (= "dev"  (:tservice-run-mode env)))
(def ^Boolean is-prod? "Are we running in `prod` mode (i.e. from a JAR)?"                         (= "prod" (:tservice-run-mode env)))
(def ^Boolean is-test? "Are we running in `test` mode (i.e. via `lein test`)?"                    (= "test" (:tservice-run-mode env)))

(defn get-workdir
  []
  (fs/expand-home (get-in env [:tservice-workdir])))
