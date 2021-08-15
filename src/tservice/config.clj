(ns tservice.config
  (:require
   [clojure.spec.alpha :as s]
   [expound.alpha :refer [expound-str]]
   [cprop.core :refer [load-config]]
   [cprop.source :as source]
   [clojure.string :as clj-str]
   [clojure.tools.logging :as log]
   [clojure.java.io :refer [file]]
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
  :start (load-config :merge [(args)
                              (source/from-system-props)
                              (source/from-env)]))

#_:clj-kondo/ignore
(defn which-database
  [database-url]
  {:pre [(s/valid? ::database-url database-url)]
   :post [(s/valid? ::database %)]}
  (let [database (re-find (re-matcher #"jdbc:sqlite|jdbc:postgresql|jdbc:h2"
                                      database-url))]
    (if database
      (clj-str/replace database #"^jdbc:" "")
      "sqlite")))

(defn get-migration-config
  [env]
  (merge {:migration-dir (str "migrations/" (which-database (:database-url env)))}
         (select-keys env [:database-url :init-script])))

;; -------------------------------- Config Spec --------------------------------
(defn exists?
  [filepath]
  (.exists (file filepath)))

;; More details on https://stackoverflow.com/q/13621307
(s/def ::port (s/int-in 1024 65535))

(s/def ::nrepl-port (s/int-in 1024 65535))

(s/def ::database-url (s/and string? #(some? (re-matches #"jdbc:(sqlite|postgresql|h2):.*" %))))

(s/def ::database #{"postgresql" "sqlite" "h2"})

;; More details on https://stackoverflow.com/a/537876
(s/def ::external-bin (s/nilable #(some? (re-matches #"([^\\0]+:)*" %))))

(s/def ::tservice-workdir (s/and string? exists?))

(s/def ::tservice-plugin-path (s/and string? exists?))

(s/def ::tservice-run-mode #{"test" "dev" "prod"})

;; Service
(s/def ::fs-service #{"minio" "oss" "s3"})

(s/def ::fs-endpoint #(some? (re-matches #"https?:\/\/.*" %)))

(s/def ::fs-access-key string?)

(s/def ::fs-secret-key string?)

(s/def ::fs-rootdir (s/nilable (s/and string? exists?)))

(s/def ::service (s/keys :req-un [::fs-service ::fs-endpoint ::fs-access-key ::fs-secret-key]
                         :opt-un [::fs-rootdir]))

(s/def ::fs-services (s/coll-of ::service))

(s/def ::default-fs-service #{"minio" "oss" "s3"})

(s/def ::cron string?)

(s/def ::cron-map (s/keys :req-un [::cron]))

(s/def ::tasks (s/map-of keyword? ::cron-map))

(s/def ::config (s/keys :req-un [::port ::database-url ::tservice-workdir
                                 ::tservice-plugin-path ::tservice-run-mode
                                 ::fs-services ::default-fs-service]
                        :opt-un [::nrepl-port ::tasks]))

(defn check-config
  [env]
  (let [config (select-keys env [:port :nrepl-port :database-url :tservice-workdir
                                 :tservice-plugin-path :tservice-run-mode :fs-services
                                 :default-fs-service :tasks])]
    (when (not (s/valid? ::config config))
      (log/error "Configuration errors:\n" (expound-str ::config config))
      (System/exit 1))))
