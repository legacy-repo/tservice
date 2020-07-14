(ns tservice.lib.filter-files
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [me.raynes.fs :as fs]
            [tservice.lib.fs :as fs-lib]))

(defn list-files
  [path & options]
  (let [{:keys [mode]} (first options)]
    (->> (io/file path)
         file-seq
         (map #(.getAbsolutePath %))
         (filter #(cond
                    (= mode "directory") (.isDirectory (io/file %))
                    (= mode "file") (.isFile (io/file %))
                    :else true)))))

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

(defn copy-files!
  ":replace-existing, :copy-attributes, :nofollow-links"
  [files dest-dir options]
  (doseq [file-path files]
    (let [file (io/file file-path)
          dest (fs-lib/join-paths dest-dir (fs/base-name file-path))]
      (if (.isFile file)
        (fs-lib/copy file-path dest options)
        (fs-lib/copy-recursively file-path dest options)))))
