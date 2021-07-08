# Tservice

Make tool as a service.

Many tools are used in the research, but located in each computer. e.g. xps to pdf, convert rnaseq results to a report etc. 

Why can't we do all these things in one place？Tservice is the answer.

## For user
### Using docker image

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

#### Tservice Plugin Manifest
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

### How to auto-generate docs?

1. Commit all code modifications
2. Give a tag for the latest commit
3. Switch to the gh-pages branch
4. Merge master into the gh-pages branch
5. Run the `lein codox` to generate docs
6. Commit all modifications and push the gh-pages branch to the GitHub

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