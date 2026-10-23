#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

set -euo pipefail

chart_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

# Defaults are closed: private networks off, and no file roots or allowed hosts rendered at all.
defaults="$(helm template catalog-test "${chart_dir}" --set encryption.enabled=false)"
if ! grep -A1 -F 'name: EPISTOLA_CATALOG_OUTBOUND_ALLOWPRIVATENETWORKS' <<<"${defaults}" | grep -Fq 'value: "false"'; then
  echo 'Default render must set EPISTOLA_CATALOG_OUTBOUND_ALLOWPRIVATENETWORKS to "false"' >&2
  exit 1
fi
if ! grep -A1 -F 'name: EPISTOLA_CATALOG_EDITING_ENABLED' <<<"${defaults}" | grep -Fq 'value: "true"'; then
  echo 'Default render must set EPISTOLA_CATALOG_EDITING_ENABLED to "true"' >&2
  exit 1
fi
runonly="$(helm template catalog-test "${chart_dir}" --set encryption.enabled=false --set catalog.editing.enabled=false)"
if ! grep -A1 -F 'name: EPISTOLA_CATALOG_EDITING_ENABLED' <<<"${runonly}" | grep -Fq 'value: "false"'; then
  echo 'catalog.editing.enabled=false must render EPISTOLA_CATALOG_EDITING_ENABLED as "false"' >&2
  exit 1
fi
for absent in EPISTOLA_CATALOG_FILEROOTS EPISTOLA_CATALOG_OUTBOUND_ALLOWEDHOSTS; do
  if grep -Fq "name: ${absent}" <<<"${defaults}"; then
    echo "Default render must not set ${absent}" >&2
    exit 1
  fi
done

configured="$(helm template catalog-test "${chart_dir}" \
  --set encryption.enabled=false \
  --set 'catalog.fileRoots={/srv/catalogs,/mnt/shared}' \
  --set catalog.outbound.allowPrivateNetworks=true \
  --set 'catalog.outbound.allowedHosts={catalogs.intranet.example,codes.intranet.example}')"
for pair in \
  'EPISTOLA_CATALOG_FILEROOTS|"/srv/catalogs,/mnt/shared"' \
  'EPISTOLA_CATALOG_OUTBOUND_ALLOWPRIVATENETWORKS|"true"' \
  'EPISTOLA_CATALOG_OUTBOUND_ALLOWEDHOSTS|"catalogs.intranet.example,codes.intranet.example"'; do
  name="${pair%%|*}"
  value="${pair#*|}"
  if ! grep -A1 -F "name: ${name}" <<<"${configured}" | grep -Fq "value: ${value}"; then
    echo "Expected ${name} to render as ${value}" >&2
    exit 1
  fi
done

# The schema refuses a relative root and a wildcard host rather than rendering something the app rejects.
if helm template catalog-test "${chart_dir}" --set encryption.enabled=false \
  --set 'catalog.fileRoots={srv/catalogs}' >/dev/null 2>&1; then
  echo 'A relative file root must fail schema validation' >&2
  exit 1
fi
if helm template catalog-test "${chart_dir}" --set encryption.enabled=false \
  --set 'catalog.outbound.allowedHosts={*.intranet.example}' >/dev/null 2>&1; then
  echo 'A wildcard allowed host must fail schema validation' >&2
  exit 1
fi

echo 'catalog source settings render as expected'
