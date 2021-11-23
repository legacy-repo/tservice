(ns tservice.api.config
  (:require [tservice.lib.files :as files :refer [get-path-variable get-relative-filepath]]
            [tservice.lib.fs :as fs-lib]
            [tservice.config :as config]))

(def get-workdir files/get-workdir)

(def get-tservice-workdir files/get-tservice-workdir)


(defn add-env-to-path
  [plugin-name]
  (let [env-bin-path (fs-lib/join-paths (files/get-plugin-jar-dir)
                                        "envs" plugin-name "bin")
        path (get-path-variable)]
    (str env-bin-path ":" path)))

(def get-minio-prefix config/get-minio-prefix)

(defn get-minio-link
  [filepath]
  (str (get-minio-prefix) (get-relative-filepath filepath :filemode false)))
