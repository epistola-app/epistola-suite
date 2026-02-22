FROM paketobuildpacks/run-noble-base:latest

USER root

RUN apt-get update -qq \
    && apt-get install -y --no-install-recommends \
        fontconfig \
        fonts-dejavu-core \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*

USER 1002:1000
