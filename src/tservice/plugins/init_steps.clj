(ns tservice.plugins.init-steps
  "Logic for performing the `init-steps` listed in a TService plugin's manifest. For plugins that specify that we
  should `lazy-load`, these steps are lazily performed the first time non-trivial plugin methods (such as connecting
  to a Database) are called; for all other TService plugins these are perfomed during launch.
  The entire list of possible init steps is below, as impls for the `do-init-step!` multimethod."
  (:require [clojure.tools.logging :as log]
            [tservice.plugins.classloader :as classloader]
            [tservice.plugins.plugin-proxy :as plugin-proxy]
            [tservice.util :as u]))

(defmulti ^:private do-init-step!
  "Perform a plugin init step. Steps are listed in `init:` in the plugin manifest; impls for each step are found below
  by dispatching off the value of `step:` for each step. Other properties specified for that step are passed as a map."
  {:arglists '([m])}
  (comp keyword :step))

(defmethod do-init-step! :load-namespace [{nmspace :namespace}]
  (log/debug (u/format-color 'blue (format "Loading plugin namespace %s..." nmspace)))
  (classloader/require (symbol nmspace)))

(defmethod do-init-step! :register-plugin [{entrypoint :entrypoint}]
  (plugin-proxy/load-and-register-plugin-metadata! entrypoint))

(defmethod do-init-step! :init-event [{entrypoint :entrypoint}]
  (plugin-proxy/init-event! entrypoint))

(defn do-init-steps!
  "Perform the initialization steps for a TService plugin as specified under `init:` in its plugin
  manifest (`tservice-plugin.yaml`) by calling `do-init-step!` for each step."
  [init-steps]
  (doseq [step init-steps]
    (do-init-step! step)))