(ns tservice.version
  (:require [clojure.string])
  (:import [java.util Properties]))

;; More details on https://github.com/trptcolin/versioneer
(defn map-from-property-filepath 
  {:added "0.5.9"}
  [file]
  (try
    (let [file-reader (.. (Thread/currentThread)
                          (getContextClassLoader)
                          (getResourceAsStream file))
          props (Properties.)]
      (.load props file-reader)
      (into {} props))
    (catch Exception e nil)))

(defn get-properties-filename 
  {:added "0.5.9"}
  [group artifact]
  (str "META-INF/maven/" group "/" artifact "/pom.properties"))

(defn get-version
  "Attempts to get the project version from system properties (set when running
  Leiningen), or a properties file based on the group and artifact ids (in jars
  built by Leiningen), or a default version passed in.  Falls back to an empty
  string when no default is present."
  {:added "0.5.9"}
  ([group artifact]
   (get-version group artifact ""))
  ([group artifact default-version]
   (or (System/getProperty (str artifact ".version"))
       (-> (get-properties-filename group artifact)
           map-from-property-filepath
           (get "version"))
       default-version)))

(defn get-revision
  "Attempts to get the project source control revision from a properties file
  based on the group and artifact ids (in jars built by Leiningen), or a default
  revision passed in.  Falls back to an empty string when no default is
  present."
  {:added "0.5.9"}
  ([group artifact]
   (get-revision group artifact ""))
  ([group artifact default-revision]
   (or (-> (get-properties-filename group artifact)
           map-from-property-filepath
           (get "revision"))
       default-revision)))
