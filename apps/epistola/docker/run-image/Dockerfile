# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

# ubuntu-noble-run is Paketo's maintained Noble run image, rebuilt as Ubuntu ships
# security updates. Its predecessor, run-noble-base, has not been updated since
# April 2024: every image built on it shipped Noble's original OpenSSL, and could
# never pick up a patched package. The build passes --pull so a stale local copy
# of :latest is not reused either.
FROM paketobuildpacks/ubuntu-noble-run:latest

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

# uid 1002 with gid 1000, as on run-noble-base. ubuntu-noble-run's own cnb user has gid 1001,
# but group 1000 still exists there, so keep it: switching the base should not change the user.
USER 1002:1000
