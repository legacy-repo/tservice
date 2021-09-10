(ns tservice.plugins.plugin-proxy
  "Plugin proxy used for plugins added at runtime. Load metadata from each plugin and register into the private variable."
  (:require [clojure.tools.logging :as log]
            [tservice.util :as u]))

(defonce ^:private plugins-metadata
  (atom nil))

(defn get-plugins-metadata
  []
  @plugins-metadata)

(defn- load-plugin-metadata
  "TODO: Need to check metadata format?"
  [^String entrypoint]
  ;; tservice.plugins.<plugin-name>/metadata
  (let [metadata (find-var (symbol entrypoint))]
    (when metadata
      (deref metadata))))

(defn- setup-plugins-metadata
  [metadata]
  (reset! plugins-metadata metadata))

(defn init-event!
  [^String entrypoint]
  (when-let [init-fn (find-var (symbol entrypoint))]
    (log/info "Starting events listener:" (u/format-color 'blue entrypoint) "👂")
    (init-fn)))

(defn load-and-register-plugin-metadata!
  [^String entrypoint plugin-info]
  (let [m (load-plugin-metadata entrypoint)
        metadata {:route (:route m)
                  :routes (:routes m)
                  :manifest (or (:manifest m) plugin-info)}]
    (log/debug (format "%s's metadata %s" entrypoint m))
    (if (and (nil? (:route metadata)) (nil? (:routes metadata)))
      (log/warn (format "%s is not a valid plugin, not found metadata (%s)" entrypoint m))
      (setup-plugins-metadata (cons metadata @plugins-metadata)))))
