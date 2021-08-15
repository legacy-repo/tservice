(ns tservice.lib.files
  "Low-level file-related functions for implementing TService plugin functionality. These use the `java.nio.file`
  library rather than the usual `java.io` stuff because it abstracts better across different filesystems (such as
  files in a normal directory vs files inside a JAR.)
  As much as possible, this namespace aims to abstract away the `nio.file` library and expose a set of high-level
  *file-manipulation* functions for the sorts of operations the plugin system needs to perform."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [tservice.util :as u]
            [tservice.lib.fs :as fs]
            [clojure.string :as clj-str]
            [tservice.config :refer [env]]
            [selmer.parser :as parser]
            [clojure.java.shell :as shell :refer [sh]])
  (:import java.io.FileNotFoundException
           java.net.URL
           [java.nio.file CopyOption Files FileSystem FileSystems LinkOption OpenOption Path Paths StandardCopyOption]
           java.nio.file.attribute.FileAttribute
           java.util.Collections))

;;; --------------------------------------------------- Path Utils ---------------------------------------------------

(defn- get-path-in-filesystem ^Path [^FileSystem filesystem ^String path-component & more-components]
  (.getPath filesystem path-component (u/varargs String more-components)))

(defn get-path
  "Get a `Path` for a file or directory in the default (i.e., system) filesystem named by string path component(s).
    (get-path \"/Users/cam/tservice/tservice/plugins\")
    ;; -> #object[sun.nio.fs.UnixPath 0x4d378139 \"/Users/cam/tservice/tservice/plugins\"]"
  ^Path [& path-components]
  (apply get-path-in-filesystem (FileSystems/getDefault) path-components))

(defn- append-to-path ^Path [^Path path & components]
  (loop [^Path path path, [^String component & more] components]
    (let [path (.resolve path component)]
      (if-not (seq more)
        path
        (recur path more)))))

;;; ----------------------------------------------- Other Basic Utils ------------------------------------------------

(defn exists?
  "Does file at `path` actually exist?"
  [^Path path]
  (Files/exists path (u/varargs LinkOption)))

(defn regular-file?
  "True if `path` refers to a regular file (as opposed to something like directory)."
  [^Path path]
  (Files/isRegularFile path (u/varargs LinkOption)))

(defn readable?
  "True if we can read the file at `path`."
  [^Path path]
  (Files/isReadable path))


;;; ----------------------------------------------- Working with Dirs ------------------------------------------------

(defn create-dir-if-not-exists!
  "Self-explanatory. Create a directory with `path` if it does not already exist."
  [^Path path]
  (when-not (exists? path)
    (Files/createDirectories path (u/varargs FileAttribute))))

(defn files-seq
  "Get a sequence of all files in `path`, presumably a directory or an archive of some sort (like a JAR)."
  [^Path path]
  (iterator-seq (.iterator (Files/list path))))


;;; ------------------------------------------------- Copying Stuff --------------------------------------------------

(defn- last-modified-timestamp ^java.time.Instant [^Path path]
  (when (exists? path)
    (.toInstant (Files/getLastModifiedTime path (u/varargs LinkOption)))))

(defn copy-file!
  "Copy a file from `source` -> `dest`."
  [^Path source ^Path dest]
  (when (or (not (exists? dest))
            (not= (last-modified-timestamp source) (last-modified-timestamp dest)))
    (log/info (format "Extract file %s -> %s" source dest))
    (Files/copy source dest (u/varargs CopyOption [StandardCopyOption/REPLACE_EXISTING
                                                   StandardCopyOption/COPY_ATTRIBUTES]))))

(defn copy-files!
  "Copy all files in `source-dir` to `dest-dir`. Overwrites existing files if last modified timestamp is not the same as
  that of the source file — see #11699 for more context."
  [^Path source-dir, ^Path dest-dir]
  (doseq [^Path source (files-seq source-dir)
          :let         [target (append-to-path dest-dir (str (.getFileName source)))]]
    (try
      (copy-file! source target)
      (catch Throwable e
        (log/error e "Failed to copy file")))))


;;; ------------------------------------------ Opening filesystems for URLs ------------------------------------------

(defn- url-inside-jar? [^URL url]
  (when url
    (clj-str/includes? (.getFile url) ".jar!/")))

(defn- jar-file-system-from-url ^FileSystem [^URL url]
  (FileSystems/newFileSystem (.toURI url) Collections/EMPTY_MAP))

(defn do-with-open-path-to-resource
  "Impl for `with-open-path-to-resource`."
  [^String resource, f]
  (let [url (io/resource resource)]
    (when-not url
      (throw (FileNotFoundException. "Resource does not exist.")))
    (if (url-inside-jar? url)
      (with-open [fs (jar-file-system-from-url url)]
        (f (get-path-in-filesystem fs "/" resource)))
      (f (get-path (.toString (Paths/get (.toURI url))))))))

(defmacro with-open-path-to-resource
  "Execute `body` with an Path to a resource file or directory (i.e. a file in the project `resources/` directory, or
  inside the uberjar), cleaning up when finished.
  Throws a FileNotFoundException if the resource does not exist; be sure to check with `io/resource` or similar before
  calling this.
    (with-open-path-to-resouce [path \"modules\"]
       ...)"
  [[path-binding resource-filename-str] & body]
  `(do-with-open-path-to-resource
    ~resource-filename-str
    (fn [~(vary-meta path-binding assoc :tag java.nio.file.Path)]
      ~@body)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                               Environment                                                |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn get-tservice-workdir
  []
  (clj-str/replace
   (fs/expand-home (get-in env [:tservice-workdir]))
   #"\/$" ""))

(defn get-workdir
  ([]
   (fs/join-paths (get-tservice-workdir) (u/uuid)))
  ([username]
   (fs/join-paths (get-tservice-workdir) username (u/uuid))))

(defn- get-plugin-basedir
  []
  (clj-str/replace
   (fs/expand-home (get-in env [:tservice-plugin-path]))
   #"\/$" ""))

(defn get-plugin-dir
  []
  (let [path (fs/join-paths (get-plugin-basedir) "plugins")]
    (create-dir-if-not-exists! (get-path path))
    path))

(defn get-plugin-jar-dir
  []
  (let [path (fs/join-paths (get-plugin-basedir) "plugin-jars")]
    (create-dir-if-not-exists! (get-path path))
    path))

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

(defn exist-bin?
  [name]
  (= 0 (:exit (sh "which" name))))

(defn is-localpath?
  [filepath]
  (re-matches #"^file:\/\/.*" filepath))

(defn correct-filepath
  [filepath]
  (if (is-localpath? filepath)
    (if (re-matches #"^file:\/\/\/.*" filepath)
    ; Absolute path with file://
      (clj-str/replace filepath #"^file:\/\/" "")
      (fs/join-paths (get-workdir) (clj-str/replace filepath #"^file:\/\/" "")))
    filepath))

(defn get-relative-filepath
  [filepath]
  (clj-str/replace filepath (re-pattern (get-tservice-workdir)) "."))

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
        env-dest-dir (fs/join-paths ENV_DEST_DIR ENV_NAME)
        env-name ENV_NAME
        clone-env-bin (which "clone-env")]
    (parser/render template {:ENV_DEST_DIR env-dest-dir
                             :ENV_NAME env-name
                             :CLONE_ENV_BIN clone-env-bin})))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                               JAR FILE CONTENTS                                                |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn file-exists-in-archive?
  "True is a file exists in an archive."
  [^Path archive-path & path-components]
  (with-open [fs (FileSystems/newFileSystem archive-path (ClassLoader/getSystemClassLoader))]
    (let [file-path (apply get-path-in-filesystem fs path-components)]
      (exists? file-path))))

(defn slurp-file-from-archive
  "Read the entire contents of a file from a archive (such as a JAR)."
  [^Path archive-path & path-components]
  (with-open [fs (FileSystems/newFileSystem archive-path (ClassLoader/getSystemClassLoader))]
    (let [file-path (apply get-path-in-filesystem fs path-components)]
      (when (exists? file-path)
        (with-open [is (Files/newInputStream file-path (u/varargs OpenOption))]
          (slurp is))))))

(defn long-str [& strings] (clojure.string/join " " strings))

(defn decompress-archive
  "decompress data from archive to `out-folder` directory.
   Warning! In `out-folder` files will be overwritten by decompressed data from `arch-name`.
  "
  [^String arch-name ^String out-folder]
  (let [suffix (fs/extension arch-name)
        cmd-fn (fn [arg] (call-command! (format "tar %s -xf %s -C %s" arg arch-name out-folder)))]
    (cond (= suffix "gz") (cmd-fn "-z")
          (= suffix "bz2") (cmd-fn "-j")
          (= suffix "xz") (cmd-fn "-J")
          (= suffix "lzma") (cmd-fn "--lzma")
          (= suffix "zip") (call-command! (format "unzip -d %s %s" out-folder arch-name)))))

(defn extract-env-from-archive
  "Extract the entire contents of a file from a archive (such as a JAR)."
  [^Path archive-path ^String path-component ^String dest-dir]
  (with-open [fs (FileSystems/newFileSystem archive-path (ClassLoader/getSystemClassLoader))]
    (let [file-path (get-path-in-filesystem fs path-component)
          dest-path (fs/join-paths dest-dir path-component)
          env-name (first (clj-str/split path-component #"\."))
          env-path (fs/join-paths dest-dir env-name)]
      (log/info (format "Extract env archive %s to %s" file-path env-path))
      (when (exists? file-path)
        (if (fs/exists? env-path)
          (log/info (u/format-color 'yellow
                                    (format (long-str "If it have any problems when the plugin %s is loading"
                                                      "you can remove the directory `%s` and retry.") env-name env-path)))
          (with-open [is (Files/newInputStream file-path (u/varargs OpenOption))]
            (io/copy is (io/file dest-path))
            (decompress-archive dest-path dest-dir)))))))
