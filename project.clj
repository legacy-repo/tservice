(defproject tservice "0.1.3"

  :description "FIXME: write description"
  :url "http://example.com/FIXME"

  :dependencies [[ch.qos.logback/logback-classic "1.2.3"]
                 [org.clojure/tools.namespace "1.0.0"]
                 [org.clojure/tools.cli "0.4.2" :exclusions [org.clojure/clojure]]
                 [org.clojure/core.async "0.4.500"
                  :exclusions [org.clojure/tools.reader]]
                 [cheshire "5.10.0"]
                 [me.raynes/fs "1.4.6"]
                 [clojure.java-time "0.3.2"]
                 [colorize "0.1.1" :exclusions [org.clojure/clojure]]               ; string output with ANSI color codes (for logging)
                 [org.tcrawley/dynapath "1.0.0"]                                    ; Dynamically add Jars (e.g. Oracle or Vertica) to classpath
                 [danlentz/clj-uuid "0.1.9"]
                 [com.fasterxml.jackson.core/jackson-core "2.11.0"]
                 [com.fasterxml.jackson.core/jackson-databind "2.11.0"]
                 [cprop "0.1.17"]
                 [ring-cors "0.1.13"]
                 [pdfboxing/pdfboxing "0.1.14"]
                 [clj-file-zip "0.1.0"]
                 [expound "0.8.4"]
                 [funcool/struct "1.4.0"]
                 [luminus-transit "0.1.2"]
                 [luminus/ring-ttl-session "0.3.3"]
                 [markdown-clj "1.10.4"]
                 [metosin/jsonista "0.2.6"]
                 [metosin/muuntaja "0.6.7"]
                 [metosin/reitit "0.5.2"]
                 [metosin/ring-http-response "0.9.1"]
                 [mount "0.1.16"]
                 [nrepl "0.7.0"]
                 [luminus-jetty "0.1.7"
                  :exclusions [clj-time joda-time org.clojure/clojure]]
                 [luminus-migrations "0.6.6" :exclusions [org.clojure/clojure]]
                 [org.clojure/clojure "1.10.1"]
                 [org.clojure/tools.cli "1.0.194"]
                 [org.clojure/tools.logging "1.1.0"]
                 [org.webjars.npm/bulma "0.8.2"]
                 [org.webjars.npm/material-icons "0.3.1"]
                 [org.webjars/webjars-locator "0.40"]
                 [ring-webjars "0.2.0"]
                 [ring/ring-core "1.8.1"]
                 [ring/ring-defaults "0.3.2"]
                 [ring/ring-servlet "1.7.1"]
                 [org.clojure/data.csv "1.0.0"]
                 [selmer "1.12.27"]
                 [clj-filesystem "0.2.5"]]

  :repositories [["central" "https://maven.aliyun.com/repository/central"]
                 ["jcenter" "https://maven.aliyun.com/repository/jcenter"]
                 ["clojars" "https://mirrors.tuna.tsinghua.edu.cn/clojars/"]
                 ["clojars-official" "https://clojars.org/repo/"]]

  :plugin-repositories [["central" "https://maven.aliyun.com/repository/central"]
                        ["jcenter" "https://maven.aliyun.com/repository/jcenter"]
                        ["clojars" "https://mirrors.tuna.tsinghua.edu.cn/clojars/"]]

  :min-lein-version "2.0.0"

  :source-paths ["src/clj"]
  :test-paths ["test/clj"]
  :resource-paths ["resources"]
  :target-path "target/%s/"
  :main ^:skip-aot tservice.core

  :plugins [[lein-uberwar "0.2.0"]]
  :uberwar
  {:handler tservice.handler/app
   :init tservice.handler/init
   :destroy tservice.handler/destroy
   :name "tservice.war"}


  :profiles
  {:uberjar {:omit-source false
             :aot :all
             :uberjar-name "tservice.jar"
             :source-paths ["env/prod/clj"]
             :resource-paths ["env/prod/resources"]}

   :dev           [:project/dev :profiles/dev]
   :test          [:project/dev :project/test :profiles/test]

   :project/dev  {:jvm-opts ["-Dconf=dev-config.edn"]
                  :dependencies [[directory-naming/naming-java "0.8"]
                                 [luminus-jetty "0.1.9"]
                                 [pjstadig/humane-test-output "0.10.0"]
                                 [prone "2020-01-17"]
                                 [ring/ring-devel "1.8.1"]
                                 [ring/ring-mock "0.4.0"]]
                  :plugins      [[com.jakemccrary/lein-test-refresh "0.24.1"]
                                 [jonase/eastwood "0.3.5"]]

                  :source-paths ["env/dev/clj"]
                  :resource-paths ["env/dev/resources"]
                  :repl-options {:init-ns user
                                 :timeout 120000}
                  :injections [(require 'pjstadig.humane-test-output)
                               (pjstadig.humane-test-output/activate!)]}
   :project/test {:jvm-opts ["-Dconf=test-config.edn"]
                  :resource-paths ["env/test/resources"]}
   :profiles/dev {}
   :profiles/test {}})
