(ns tservice.api.config
  (:require [tservice.lib.files :as files :refer [get-path-variable]]
            [tservice.lib.fs :as fs-lib]))

(def get-workdir files/get-workdir)

(def get-tservice-workdir files/get-tservice-workdir)


(defn add-env-to-path
  [plugin-name]
  (let [env-bin-path (fs-lib/join-paths (files/get-plugin-jar-dir)
                                        "envs" plugin-name "bin")
        path (get-path-variable)]
    (str env-bin-path ":" path)))
