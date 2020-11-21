(ns tservice.plugin
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [tservice.lib.fs :as fs]
            [clojure.java.io :as io]
            [tservice.config :refer [env]]))

(defn- clj-file?
  [file-path]
  (str/ends-with? (.getName file-path) ".clj"))

(defn- lib-file?
  [file-path]
  (and (clj-file? file-path)
       (str/ends-with? file-path (format "libs/%s" (.getName file-path)))))

(defn- wrapper-file?
  [file-path]
  (and (clj-file? file-path)
       (str/ends-with? file-path (format "wrappers/%s" (.getName file-path)))))

(defn- plugin?
  [file-path]
  (and (clj-file? file-path)
       (not (str/ends-with? file-path (format "libs/%s" (.getName file-path))))
       (not (str/ends-with? file-path (format "wrappers/%s" (.getName file-path))))))

(defonce ^:private repo
  (atom "/etc/vcftool"))

(defonce ^:private plugins-metadata
  (atom nil))

(defonce ^:private plugins
  (atom nil))

(defn get-repo
  []
  @repo)

(defn get-plugins-metadata
  []
  @plugins-metadata)

(defn get-plugins
  []
  @plugins)

(defn merge-plugins-metadata
  []
  (let [metadata (apply merge-with into (filter #(:routes %) @plugins-metadata))
        routes (:routes metadata)
        manifests (:manifests metadata)]
    (concat []
            (filter #(or (:route %) (:manifest %)) @plugins-metadata)
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

(defn- path->ns
  [path]
  (-> path
      (str/split #"\.")
      (first)
      (str/replace #"/" ".")
      (str/replace #"_" "-")))

(defn- list-plugins
  []
  (mapv (fn [file] (-> (str/replace (.getAbsolutePath file) 
                                    (re-pattern @repo) 
                                    "plugins")
                       (path->ns)))
        (filter #(and (.isFile %)
                      (plugin? %))
                (file-seq (io/file @repo)))))

(defn- setup-repo
  "Sets the location of the local clojure repository used
   by `load-plugins` or `load-plugin`"
  ([path] (reset! repo (fs/expand-home path)))
  ([] (setup-repo (fs/join-paths (:tservice-plugin-path env) "plugins"))))

(defn- setup-plugins
  []
  (reset! plugins (list-plugins)))

(defn- setup-plugins-metadata
  [metadata]
  (reset! plugins-metadata metadata))

(defn- load-plugin-metadata
  "TODO: Need to check metadata format?"
  [name]
  (let [metadata (find-var (symbol (str name "/" "metadata")))]
    (when metadata
      (deref metadata))))

(defn- load-plugins-metadata
  []
  (println @repo @plugins)
  (doseq [plugin @plugins]
    (let [metadata (load-plugin-metadata plugin)]
      (if metadata
        (setup-plugins-metadata (cons metadata @plugins-metadata))
        (log/warn (format "%s is not a valid plugin: not found metadata" plugin))))))

(defn- match-name?
  [name file-path]
  (and
   (clj-file? file-path)
   (= (format "%s.clj" name) (.getName file-path))))

(defn- load-libs
  "Load library files for wrappers or plugins."
  ([] (load-libs lib-file?))
  ([filter-fn]
   (let [path (fs/join-paths @repo "libs")]
     (if (fs/directory? path)
       (run! #(load-file (.getAbsolutePath %))
             (filter
              filter-fn
              (rest (file-seq (java.io.File. path)))))
       (throw (Exception. (format "No such path: %s" path)))))))

(defn- load-wrappers
  "Load wrapper files for plugins."
  ([] (load-wrappers wrapper-file?))
  ([filter-fn]
   (let [path (fs/join-paths @repo "wrappers")]
     (if (fs/directory? path)
       (run! #(load-file (.getAbsolutePath %))
             (filter
              filter-fn
              (rest (file-seq (java.io.File. path)))))
       (throw (Exception. (format "No such path: %s" path)))))))

(defn- load-plugins
  "Load plugins, echo plugin is an event-handler file that contains metadata and events-init function"
  ([] (load-plugins plugin?))
  ([filter-fn]
   (if (or (fs/directory? @repo) (fs/regular-file? @repo))
     (run! #(load-file (.getAbsolutePath %))
           (filter
            filter-fn
            (rest (file-seq (java.io.File. @repo)))))
     (throw (Exception. (format "No such path: %s" @repo))))))

(defn setup
  []
  (setup-repo)
  (setup-plugins)
  (load-libs)               ; Must load all libs before you load wrappers.
  (load-wrappers)           ; Must load all wrappers before you load plugins.
  (load-plugins)            ; Must load all plugins before you want to get metadata from plugin
  (load-plugins-metadata))

(defn load-plugin
  [name]
  (load-plugins (partial match-name? name)))

(defn load-init-fn
  [name]
  (find-var (symbol (str name "/" "events-init"))))

(defn start-plugins!
  []
  (when (or (nil? @plugins) (nil? @plugins-metadata))
    (setup)))

(defn stop-plugins!
  []
  (when (not-any? nil? [@repo @plugins @plugins-metadata])
    (reset! repo "/etc/vcftool")
    (reset! plugins nil)
    (reset! plugins-metadata nil)))