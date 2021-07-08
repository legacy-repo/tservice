#!/bin/bash

VERSION=$(git describe --tags `git rev-list --tags --max-count=1`)

# dynamically pull more interesting stuff from latest git commit
HASH=$(git show-ref --head --hash=8 head)  # first 8 letters of hash should be enough; that's what GitHub uses

# Build base docker image
docker build -t tservice:${VERSION}-${HASH} .

docker tag tservice:${VERSION}-${HASH} ghcr.io/clinico-omics/tservice:${VERSION}-${HASH}

docker push ghcr.io/clinico-omics/tservice:${VERSION}-${HASH}

# Build docker image for AI
docker build -t tservice-ai:${VERSION}-${HASH} . -f Dockerfile.ai

docker tag tservice-ai:${VERSION}-${HASH} ghcr.io/clinico-omics/tservice-ai:${VERSION}-${HASH}

docker push ghcr.io/clinico-omics/tservice-ai:${VERSION}-${HASH}
