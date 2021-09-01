###################
# STAGE 1: builder
###################

# Build currently doesn't work on > Java 11 (i18n utils are busted) so build on 8 until we fix this
FROM adoptopenjdk/openjdk8:centos as builder

WORKDIR /app/source

ENV FC_LANG en-US
ENV LC_CTYPE en_US.UTF-8

# bash:    various shell scripts
# wget:    installing lein
# git:     ./bin/version
# make:    backend building
# gettext: translations
RUN yum install -y coreutils bash git wget make gettext

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
# RUN yum install -y java-cacerts

# ###################
# # STAGE 2: runner
# ###################

FROM adoptopenjdk/openjdk11:centos-jre as runner

LABEL org.opencontainers.image.source https://github.com/clinico-omics/tservice

ARG CONDA_VERSION="4.7.12.1"
ARG CONDA_MD5="81c773ff87af5cfac79ab862942ab6b3"
ARG CONDA_DIR="/opt/conda"
ENV PATH="$PATH:$CONDA_DIR/bin:/app/bin"
ENV PYTHONDONTWRITEBYTECODE=1
ENV FC_LANG en-US
ENV LC_CTYPE en_US.UTF-8

WORKDIR /app

# Install conda
# You can uncomment the following line when you are in Chinese Mainland
# COPY condarc /root/.condarc
RUN echo "**** Install dev packages ****" && \
    yum install -y which bash wget git libgxps && \
    \
    echo "**** Get Miniconda ****" && \
    mkdir -p "$CONDA_DIR" && \
    wget "https://repo.anaconda.com/miniconda/Miniconda3-${CONDA_VERSION}-Linux-x86_64.sh" -O miniconda.sh && \
    echo "$CONDA_MD5  miniconda.sh" | md5sum -c && \
    \
    echo "**** Install Miniconda ****" && \
    bash miniconda.sh -f -b -p "$CONDA_DIR" && \
    \
    echo "**** Setup Miniconda ****" && \
    conda config --set auto_update_conda False && \
    conda config --add channels conda-forge && \
    conda config --add channels bioconda && \
    \
    echo "**** Install dev dependencies by conda ****" && \
    conda install conda-pack r-base=3.6.3 r-renv && \
    \
    echo "**** Install dev dependencies by pip ****" && \
    pip install --no-cache-dir virtualenv clone-env==0.5.4 && \
    \
    echo "**** Cleanup ****" && \
    rm -f miniconda.sh && \
    conda clean --all --force-pkgs-dirs --yes && \
    find "$CONDA_DIR" -follow -type f \( -iname '*.a' -o -iname '*.pyc' -o -iname '*.js.map' \) -delete && \
    yum clean all && \
    \
    echo "**** Finalize ****" && \
    mkdir -p "$CONDA_DIR/locks" && \
    chmod 777 "$CONDA_DIR/locks"

# Add tservice script and uberjar
RUN mkdir -p bin target/uberjar
COPY --from=builder /app/source/target/uberjar/tservice.jar /app/target/uberjar/
COPY --from=builder /app/source/bin /app/bin

# Expose our default runtime port
EXPOSE 3000

# Run it
ENTRYPOINT ["/app/bin/start"]