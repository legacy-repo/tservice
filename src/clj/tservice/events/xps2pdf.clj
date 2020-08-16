(ns tservice.events.xps2pdf
  (:require [clojure.core.async :as async]
            [me.raynes.fs :as fs]
            [tservice.lib.fs :as fs-lib]
            [clojure.tools.logging :as log]
            [tservice.lib.xps :as xps-lib]
            [clojure.data.json :as json]
            [tservice.events :as events]))

(def spec "")

(def manifest {:name "xps2pdf"
               :file-type #"(zip|xps)"
               :args spec})

(def ^:const xps2pdf-topics
  "The `Set` of event topics which are subscribed to for use in xps2pdf tracking."
  #{:xps2pdf-convert
    :batchxps2pdf-convert})

(def ^:private xps2pdf-channel
  "Channel for receiving event xps2pdf we want to subscribe to for xps2pdf events."
  (async/chan))

;;; ------------------------------------------------ Event Processing ------------------------------------------------

(defn- xps2pdf! [from to]
  (log/info "Converted xps to pdf: " from to)
  (let [out (xps-lib/xps2pdf from to)
        log-path (fs-lib/join-paths (fs-lib/parent-path to) "log")
        exit (:exit out)
        status (if (>= exit 0) "Success" "Error")
        msg (if (>= exit 0) (:err out) (:out out))
        log (json/write-str {:status status :msg msg})]
    (spit log-path log)))

(defn- batch-xps2pdf! [from-files to-dir]
  (log/info "Converted xps files in a zip to pdf files.")
  (let [to-files (vec (map #(fs-lib/join-paths to-dir (str (fs/base-name % ".xps") ".pdf")) from-files))
        zip-path (fs-lib/join-paths to-dir "merged.zip")
        pdf-path (fs-lib/join-paths to-dir "merged.pdf")]
    (doall (pmap #(xps2pdf! %1 %2) from-files to-files))
    (fs-lib/zip-files to-files zip-path)
    (fs-lib/merge-pdf-files to-files pdf-path)))

(defn- process-xps2pdf-event!
  "Handle processing for a single event notification received on the xps2pdf-channel"
  [xps2pdf-event]
  ;; try/catch here to prevent individual topic processing exceptions from bubbling up.  better to handle them here.
  (try
    (when-let [{topic :topic object :item} xps2pdf-event]
      ;; TODO: only if the definition changed??
      (case (events/topic->model topic)
        "xps2pdf"  (xps2pdf! (:from object) (:to object))
        "batchxps2pdf" (batch-xps2pdf! (:from-files object) (:to-dir object))))
    (catch Throwable e
      (log/warn (format "Failed to process xps2pdf event. %s" (:topic xps2pdf-event)) e))))

;;; --------------------------------------------------- Lifecycle ----------------------------------------------------

(defn events-init
  "Automatically called during startup; start event listener for xps2pdf events."
  []
  (events/start-event-listener! xps2pdf-topics xps2pdf-channel process-xps2pdf-event!))
