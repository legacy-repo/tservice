(ns tservice.plugin-jars
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [tservice.lib.files :as files :refer [get-plugin-jar-dir]]
            [tservice.plugins.classloader :as classloader]
            [tservice.plugins.initialize :as initialize]
            [tservice.plugins.plugin-proxy :refer [get-plugins-metadata]]
            [yaml.core :as yaml])
  (:import [java.nio.file Files Path]))

;; logic for determining plugins dir -- see below
(defonce ^:private plugins-dir*
  (delay
   (let [filename (get-plugin-jar-dir)]
     (try
        ;; attempt to create <current-dir>/plugin-jars if it doesn't already exist. Check that the directory is readable.
       (let [path (files/get-path filename)]
         (files/create-dir-if-not-exists! path)
         (assert (Files/isWritable path)
                 (str "TService does not have permissions to write to plugins directory " filename))
         path)
        ;; If we couldn't create the directory, or the directory is not writable, fall back to a temporary directory
        ;; rather than failing to launch entirely. Log instructions for what should be done to fix the problem.
       (catch Throwable e
         (log/warn
          e
          "TService cannot use the plugins directory " filename
          "\n"
          "Please make sure the directory exists and that TService has permission to write to it."
          "You can change the directory TService uses for modules by setting tservice-plugin-path variable in the edn file."
          "Falling back to a temporary directory for now.")
          ;; Check whether the fallback temporary directory is writable. If it's not, there's no way for us to
          ;; gracefully proceed here. Throw an Exception detailing the critical issues.
         (let [path (files/get-path (System/getProperty "java.io.tmpdir"))]
           (assert (Files/isWritable path)
                   "TService cannot write to temporary directory. Please set tservice-plugin-path to a writable directory and restart Tservice.")
           path))))))

;; Actual logic is wrapped in a delay rather than a normal function so we don't log the error messages more than once
;; in cases where we have to fall back to the system temporary directory
(defn- plugins-dir
  "Get a `Path` to the TService plugins directory, creating it if needed. If it cannot be created for one reason or
  another, or if we do not have write permissions for it, use a temporary directory instead."
  ^Path []
  @plugins-dir*)

(defn- extract-system-modules! []
  (println "Modules: " (io/resource "modules"))
  (when (io/resource "modules")
    (let [plugins-path (plugins-dir)]
      (files/with-open-path-to-resource [modules-path "modules"]
        (files/copy-files! modules-path plugins-path)))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          loading/initializing plugins                                          |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- add-to-classpath! [^Path jar-path]
  (classloader/add-url-to-classpath! (-> jar-path .toUri .toURL)))

(defn- plugin-info [^Path jar-path]
  (some-> (files/slurp-file-from-archive jar-path "tservice-plugin.yaml")
          yaml/parse-string))

(defn- init-plugin-with-info!
  "Initiaize plugin using parsed info from a plugin maifest. Returns truthy if plugin was successfully initialized;
  falsey otherwise."
  [info]
  (initialize/init-plugin-with-info! info))

(defn- init-plugin!
  "Init plugin JAR file; returns truthy if plugin initialization was successful."
  [^Path jar-path]
  (if-let [info (plugin-info jar-path)]
    ;; for plugins that include a tservice-plugin.yaml manifest run the normal init steps, don't add to classpath yet
    (init-plugin-with-info! (assoc info :add-to-classpath! #(add-to-classpath! jar-path) :jar-path jar-path))
    ;; for all other JARs just add to classpath and call it a day
    (add-to-classpath! jar-path)))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                 load-plugins!                                                  |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- plugins-paths []
  (for [^Path path (files/files-seq (plugins-dir))
        :let [file-name (.getFileName path)]
        :when      (and (files/regular-file? path)
                        (files/readable? path)
                        (or (str/ends-with? file-name "tservice-plugin.jar")
                            (str/ends-with? file-name "common-plugin.jar")))]
    path))

(defn- load-local-plugin-manifest! [^Path path]
  (some-> (slurp (str path)) yaml.core/parse-string initialize/init-plugin-with-info!))

(defn- load-local-plugin-manifests!
  "Load local plugin manifest files when running in dev or test mode, to simulate what would happen when loading those
  same plugins from the uberjar. This is needed because some plugin manifests define plugin methods and the like that
  aren't defined elsewhere."
  []
    ;; TODO - this should probably do an actual search in case we ever add any additional directories
  (log/info "Loading local plugins from " (files/files-seq (files/get-path "modules/plugins/")))
  (doseq [path  (files/files-seq (files/get-path "modules/plugins/"))
          :let  [manifest-path (files/get-path (str path) "/resources/tservice-plugin.yaml")]
          :when (files/exists? manifest-path)]
    (log/info (format "Loading local plugin manifest at %s" (str manifest-path)))
    (load-local-plugin-manifest! manifest-path)))

(defn- has-manifest? ^Boolean [^Path path]
  (boolean (files/file-exists-in-archive? path "tservice-plugin.yaml")))

(defn- init-plugins! [paths]
  ;; sort paths so that ones that correspond to JARs with no plugin manifest (e.g. a dependency like the Oracle JDBC
  ;; driver `ojdbc8.jar`) always get initialized (i.e., added to the classpath) first; that way, TService plugins that
  ;; depend on them (such as Oracle) can be initialized the first time we see them.
  ;;
  ;; In Clojure world at least `false` < `true` so we can use `sort-by` to get non-Tservice-plugin JARs in front
  (doseq [^Path path (sort-by has-manifest? paths)]
    (try
      (init-plugin! path)
      (catch Throwable e
        (log/error e "Failed to initialize plugin " (.getFileName path))))))

(defn- load! []
  (log/info (format "Loading plugins in %s..." (str (plugins-dir))))
  (let [paths (plugins-paths)]
    (init-plugins! paths)))

(defonce ^:private load!* (delay (load!)))

(defn load-plugins!
  "Load TService plugins. The are JARs shipped as part of TService itself, under the `resources/modules` directory (the
  source for these JARs is under the `modules` directory); and others manually added by users to the TService plugins
  directory, which defaults to `./plugins`.
  When loading plugins, TService performs the following steps:
  *  TService creates the plugins directory if it does not already exist.
  *  Any plugins that are shipped as part of TService itself are extracted from the TService uberjar (or `resources`
     directory when running with `lein`) into the plugins directory.
  *  Each JAR in the plugins directory that *does not* include a TService plugin manifest is added to the classpath.
  *  For JARs that include a TService plugin manifest (a `tservice-plugin.yaml` file), a lazy-loading TService plugin
     is registered; when the plugin is initialized (automatically, when certain methods are called) the JAR is added
     to the classpath and the plugin namespace is loaded
  This function will only perform loading steps the first time it is called — it is safe to call this function more
  than once."
  []
  @load!*)

(defn start-plugin-jars!
  []
  (when (some? (get-plugin-jar-dir))
    (load-plugins!)))

(defn stop-plugin-jars!
  []
  nil)

(defn- merge-plugins-metadata
  []
  (let [metadata (apply merge-with into (filter #(:routes %) (get-plugins-metadata)))
        routes (:routes metadata)
        manifests (:manifests metadata)]
    (concat []
            (filter #(or (:route %) (:manifest %)) (get-plugins-metadata))
            (map (fn [route] {:route route}) routes)
            (map (fn [manifest] {:manifest manifest}) manifests))))

(defn get-routes
  []
  (->> (merge-plugins-metadata)
       (map #(:route %))
       (filter some?)))

(defn get-manifest
  []
  (->> (merge-plugins-metadata)
       (map #(:manifest %))
       (filter some?)))
