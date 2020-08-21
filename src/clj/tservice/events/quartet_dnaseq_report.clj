(ns tservice.events.quartet-dnaseq-report
  (:require [clojure.core.async :as async]
            [tservice.lib.fs :as fs-lib]
            [clojure.tools.logging :as log]
            [tservice.lib.filter-files :as ff]
            [tservice.vendor.multiqc :as mq]
            [tservice.events :as events]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(def ^:const quartet-dnaseq-report-topics
  "The `Set` of event topics which are subscribed to for use in quartet-dnaseq-report tracking."
  #{:quartet_dnaseq_report-convert})

(def ^:private quartet-dnaseq-report-channel
  "Channel for receiving event quartet-dnaseq-report we want to subscribe to for quartet-dnaseq-report events."
  (async/chan))

;;; ------------------------------------------------ Event Processing ------------------------------------------------

(defn- quartet-dnaseq-report!
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
        config (.getPath (io/resource "config/quartet_dnaseq_report.yaml"))]
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

(defn- process-quartet-dnaseq-report-event!
  "Handle processing for a single event notification received on the quartet-dnaseq-report-channel"
  [quartet-dnaseq-report-event]
  ;; try/catch here to prevent individual topic processing exceptions from bubbling up.  better to handle them here.
  (try
    (when-let [{topic :topic object :item} quartet-dnaseq-report-event]
      ;; TODO: only if the definition changed??
      (case (events/topic->model topic)
        "quartet_dnaseq_report"  (quartet-dnaseq-report! (:datadir object) (:metadata object) (:dest-dir object))))
    (catch Throwable e
      (log/warn (format "Failed to process quartet-dnaseq-report event. %s" (:topic quartet-dnaseq-report-event)) e))))

;;; --------------------------------------------------- Lifecycle ----------------------------------------------------

(defn events-init
  "Automatically called during startup; start event listener for quartet-dnaseq-report events."
  []
  (events/start-event-listener! quartet-dnaseq-report-topics quartet-dnaseq-report-channel process-quartet-dnaseq-report-event!))
