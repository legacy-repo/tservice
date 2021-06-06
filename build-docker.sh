#!/bin/bash

VERSION=$(git describe --tags `git rev-list --tags --max-count=1`)

# dynamically pull more interesting stuff from latest git commit
HASH=$(git show-ref --head --hash=8 head)  # first 8 letters of hash should be enough; that's what GitHub uses

docker build -t tservice:${VERSION}-${HASH} .