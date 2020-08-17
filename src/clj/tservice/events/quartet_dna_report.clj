(ns tservice.events.quartet-dna-report
  (:require [clojure.core.async :as async]
            [tservice.lib.fs :as fs-lib]
            [clojure.tools.logging :as log]
            [tservice.lib.filter-files :as ff]
            [tservice.lib.multiqc :as mq]
            [tservice.events :as events]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(def ^:const quartet-dna-report-topics
  "The `Set` of event topics which are subscribed to for use in quartet-dna-report tracking."
  #{:quartet_dna_report-convert})

(def ^:private quartet-dna-report-channel
  "Channel for receiving event quartet-dna-report we want to subscribe to for quartet-dna-report events."
  (async/chan))

;;; ------------------------------------------------ Event Processing ------------------------------------------------

(defn- quartet-dna-report!
  "Chaining Pipeline: merge_exp_file -> r2r -> multiqc."
  [datadir metadata dest-dir]
  (log/info "Generate quartet dna report: " datadir metadata dest-dir)
  (let [metadata-file (fs-lib/join-paths dest-dir
                                         "results"
                                         "data_generation_information.json")
        files (ff/batch-filter-files datadir
                                     [".*call-fastqc/.*_fastqc.html"
                                      ".*call-fastqc/.*_fastqc.zip"
                                      ".*call-qualimapBAMqc/.*sorted_bamqc_qualimap.zip"
                                      ".*/benchmark_score.txt"
                                      ".*/mendelian_jaccard_index_indel.txt"
                                      ".*/mendelian_jaccard_index_snv.txt"
                                      ".*/postalignment_qc_summary.txt"
                                      ".*/prealignment_qc_summary.txt"
                                      ".*/precision_recall_indel.txt"
                                      ".*/precision_recall_snv.txt"
                                      ".*/variant_calling_qc_summary.txt"])
        result-dir (fs-lib/join-paths dest-dir "results")
        log-path (fs-lib/join-paths dest-dir "log")
        config (.getPath (io/resource "config/quartet_dna_report.yaml"))]
    (try
      (fs-lib/create-directories! result-dir)
      (spit metadata-file (json/write-str metadata))
      (log/info "Copy files to " result-dir)
      (ff/copy-files! files result-dir {:replace-existing true})
      (let [multiqc-result (mq/multiqc result-dir dest-dir {:config config :template "quartet_dnaseq_report"})
            result {:status (:status multiqc-result)
                    :msg (:msg multiqc-result)}
            log (json/write-str result)]
        (log/info "Status: " result)
        (spit log-path log))
      (catch Exception e
        (let [log (json/write-str {:status "Error" :msg (.toString e)})]
          (log/info "Status: " log)
          (spit log-path log))))))

(defn- process-quartet-dna-report-event!
  "Handle processing for a single event notification received on the quartet-dna-report-channel"
  [quartet-dna-report-event]
  ;; try/catch here to prevent individual topic processing exceptions from bubbling up.  better to handle them here.
  (try
    (when-let [{topic :topic object :item} quartet-dna-report-event]
      ;; TODO: only if the definition changed??
      (case (events/topic->model topic)
        "quartet_dna_report"  (quartet-dna-report! (:datadir object) (:metadata object) (:dest-dir object))))
    (catch Throwable e
      (log/warn (format "Failed to process quartet-dna-report event. %s" (:topic quartet-dna-report-event)) e))))

;;; --------------------------------------------------- Lifecycle ----------------------------------------------------

(defn events-init
  "Automatically called during startup; start event listener for quartet-dna-report events."
  []
  (events/start-event-listener! quartet-dna-report-topics quartet-dna-report-channel process-quartet-dna-report-event!))
