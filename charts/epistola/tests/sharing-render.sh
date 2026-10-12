#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

# The data-sharing values render to the application's environment: shared by default, and the
# no-data example turns every one of them off.
set -euo pipefail

chart_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

env_value() {
  awk -v name="$1" '$0 ~ "- name: " name "$" {getline; sub(/^ *value: /, ""); gsub(/"/, ""); print; exit}' <<<"$2"
}

expect() {
  local rendered="$1" name="$2" expected="$3" actual
  actual="$(env_value "${name}" "${rendered}")"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "Expected ${name}=${expected}, got '${actual}'" >&2
    exit 1
  fi
}

defaults="$(helm template sharing-test "${chart_dir}" --set encryption.enabled=false)"
expect "${defaults}" EPISTOLA_SHARING_INSTALLATIONID true
expect "${defaults}" EPISTOLA_SHARING_SUITEVERSION true
expect "${defaults}" EPISTOLA_SHARING_USAGESTATISTICS true
expect "${defaults}" EPISTOLA_VERSIONCHECK_ENABLED true
expect "${defaults}" EPISTOLA_VERSIONCHECK_WELLKNOWNURL "https://epistola.app/.well-known/epistola/releases.json"
expect "${defaults}" EPISTOLA_INSTALLATIONCHECK_URL "https://epistola.app/installation/{id}.json"

none="$(helm template sharing-test "${chart_dir}" --set encryption.enabled=false -f "${chart_dir}/examples/no-data-to-epistola.yaml")"
expect "${none}" EPISTOLA_SHARING_INSTALLATIONID false
expect "${none}" EPISTOLA_SHARING_SUITEVERSION false
expect "${none}" EPISTOLA_SHARING_USAGESTATISTICS false
expect "${none}" EPISTOLA_VERSIONCHECK_ENABLED false

if helm template sharing-test "${chart_dir}" --set encryption.enabled=false --set sharing.installationId=maybe >/dev/null 2>&1; then
  echo "Expected the schema to reject a non-boolean sharing.installationId" >&2
  exit 1
fi

echo "sharing render: ok"
