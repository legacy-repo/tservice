(ns tservice.events.ballgown2exp
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [me.raynes.fs :as fs]
            [tservice.lib.fs :as fs-lib]
            [clojure.tools.logging :as log]
            [tservice.lib.filter-files :as ff]
            [tservice.lib.merge-exp :as me]
            [tservice.lib.r2r :as r2r]
            [tservice.util :as u]
            [tservice.events :as events]
            [clojure.data.json :as json]))

(def ^:const ballgown2exp-topics
  "The `Set` of event topics which are subscribed to for use in ballgown2exp tracking."
  #{:ballgown2exp-convert})

(def ^:private ballgown2exp-channel
  "Channel for receiving event ballgown2exp we want to subscribe to for ballgown2exp events."
  (async/chan))

;;; ------------------------------------------------ Event Processing ------------------------------------------------

(defn- ballgown2exp! 
  "Chaining Pipeline: merge_exp_file -> r2r -> multiqc."
  [ballgown-dir phenotype-filepath dest-dir]
  (let [files (ff/batch-filter-files ballgown-dir [".*call-ballgown/.*.txt"])
        ballgown-dir (fs-lib/join-paths dest-dir "ballgown")
        exp-filepath (fs-lib/join-paths dest-dir "fpkm.txt")
        result-dir (fs-lib/join-paths dest-dir "results")
        log-path (fs-lib/join-paths dest-dir "log")]
    (try
      (do
       (fs-lib/create-directories! ballgown-dir)
       (fs-lib/create-directories! result-dir)
       (log/info "Merge these files: " files)
       (log/info "Merge gene experiment files from ballgown directory to a experiment table: " ballgown-dir exp-filepath)
       (ff/copy-files! files ballgown-dir {:replace-existing true})
       (me/merge-exp-files! (ff/list-files ballgown-dir {:mode "file"}) exp-filepath)
       (log/info "Call R2R: " exp-filepath phenotype-filepath result-dir)
       (let [result (r2r/call-r2r! exp-filepath phenotype-filepath result-dir)
             status (if (= (:exit result) 0) "Success" "Error")
             msg (str (:out result) "\n" (:err result))
             log (json/write-str {:status status  
                                  :msg msg})]
         (log/info status msg)
         (spit log-path log)))
      (catch Exception e
        (let [log (json/write-str {:status "Error" :msg (.toString e)})]
          (spit log-path log))))))

(defn- process-ballgown2exp-event!
  "Handle processing for a single event notification received on the ballgown2exp-channel"
  [ballgown2exp-event]
  ;; try/catch here to prevent individual topic processing exceptions from bubbling up.  better to handle them here.
  (try
    (when-let [{topic :topic object :item} ballgown2exp-event]
      ;; TODO: only if the definition changed??
      (case (events/topic->model topic)
        "ballgown2exp"  (ballgown2exp! (:ballgown-dir object) (:phenotype-filepath object) (:dest-dir object))))
    (catch Throwable e
      (log/warn (format "Failed to process ballgown2exp event. %s" (:topic ballgown2exp-event)) e))))

;;; --------------------------------------------------- Lifecycle ----------------------------------------------------

(defn events-init
  "Automatically called during startup; start event listener for ballgown2exp events."
  []
  (events/start-event-listener! ballgown2exp-topics ballgown2exp-channel process-ballgown2exp-event!))
