# TService

Make research tool as a service.

Many tools are used in the research, but located in each computer. e.g. xps to pdf, convert rnaseq results to a report etc. 

Why can't we do all these things in one place? TService is the answer.

[![Latest Release](https://img.shields.io/github/v/release/clinico-omics/tservice?sort=semver)](https://github.com/clinico-omics/tservice/releases)
[![Docker Image](https://github.com/clinico-omics/tservice/actions/workflows/publish.yaml/badge.svg)](https://github.com/clinico-omics/tservice/actions/workflows/publish.yaml)
[![License](https://img.shields.io/github/license/clinico-omics/tservice)](https://github.com/clinico-omics/tservice/blob/master/LICENSE.md)

## For user
### Configuration

#### Configuration File Mode
```clojure
{:port 8089
 ;; when :nrepl-port is set the application starts the nREPL server on load
 :nrepl-port 7000
 ;; :database-url "postgresql://localhost:5432/tservice_dev?user=postgres&password=password"
 :database-url "jdbc:sqlite:./tservice/tservice_dev.db"
 :external-bin "/Users/choppy/Documents/Code/ClinicoOmics/ReportEngine/tservice-plugins/external:/Users/choppy/miniconda3/envs/multiqc/bin"
 :tservice-workdir "./tservice"
 :tservice-plugin-path "./tservice/"
 :tservice-run-mode "dev"
 :fs-services [{:fs-service             "minio"
                :fs-endpoint            "http://localhost:9000"
                :fs-access-key          "XXXXXXXXXXXX"
                :fs-secret-key          "XXXXXXXXXXXX"
                :fs-rootdir             "/data/minio"}
               {:fs-service             "oss"
                :fs-endpoint            "http://oss-cn-shanghai.aliyuncs.com"
                :fs-access-key          "XXXXXXXXXXXX"
                :fs-secret-key          "XXXXXXXXXXXX"
                :fs-rootdir             ""}]
 :default-fs-service "minio"
 :tasks {:sync-reports {:cron "0 */1 * * * ?"}}}
```

#### Environment Mode

```bash
# Port
export PORT=3000
# NREPL Port
export NREPL_PORT=7000

# Database(Support PostgreSQL, H2, SQLite)
## PostgreSQL
export DATABASE_URL="postgresql://localhost:5432/tservice_dev?user=postgres&password=password"

## H2
export DATABASE_URL="jdbc:h2:./tservice_dev.db"

## SQLite
export DATABASE_URL="jdbc:sqlite:./tservice_dev.db"

# TService Working Directory
export TSERVICE_WORKDIR=./

# TService Plugin Path
export TSERVICE_PLUGIN_PATH=./
```

### Using docker image

1. Go to the Packages page in the `https://github.com/clinico-omics/tservice`
2. Choose a version
3. Pull your expected package with the following command

```
docker pull ghcr.io/clinico-omics/tservice:v0.3.2-9e3daa48
```

### Download jar package

```
# TODO
```

## For Developer
### Prerequisites

1. You will need [Leiningen][1] 2.0 or above installed.
2. Clone the `tservice` repo
   
   ```
   git clone https://github.com/clinico-omics/tservice.git
   cd tservice
   ```

3. Prepare a configuration file and save as `dev-config.edn` into the `tservice` directory
   
   ```
   ;; WARNING
   ;; The dev-config.edn file is used for local environment variables, such as database credentials.
   ;; This file is listed in .gitignore and will be excluded from version control by Git.

   {:port 3000
    ;; when :nrepl-port is set the application starts the nREPL server on load
    :nrepl-port 7000
    :database-url "postgresql://localhost:5432/tservice_dev?user=postgres&password=password"
    :external-bin "~/miniconda3/envs/multiqc/bin"
    :tservice-workdir "~/Downloads/tservice"
    :tservice-plugin-path "~/Downloads/tservice/"
    :tservice-run-mode "dev"
    :fs-services [{:fs-service             "minio"
                    :fs-endpoint            "http://10.157.72.56:9000"
                    :fs-access-key          "test"
                    :fs-secret-key          "4gmPNjG5JKRXXXXXuxTqO"
                    :fs-rootdir             "/data/minio"}
                  {:fs-service             "oss"
                    :fs-endpoint            "http://oss-cn-shanghai.aliyuncs.com"
                    :fs-access-key          "LTAI4Fi5MEXXXXXzhjEEF43a"
                    :fs-secret-key          "hQhPB8tRFloXXXXXXhKv1GOLdwFVLgt"
                    :fs-rootdir             ""}]
    :default-fs-service "minio"
    :tasks {:sync-reports {:cron "0 */1 * * * ?"}}}
   ```

4. [Install PostgreSQL and create the `tservice_dev` database for development](#build-dev-db)

### Install Dependencies

```bash
lein deps
```

### Running

To start a web server for the application, run:

```bash
lein run 
```

### How to reload application without the need to restart the REPL itself

```
(require '[user :as u])
(u/restart)
```

### How to get a tservice plugin?

```
# TODO
```

### How to develop a tservice plugin?

#### TService Plugin Manifest
```yaml
info:
  name: Quartet DNA-Seq Report
  version: v1.0.1
  description: Parse the results of the quartet-dna-qc app and generate the report.
init:
  # Unpack environment file to the directory, repository/envs/quartet-dnaseq-report
  - step: unpack-env
    envname: quartet-dnaseq-report
  - step: load-namespace
    namespace: tservice.plugins.quartet-dnaseq-report
  - step: register-plugin
    entrypoint: tservice.plugins.quartet-dnaseq-report/metadata
  - step: init-event
    entrypoint: tservice.plugins.quartet-dnaseq-report/events-init
```

### [How to auto-generate docs?](https://github-wiki-see.page/m/weavejester/codox/wiki/Deploying-to-GitHub-Pages)

1. Commit all code modifications
2. Give a tag for the latest commit
3. Build your documentation with `lein codox`
4. To publish your docs to Github Pages, run the following commands
   
   ```
   cd docs
   git add .
   git commit -am "Update docs."
   git push -u origin gh-pages
   cd ..
   ```

### How to build docker image?

```
make build-docker
```

### <span id="build-dev-db">How to create a database for development?</span>

```
make dev-db
```

## License

Copyright © 2015-2021 Eclipse Public License 2.0

[1]: https://github.com/technomancy/leiningen