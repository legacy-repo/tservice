###################
# STAGE 1: builder
###################

# Build currently doesn't work on > Java 11 (i18n utils are busted) so build on 8 until we fix this
FROM adoptopenjdk/openjdk8:alpine as builder

WORKDIR /app/source

ENV FC_LANG en-US
ENV LC_CTYPE en_US.UTF-8

# bash:    various shell scripts
# wget:    installing lein
# git:     ./bin/version
# make:    backend building
# gettext: translations
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.tuna.tsinghua.edu.cn/g' /etc/apk/repositories
RUN apk add --update coreutils bash git wget make gettext

# lein:    backend dependencies and building
ADD ./bin/lein /usr/local/bin/lein
RUN chmod 744 /usr/local/bin/lein
RUN lein upgrade

# install dependencies before adding the rest of the source to maximize caching

# backend dependencies
ADD project.clj .
RUN lein deps

# add the rest of the source
ADD . .

# build the app
RUN bin/build

# install updated cacerts to /etc/ssl/certs/java/cacerts
RUN apk add --update java-cacerts

# ###################
# # STAGE 2: runner
# ###################

FROM adoptopenjdk/openjdk11:alpine-jre as runner

ARG CONDA_VERSION="4.7.12.1"
ARG CONDA_MD5="81c773ff87af5cfac79ab862942ab6b3"
ARG CONDA_DIR="/opt/conda"
ENV PATH="$CONDA_DIR/bin:$PATH"
ENV PYTHONDONTWRITEBYTECODE=1

# Install conda
COPY condarc /root/.condarc
RUN echo "**** install dev packages ****" && \
  apk add --no-cache --virtual .build-dependencies bash ca-certificates wget && \
  \
  echo "**** get Miniconda ****" && \
  mkdir -p "$CONDA_DIR" && \
  wget "https://mirrors.tuna.tsinghua.edu.cn/anaconda/miniconda/Miniconda3-${CONDA_VERSION}-Linux-x86_64.sh" -O miniconda.sh && \
  echo "$CONDA_MD5  miniconda.sh" | md5sum -c && \
  \
  echo "**** install Miniconda ****" && \
  bash miniconda.sh -f -b -p "$CONDA_DIR" && \
  echo "export PATH=$PATH:$CONDA_DIR/bin" > /etc/profile.d/conda.sh && \
  \
  echo "**** setup Miniconda ****" && \
  conda update --all --yes && \
  conda config --set auto_update_conda False && \
  \
  echo "Initialize conda" && \
  conda init bash && \
  echo "**** cleanup ****" && \
  apk del --purge .build-dependencies && \
  rm -f miniconda.sh && \
  conda clean --all --force-pkgs-dirs --yes && \
  find "$CONDA_DIR" -follow -type f \( -iname '*.a' -o -iname '*.pyc' -o -iname '*.js.map' \) -delete && \
  \
  echo "**** finalize ****" && \
  mkdir -p "$CONDA_DIR/locks" && \
  chmod 777 "$CONDA_DIR/locks"

WORKDIR /app

ENV FC_LANG en-US
ENV LC_CTYPE en_US.UTF-8

# Dependencies
## zip for zipping dependencies of workflow
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.tuna.tsinghua.edu.cn/g' /etc/apk/repositories
# gcc, libc-dev, libxml2 and libxml2-dev for r packages
RUN apk add --update bash ttf-dejavu fontconfig libgxps gcc libc-dev libxml2 libxml2-dev automake git
RUN apk add ca-certificates && update-ca-certificates && apk add openssl

# Install R-base for tservice plugins
RUN conda install r-base=3.6.3

# Install Python Environment
RUN pip3 install poetry

# Add tservice script and uberjar
RUN mkdir -p bin target/uberjar
COPY --from=builder /app/source/target/uberjar/tservice.jar /app/target/uberjar/
COPY --from=builder /app/source/bin /app/bin

# Expose our default runtime port
EXPOSE 3000

# Run it
ENTRYPOINT ["/app/bin/start"]