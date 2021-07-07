# Tservice

Make tool as a service.

Many tools are used in the research, but located in each computer. e.g. xps to pdf, convert rnaseq results to a report etc. 

Why can't we do all these things in one place？Tservice is the answer.

## For user
### Using docker

### Download jar package

```
# TODO
```

## For Developer
### Prerequisites

You will need [Leiningen][1] 2.0 or above installed.

[1]: https://github.com/technomancy/leiningen

### Install Dependencies

```bash
lein deps
```

### Running

To start a web server for the application, run:

```bash
lein run 
```

## How to reload application without the need to restart the REPL itself

```
(require '[user :as u])
(u/restart)
```

### How to develop tservice plugin?

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

## License

Copyright © 2015-2021 Eclipse Public License 2.0
