(ns tservice.util.files
  "Low-level file-related functions for implementing TService plugin functionality. These use the `java.nio.file`
  library rather than the usual `java.io` stuff because it abstracts better across different filesystems (such as
  files in a normal directory vs files inside a JAR.)
  As much as possible, this namespace aims to abstract away the `nio.file` library and expose a set of high-level
  *file-manipulation* functions for the sorts of operations the plugin system needs to perform."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [tservice.util :as u]
            [tservice.lib.fs :as fs-lib]
            [tservice.lib.commons :as commons])
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
    (Files/createDirectory path (u/varargs FileAttribute))))

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
    (str/includes? (.getFile url) ".jar!/")))

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
  (let [suffix (fs-lib/extension arch-name)
        cmd-fn (fn [arg] (commons/call-command! (format "tar %s -xf %s -C %s" arg arch-name out-folder)))]
   (cond (= suffix "gz") (cmd-fn "-z")
         (= suffix "bz2") (cmd-fn "-j")
         (= suffix "xz") (cmd-fn "-J")
         (= suffix "lzma") (cmd-fn "--lzma")
         (= suffix "zip") (commons/call-command! (format "unzip -d %s %s" out-folder arch-name)))))

(defn extract-env-from-archive
  "Extract the entire contents of a file from a archive (such as a JAR)."
  [^Path archive-path ^String path-component ^String dest-dir]
  (with-open [fs (FileSystems/newFileSystem archive-path (ClassLoader/getSystemClassLoader))]
    (let [file-path (get-path-in-filesystem fs path-component)
          dest-path (fs-lib/join-paths dest-dir path-component)
          env-name (first (str/split path-component #"\."))
          env-path (fs-lib/join-paths dest-dir env-name)]
      (log/info (format "Extract env archive %s to %s" file-path env-path))
      (when (exists? file-path)
        (if (fs-lib/exists? env-path)
          (log/info (u/format-color 'yellow
                                    (format (long-str "If it have any problems when the plugin %s is loading"
                                                      "you can remove the directory `%s` and retry.") env-name env-path)))
          (with-open [is (Files/newInputStream file-path (u/varargs OpenOption))]
            (io/copy is (io/file dest-path))
            (decompress-archive dest-path dest-dir)))))))
