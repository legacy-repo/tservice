#!/bin/bash
# WARNING
# The create-db.sh file is used for local postgres database.
# This file is listed in .gitignore and will be excluded from version control by Git.
set -e # exit immediately if a command exits with a non-zero status.

export PGPASSWORD=password
POSTGRES="psql -h localhost -p $2 --username postgres"

if [ -z "$1" ]; then
  database="tservice_dev"
else
  database="$1"
fi

# create database for superset
$POSTGRES <<EOSQL
CREATE DATABASE $database OWNER postgres;
EOSQL
