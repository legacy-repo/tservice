(ns tservice.api.storage.fs
  (:require [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [tservice.lib.fs :as fs-lib]
            [clj-filesystem.core :as clj-fs]))

;; -------------------------------- Re-export --------------------------------
(def join-paths fs-lib/join-paths)

(def create-directories! fs-lib/create-directories!)

(def directory? fs-lib/directory?)

(def regular-file? fs-lib/regular-file?)

(def parent-path fs-lib/parent-path)

(def zip-files fs-lib/zip-files)

(def merge-pdf-files fs-lib/merge-pdf-files)

(defn fs-service?
  [filepath]
  (re-matches #"^[a-zA-Z0-9]+:\/\/.*" filepath))

(defn not-empty?
  [coll]
  ((complement empty?) coll))

(defn parse-path
  "Parse path and extract protocol, bucket, and object path."
  [path]
  (let [path-lst (rest (re-find (re-matcher #"^([a-zA-Z0-9]+):\/\/([^\/]+)\/(.*)" path)))]
    (when (not-empty? path-lst)
      {:protocol (first path-lst)
       :bucket (second path-lst)
       :prefix (nth path-lst 2)})))

(defn make-link
  [protocol bucket object]
  (str protocol "://" bucket "/" object))

(defn filter-remote-fn
  [mode]
  (cond
    (= mode "directory") #".*\/"
    (= mode "file") #".*[^\/]$"
    :else #".*"))

(defn filter-local-fn
  [mode]
  (cond
    (= mode "directory") (fn [file] (.isDirectory (io/file file)))
    (= mode "file") (fn [file] (.isFile (io/file file)))
    :else (fn [file] file)))

(defn objects->links
  [protocol bucket objects mode]
  (->> objects
       (map #(make-link protocol bucket (:key %)))
       (filter #(re-matches (filter-remote-fn mode) %))))

(defn list-files
  "Local path must not contain file:// prefix. options - directory | file"
  [path & options]
  (let [{:keys [mode]} (first options)
        {:keys [protocol bucket prefix]} (parse-path path)
        is-service? (fs-service? path)]
    (if is-service?
      (->> (concat
            (map (fn [item] (clj-fs/with-conn protocol (clj-fs/list-objects bucket (:key item))))
                 (clj-fs/with-conn protocol (clj-fs/list-objects bucket prefix true))))
           (flatten)
           (map #(make-link protocol bucket (:key %)))
           (filter #(re-matches (filter-remote-fn mode) %)))
      (->> (io/file path)
           file-seq
           (map #(.getAbsolutePath %))
           (filter #((filter-local-fn mode) %))))))

(defn make-pattern-fn
  [patterns]
  (map #(re-pattern %) patterns))

(defn filter-files
  [all-files pattern]
  (filter #(re-matches (re-pattern pattern) %) all-files))

(defn batch-filter-files
  [path patterns]
  (-> (map #(filter-files (list-files path) %)
           (make-pattern-fn patterns))
      flatten
      dedupe))

(defn copy-local-files!
  ":replace-existing, :copy-attributes, :nofollow-links"
  [files dest-dir options]
  (doseq [file-path files]
    (let [file (io/file file-path)
          dest (fs-lib/join-paths dest-dir (fs/base-name file-path))]
      (if (.isFile file)
        (fs-lib/copy file-path dest options)
        (fs-lib/copy-recursively file-path dest options)))))

(defn copy-local-file!
  [file-path dest-dir options]
  (let [file (io/file file-path)
        dest (fs-lib/join-paths dest-dir (fs/base-name file-path))]
    (if (.isFile file)
      (fs-lib/copy file-path dest options)
      (fs-lib/copy-recursively file-path dest options))))

(defn basename
  [path]
  (let [groups (re-find #".*\/(.*\/?)" path)]
    (if groups
      (first (rest groups))
      nil)))

(defn dirname
  [filepath]
  (let [groups (re-find #".*\/(.*)\/$" filepath)]
    (if groups
      (first (rest groups))
      nil)))

(defn copy-remote-file!
  [file-path dest-dir options]
  ;; TODO: Any options can help to improve the action.
  (let [is-dir? (re-matches #".*\/" file-path)
        filename (basename file-path)
        {:keys [protocol bucket prefix]} (parse-path file-path)]
    (if is-dir?
      (let [dest-dir (fs-lib/join-paths dest-dir (dirname file-path))]
        (fs-lib/create-directory dest-dir)
        (map #(copy-remote-file! % dest-dir options)
             (list-files file-path)))
      (clj-fs/with-conn protocol (clj-fs/download-object bucket prefix (fs-lib/join-paths dest-dir filename))))))

(defn copy-files!
  ":replace-existing, :copy-attributes, :nofollow-links"
  [files dest-dir options]
  (doseq [file-path files]
    (if (fs-service? file-path)
      (copy-remote-file! file-path dest-dir options)
      (copy-local-file! file-path dest-dir options))))
