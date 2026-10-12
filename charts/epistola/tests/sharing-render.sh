#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

# The data-sharing and product-domain values render to the application's environment: shared by
# default, endpoints found through the product domain unless set, and the no-data example turns
# every one of them off.
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
expect "${defaults}" EPISTOLA_PRODUCTDOMAIN epistola.app
# Endpoints are found through the product domain unless set, so none is rendered by default.
for name in EPISTOLA_VERSIONCHECK_ENDPOINT EPISTOLA_INSTALLATIONCHECK_ENDPOINT EPISTOLA_EXCHANGE_ENDPOINT EPISTOLA_SUPPORT_HUB_ENDPOINT; do
  if grep -q -- "- name: ${name}$" <<<"${defaults}"; then
    echo "Expected ${name} not to be rendered by default" >&2
    exit 1
  fi
done

none="$(helm template sharing-test "${chart_dir}" --set encryption.enabled=false -f "${chart_dir}/examples/no-data-to-epistola.yaml")"
expect "${none}" EPISTOLA_SHARING_INSTALLATIONID false
expect "${none}" EPISTOLA_SHARING_SUITEVERSION false
expect "${none}" EPISTOLA_SHARING_USAGESTATISTICS false
expect "${none}" EPISTOLA_VERSIONCHECK_ENABLED false

mirror="$(helm template sharing-test "${chart_dir}" --set encryption.enabled=false \
  --set productDomain=staging.epistola.app \
  --set versionCheck.endpoint=https://mirror.example/releases.json \
  --set 'installationCheck.endpoint=https://mirror.example/installation/{id}.json' \
  --set exchange.endpoint=https://exchange.internal \
  --set support.enabled=true --set support.hub.endpoint=hub.internal:443 \
  --set support.installation.companyName=Acme --set support.installation.adminEmail=ops@acme.example \
  --set support.installation.environment=test)"
expect "${mirror}" EPISTOLA_PRODUCTDOMAIN staging.epistola.app
expect "${mirror}" EPISTOLA_VERSIONCHECK_ENDPOINT "https://mirror.example/releases.json"
expect "${mirror}" EPISTOLA_INSTALLATIONCHECK_ENDPOINT "https://mirror.example/installation/{id}.json"
expect "${mirror}" EPISTOLA_EXCHANGE_ENDPOINT "https://exchange.internal"
expect "${mirror}" EPISTOLA_SUPPORT_HUB_ENDPOINT "hub.internal:443"

reject() {
  local why="$1"
  shift
  if helm template sharing-test "${chart_dir}" --set encryption.enabled=false "$@" >/dev/null 2>&1; then
    echo "Expected the schema to reject ${why}" >&2
    exit 1
  fi
}

reject "a non-boolean sharing.installationId" --set sharing.installationId=maybe
reject "an installation check endpoint without {id}" --set installationCheck.endpoint=https://mirror.example/installation.json
reject "a relative release metadata endpoint" --set versionCheck.endpoint=mirror.example/releases.json
reject "a product domain given as a URL" --set productDomain=https://epistola.app
reject "a hub endpoint that is not host:port" --set support.hub.endpoint=https://hub.example
# Keys ADR 0030 replaced fail loudly instead of being ignored.
reject "the old versionCheck.wellKnownUrl key" --set versionCheck.wellKnownUrl=https://mirror.example/releases.json
reject "the old installationCheck.url key" --set 'installationCheck.url=https://mirror.example/installation/{id}.json'
reject "the old exchange.discoveryUrl key" --set exchange.discoveryUrl=https://epistola.app/.well-known/epistola/exchange.json
reject "the old support.hub.discoveryUrl key" --set support.hub.discoveryUrl=https://epistola.app/.well-known/epistola/hub.json

echo "sharing render: ok"
