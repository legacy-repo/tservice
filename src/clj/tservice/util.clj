(ns tservice.util
  "Common utility functions useful throughout the codebase."
  (:require [clojure.tools.namespace.find :as ns-find]
            [colorize.core :as colorize]
            [clojure.java.classpath :as classpath]
            [tservice.plugins.classloader :as classloader]
            [clojure.java.io :as io]
            [clojure.string :as clj-str]
            [clojure.tools.logging :as log]
            [clj-uuid :as uuid]
            [tservice.lib.fs :as fs-lib]
            [clj-time.coerce :as coerce]
            [clj-time.core :as t])
  (:import
   [org.apache.commons.compress.archivers.zip ZipFile ZipArchiveEntry]))

(defn- namespace-symbs* []
  (for [ns-symb (distinct
                 (ns-find/find-namespaces (concat (classpath/system-classpath)
                                                  (classpath/classpath (classloader/the-classloader)))))
        :when   (and (.startsWith (name ns-symb) "tservice.")
                     (not (.contains (name ns-symb) "test")))]
    ns-symb))

(def tservice-namespace-symbols
  "Delay to a vector of symbols of all tservice namespaces, excluding test namespaces.
    This is intended for use by various routines that load related namespaces, such as task and events initialization.
    Using `ns-find/find-namespaces` is fairly slow, and can take as much as half a second to iterate over the thousand
    or so namespaces that are part of the tservice project; use this instead for a massive performance increase."
  ;; We want to give JARs in the ./plugins directory a chance to load. At one point we have this as a future so it
  ;; start looking for things in the background while other stuff is happening but that meant plugins couldn't
  ;; introduce new tservice namespaces such as drivers.
  (delay (vec (namespace-symbs*))))

(def ^:private ^{:arglists '([color-symb x])} colorize
  "Colorize string `x` with the function matching `color` symbol or keyword."
  (fn [color x]
    (colorize/color (keyword color) x)))

(defn format-color
  "Like `format`, but colorizes the output. `color` should be a symbol or keyword like `green`, `red`, `yellow`, `blue`,
  `cyan`, `magenta`, etc. See the entire list of avaliable
  colors [here](https://github.com/ibdknox/colorize/blob/master/src/colorize/core.clj).

      (format-color :red \"Fatal error: %s\" error-message)"
  {:style/indent 2}
  (^String [color x]
   {:pre [((some-fn symbol? keyword?) color)]}
   (colorize color (str x)))

  (^String [color format-string & args]
   (colorize color (apply format (str format-string) args))))

(defn join-path
  [root path]
  (let [root (clj-str/replace root #"/$" "")
        path (clj-str/replace path #"^/" "")]
    (str root "/" path)))

(defn delete-recursively [fname]
  (doseq [f (-> (clojure.java.io/file fname)
                (file-seq)
                (reverse))]
    (clojure.java.io/delete-file f)))

(defn uuid
  "These UUID's will be guaranteed to be unique and thread-safe regardless of clock precision or degree of concurrency."
  []
  (str (uuid/v1)))

(defn merge-diff-map
  "Insert into the old-map when it doesn't contains a key in default map."
  [old-map default-map]
  (let [diff-map (into {} (filter #(nil? ((key %) old-map)) default-map))]
    (merge old-map diff-map)))

(defn time->int
  [datetime]
  (coerce/to-long datetime))

(defn now
  "Get the current local datetime."
  ([offset]
   (t/to-time-zone (t/now) (t/time-zone-for-offset offset)))
  ([] (now 0)))

(defn call [this & that]
  (cond
    (string? this) (apply (resolve (symbol this)) that)
    (fn? this)     (apply this that)
    :else          (conj that this)))

;; Note that make-parents is called for every file. Tested with ZIP
;; with ca 80k files. Not significantly slower than testing here for
;; .isDirectory and only then create the parents. Keeping the nicer
;; code with this comment, then.
(defn unzip-file
  [zip-file to-dir]
  (log/infof "Extracting %s" zip-file)
  (log/debug "  to:" to-dir)
  (with-open [zipf (ZipFile. (io/file zip-file))]
    (doseq [entry (enumeration-seq (.getEntries zipf))
            :when (not (.isDirectory ^ZipArchiveEntry entry))
            :let  [zip-in-s (.getInputStream zipf entry)
                   out-file (io/file (str to-dir
                                          java.io.File/separator
                                          (.getName entry)))]]
      (log/trace "  ->" (.getName out-file))
      (io/make-parents out-file)
      (with-open [entry-o-s (io/output-stream out-file)]
        (io/copy zip-in-s entry-o-s)))))

(defn replace-path
  [filepath workdir]
  (if (re-matches #"^file:\/\/\/.*" filepath)
    ; Absolute path with file://
    (clj-str/replace filepath #"^file:\/\/" "")
    (fs-lib/join-paths workdir (clj-str/replace filepath #"^file:\/\/" ""))))