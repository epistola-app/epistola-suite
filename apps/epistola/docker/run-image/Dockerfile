# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

FROM paketobuildpacks/run-noble-base:latest

# Pin the container locale to UTF-8. run-noble-base boots with the C/POSIX
# locale, which makes the JVM resolve sun.jnu.encoding to ASCII and mis-handle
# non-ASCII filenames, command-line args and environment values. C.UTF-8 ships
# with glibc on Ubuntu Noble, so no `locales` package is needed. This is a
# complement to the application-level request-encoding pin (RequestEncodingConfig);
# it does NOT affect HTTP form-body decoding, which is a Servlet-spec concern.
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

USER root

RUN apt-get update -qq \
    && apt-get install -y --no-install-recommends \
        fontconfig \
        fonts-dejavu-core \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*

USER 1002:1000
