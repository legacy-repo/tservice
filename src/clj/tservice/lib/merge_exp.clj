(ns tservice.lib.merge-exp
  (:require [clojure.data.csv :as csv]
            [clojure.string :as clj-str]
            [clojure.java.io :as io])
  (:import [org.apache.commons.io.input BOMInputStream]))

(set! *warn-on-reflection* true)

(defn sort-exp-data
  [coll]
  (sort-by :GENE_ID coll))

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

(defn read-csvs
  [files]
  (map #(sort-exp-data (read-csv %)) files))

(defn merge-exp
  "[[{:GENE_ID 'XXX0' :YYY0 1.2} {:GENE_ID 'XXX1' :YYY1 1.3}]
    [{:GENE_ID 'XXX0' :YYY2 1.2} {:GENE_ID 'XXX1' :YYY3 1.3}]]"
  [all-exp-data]
  (apply map merge all-exp-data))

(defn write-csv!
  [path row-data]
  (let [columns (keys (first row-data))
        headers (map name columns)
        rows (mapv #(mapv % columns) row-data)]
    (with-open [file (io/writer path)]
      (csv/write-csv file (cons headers rows)))))

(defn merge-exp-files!
  "Assumption: all files have the same GENE_ID list, no matter what order."
  [files path]
  (->> (read-csvs files)
       (merge-exp)
       (write-csv! path)))

(defn call-rnaseq-r2r
  [input-file output-file]
  (comment))