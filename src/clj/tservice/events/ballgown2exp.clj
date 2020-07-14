(ns tservice.events.ballgown2exp
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [me.raynes.fs :as fs]
            [tservice.lib.fs :as fs-lib]
            [clojure.tools.logging :as log]
            [tservice.lib.filter-files :as ff]
            [tservice.lib.merge-exp :as me]
            [tservice.util :as u]
            [tservice.events :as events]))

(def ^:const ballgown2exp-topics
  "The `Set` of event topics which are subscribed to for use in ballgown2exp tracking."
  #{:ballgown2exp-convert})

(def ^:private ballgown2exp-channel
  "Channel for receiving event ballgown2exp we want to subscribe to for ballgown2exp events."
  (async/chan))

;;; ------------------------------------------------ Event Processing ------------------------------------------------

(defn- ballgown2exp! [from to]
  (let [files (ff/batch-filter-files from [".*call-ballgown/.*.txt"])
        dest-dir (fs-lib/join-paths to "ballgown")
        exp-filepath (fs-lib/join-paths to "fpkm.txt")]
    (log/info "Merge these files: " files)
    (log/info "Merge gene experiment files from ballgown directory to a experiment table: " dest-dir exp-filepath)
    (ff/copy-files! files dest-dir {:replace-existing true})
    (me/merge-exp-files! (ff/list-files dest-dir {:mode "file"}) exp-filepath)))

(defn- process-ballgown2exp-event!
  "Handle processing for a single event notification received on the ballgown2exp-channel"
  [ballgown2exp-event]
  ;; try/catch here to prevent individual topic processing exceptions from bubbling up.  better to handle them here.
  (try
    (when-let [{topic :topic object :item} ballgown2exp-event]
      ;; TODO: only if the definition changed??
      (case (events/topic->model topic)
        "ballgown2exp"  (ballgown2exp! (:from object) (:to object))))
    (catch Throwable e
      (log/warn (format "Failed to process ballgown2exp event. %s" (:topic ballgown2exp-event)) e))))

;;; --------------------------------------------------- Lifecycle ----------------------------------------------------

(defn events-init
  "Automatically called during startup; start event listener for ballgown2exp events."
  []
  (events/start-event-listener! ballgown2exp-topics ballgown2exp-channel process-ballgown2exp-event!))
