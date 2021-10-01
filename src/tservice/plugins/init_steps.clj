(ns tservice.plugins.init-steps
  "Logic for performing the `init-steps` listed in a TService plugin's manifest. For plugins that specify that we
  should `lazy-load`, these steps are lazily performed the first time non-trivial plugin methods (such as connecting
  to a Database) are called; for all other TService plugins these are perfomed during launch.
  The entire list of possible init steps is below, as impls for the `do-init-step!` multimethod."
  (:require [clojure.tools.logging :as log]
            [tservice.plugins.classloader :as classloader]
            [tservice.plugins.plugin-proxy :as plugin-proxy]
            [tservice.util :as u]
            [tservice.lib.files :as files]
            [clojure.string :as clj-str]))

(defmulti ^:private do-init-step!
  "Perform a plugin init step. Steps are listed in `init:` in the plugin manifest; impls for each step are found below
  by dispatching off the value of `step:` for each step. Other properties specified for that step are passed as a map."
  {:arglists '([m])}
  (comp keyword :step))

(defmethod do-init-step! :unpack-env [{envname :envname envtype :envtype fileext :fileext postunpack :postunpack context :context}]
  (let [{:keys [jar-path env-dest-dir config-dir data-dir]} context
        ;; Compatible with v0.5.8 older interface (Only require envname, not fileext.)
        envname-ext (clj-str/split envname #"\.")
        fileext (or fileext (clj-str/join "." (rest envname-ext)))  ;; such as "tar.gz", "tgz" or ""
        envname (first envname-ext)
        post-unpack-cmd (when postunpack
                          (files/render-template postunpack
                                                 {:ENV_DEST_DIR env-dest-dir
                                                  :CONFIG_DIR config-dir
                                                  :DATA_DIR data-dir
                                                  :ENV_NAME envname}))
        component (if (empty? fileext) envname (format "%s.%s" envname fileext))]
    (log/info (u/format-color 'blue (format "Unpack the conda environment into %s/%s..."
                                            env-dest-dir envname)))
    (when jar-path
      ;; Archive file or directory
      (cond
        (= envtype "environment") 
        (files/extract-env-from-archive jar-path component env-dest-dir)

        (= envtype "configuration") 
        (files/extract-env-from-archive jar-path component config-dir)

        (= envtype "data") 
        (files/extract-env-from-archive jar-path component data-dir))
      (when post-unpack-cmd
        (log/info (u/format-color 'blue (format "Run post-unpack-cmd: %s" post-unpack-cmd)))
        (log/info (files/call-command! post-unpack-cmd))))))

(defmethod do-init-step! :load-namespace [{nmspace :namespace}]
  (log/info (u/format-color 'blue (format "Loading plugin namespace %s..." nmspace)))
  (classloader/require (symbol nmspace)))

(defmethod do-init-step! :register-plugin [{entrypoint :entrypoint context :context}]
  (plugin-proxy/load-and-register-plugin-metadata! entrypoint (:plugin-info context)))

(defmethod do-init-step! :init-event [{entrypoint :entrypoint}]
  (plugin-proxy/init-event! entrypoint))

(defn do-init-steps!
  "Perform the initialization steps for a TService plugin as specified under `init:` in its plugin
  manifest (`tservice-plugin.yaml`) by calling `do-init-step!` for each step."
  [init-steps context]
  (doseq [step init-steps]
    (do-init-step! (assoc step :context context))))