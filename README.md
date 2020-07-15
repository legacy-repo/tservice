# Tservice

Make tool as a service.

Many tools are used in the research, but located in each computer. e.g. xps to pdf, convert rnaseq results to a report etc. 

Why can't we do all these things in one place？Tservice is the answer.

## Prerequisites

You will need [Leiningen][1] 2.0 or above installed.

[1]: https://github.com/technomancy/leiningen

## Install Dependencies

```bash
lein deps
```

## Running

To start a web server for the application, run:

```bash
lein run 
```

## How to reload application without the need to restart the REPL itself

```
(require '[user :as u])
(u/restart)
```

## License

Copyright © 2019 FIXME
