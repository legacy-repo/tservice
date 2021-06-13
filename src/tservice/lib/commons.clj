(ns tservice.lib.commons
  (:require [tservice.config :refer [get-workdir env get-plugin-dir]]
            [clojure.data.csv :as csv]
            [clojure.string :as clj-str]
            [tservice.lib.fs :as fs-lib]
            [clojure.java.io :as io]
            [selmer.parser :as parser]
            [clojure.tools.logging :as log]
            [clojure.java.shell :as shell :refer [sh]])
  (:import [org.apache.commons.io.input BOMInputStream]))

(defn get-path-variable
  []
  (let [external-bin (get-in env [:external-bin])
        sys-path (System/getenv "PATH")]
    (if external-bin
      (str external-bin ":" sys-path)
      sys-path)))

(defn hashmap->parameters
  "{ '-d' 'true' '-o' 'output' } -> '-d true -o output'"
  [coll]
  (clj-str/join " " (map #(clj-str/join " " %) (into [] coll))))

(defn call-command!
  ([cmd parameters-coll]
   (shell/with-sh-env {:PATH   (get-path-variable)
                       :LC_ALL "en_US.utf-8"
                       :LANG   "en_US.utf-8"}
     (let [command ["bash" "-c" (format "%s %s" cmd (hashmap->parameters parameters-coll))]
           result (apply sh command)
           status (if (= (:exit result) 0) "Success" "Error")
           msg (str (:out result) "\n" (:err result))]
       {:status status
        :msg msg})))
  ([cmd]
   (call-command! cmd [])))

(defn get-external-root
  []
  (fs-lib/join-paths (get-plugin-dir) "external"))

(defn exist-bin?
  [name]
  (= 0 (:exit (sh "which" name))))

(defn csv-data->maps [csv-data]
  (map zipmap
       (->> (first csv-data) ;; First row is the header
            (map keyword)    ;; Drop if you want string keys instead
            repeat)
       (rest csv-data)))

(defn bom-reader
  "Remove `Byte Order Mark` and return reader"
  [filepath]
  (-> filepath
      io/input-stream
      BOMInputStream.
      io/reader))

(defn guess-separator
  [filepath]
  (with-open [reader (bom-reader filepath)]
    (let [header (first (line-seq reader))
          seps [\tab \, \; \space]
          sep-map (->> (map #(hash-map % (count (clj-str/split header (re-pattern (str %))))) seps)
                       (into {}))]
      (key (apply max-key val sep-map)))))

(defn read-csv
  [^String file]
  (when (.isFile (io/file file))
    (with-open
     [reader (io/reader file)]
      (doall
       (->> (csv/read-csv reader :separator (guess-separator file))
            csv-data->maps)))))

(defn vec-remove
  "Remove elem in coll"
  [pos coll]
  (vec (concat (subvec coll 0 pos) (subvec coll (inc pos)))))

(defn write-csv!
  "Write row-data to a csv file, row-data is a vector that each element is a map."
  [path row-data]
  (let [columns (keys (first row-data))
        headers (map name columns)
        rows (mapv #(mapv % columns) row-data)]
    (with-open [file (io/writer path)]
      (csv/write-csv file (cons headers rows) :separator \tab))))

(defn write-csv-by-cols! [path row-data columns]
  (let [headers (map name columns)
        rows (mapv #(mapv % columns) row-data)]
    (with-open [file (io/writer path)]
      (csv/write-csv file (cons headers rows)))))

(defn is-localpath?
  [filepath]
  (re-matches #"^file:\/\/.*" filepath))

(defn correct-filepath
  [filepath]
  (if (is-localpath? filepath)
    (if (re-matches #"^file:\/\/\/.*" filepath)
    ; Absolute path with file://
      (clj-str/replace filepath #"^file:\/\/" "")
      (fs-lib/join-paths (get-workdir) (clj-str/replace filepath #"^file:\/\/" "")))
    filepath))

(defn which
  [bin-name]
  (let [paths (clojure.string/split (or (System/getenv "PATH") "")
                                    (re-pattern (System/getProperty "path.separator")))
        ;; for windows
        pathexts (clojure.string/split (or (System/getenv "PATHEXT") "")
                                       (re-pattern (System/getProperty "path.separator")))]
    ;; adapted work by taylorwood
    (first
     (for [path (distinct paths)
           pathext pathexts
           :let [exe-file (clojure.java.io/file path (str bin-name pathext))]
           :when (.exists exe-file)]
       (.getAbsolutePath exe-file)))))

(defn render-template
  "TODO: Schema for rendering environment.
   
   Arguments:
     env-context: {:ENV_DEST_DIR \" \"
                   :ENV_NAME \"pgi\"
                   :CLONE_ENV_BIN \"\"}
   "
  [template env-context]
  (log/debug (format "Render Template with Environment Context: %s" env-context))
  (let [{:keys [ENV_DEST_DIR ENV_NAME]} env-context
        env-dest-dir (fs-lib/join-paths ENV_DEST_DIR ENV_NAME)
        env-name ENV_NAME
        clone-env-bin (which "clone-env")]
    (parser/render template {:ENV_DEST_DIR env-dest-dir
                             :ENV_NAME env-name
                             :CLONE_ENV_BIN clone-env-bin})))
