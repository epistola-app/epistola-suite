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
for name in EPISTOLA_VERSIONCHECK_ENDPOINT EPISTOLA_INSTALLATIONCHECK_ENDPOINT EPISTOLA_EXCHANGE_ENDPOINT EPISTOLA_SUPPORT_ENDPOINT; do
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
  --set support.enabled=true --set support.endpoint=https://support.internal \
  --set support.installation.companyName=Acme --set support.installation.adminEmail=ops@acme.example \
  --set support.installation.environment=test \
  --set support.usage.backlog=48h)"
expect "${mirror}" EPISTOLA_PRODUCTDOMAIN staging.epistola.app
expect "${mirror}" EPISTOLA_VERSIONCHECK_ENDPOINT "https://mirror.example/releases.json"
expect "${mirror}" EPISTOLA_INSTALLATIONCHECK_ENDPOINT "https://mirror.example/installation/{id}.json"
expect "${mirror}" EPISTOLA_EXCHANGE_ENDPOINT "https://exchange.internal"
expect "${mirror}" EPISTOLA_SUPPORT_ENDPOINT "https://support.internal"
# The catalog mirror is on with the support connection, and can be turned off from the chart.
expect "${mirror}" EPISTOLA_SUPPORT_MIRROR_ENABLED true
nomirror="$(helm template sharing-test "${chart_dir}" --set encryption.enabled=false \
  --set support.enabled=true --set support.mirror.enabled=false --set support.tenantDefaults.mirror=marked \
  --set support.installation.companyName=Acme --set support.installation.adminEmail=ops@acme.example \
  --set support.installation.environment=test)"
expect "${nomirror}" EPISTOLA_SUPPORT_MIRROR_ENABLED false
expect "${nomirror}" EPISTOLA_SUPPORT_TENANT_DEFAULTS_MIRROR marked
requestsdefault="$(helm template sharing-test "${chart_dir}" --set encryption.enabled=false \
  --set support.enabled=true --set support.tenantDefaults.requests=share \
  --set support.installation.companyName=Acme --set support.installation.adminEmail=ops@acme.example \
  --set support.installation.environment=test)"
expect "${requestsdefault}" EPISTOLA_SUPPORT_TENANT_DEFAULTS_REQUESTS share
# Telemetry is on with the support connection (the telemetry scope gates it), and can be turned off.
expect "${mirror}" EPISTOLA_SUPPORT_TELEMETRY_ENABLED true
notelemetry="$(helm template sharing-test "${chart_dir}" --set encryption.enabled=false \
  --set support.enabled=true --set support.telemetry.enabled=false \
  --set support.installation.companyName=Acme --set support.installation.adminEmail=ops@acme.example \
  --set support.installation.environment=test)"
expect "${notelemetry}" EPISTOLA_SUPPORT_TELEMETRY_ENABLED false
# Privacy is the suite's default unless set; set, it renders as given.
if grep -q -- "- name: EPISTOLA_SUPPORT_PRIVACY_TENANT_IDENTITY$" <<<"${mirror}"; then
  echo "Expected EPISTOLA_SUPPORT_PRIVACY_TENANT_IDENTITY not to be rendered by default" >&2
  exit 1
fi
named="$(helm template sharing-test "${chart_dir}" --set encryption.enabled=false \
  --set support.enabled=true --set support.privacy.tenantIdentity=named --set support.privacy.telemetry=full \
  --set support.installation.companyName=Acme --set support.installation.adminEmail=ops@acme.example \
  --set support.installation.environment=test)"
expect "${named}" EPISTOLA_SUPPORT_PRIVACY_TENANT_IDENTITY named
expect "${named}" EPISTOLA_SUPPORT_PRIVACY_TELEMETRY full
# The installation registers only when an administrator presses Register, unless auto-register is set.
expect "${mirror}" EPISTOLA_SUPPORT_AUTO_REGISTER false
autoregister="$(helm template sharing-test "${chart_dir}" --set encryption.enabled=false \
  --set support.enabled=true --set support.autoRegister=true \
  --set support.installation.companyName=Acme --set support.installation.adminEmail=ops@acme.example \
  --set support.installation.environment=test)"
expect "${autoregister}" EPISTOLA_SUPPORT_AUTO_REGISTER true
# No service is selected by default: none is rendered, and the application's default (none) applies.
if grep -q -- "- name: EPISTOLA_SUPPORT_SERVICES$" <<<"${mirror}"; then
  echo "Expected EPISTOLA_SUPPORT_SERVICES not to be rendered by default" >&2
  exit 1
fi
# support.services renders as a comma-separated list. A name this chart does not know (a service
# Epistola retired, or one a newer suite offers) is accepted, so a values file that still names it
# never breaks an upgrade; the suite logs it and ignores it.
services="$(helm template sharing-test "${chart_dir}" --set encryption.enabled=false \
  --set support.enabled=true --set 'support.services={feedback,mirror,translation}' \
  --set support.installation.companyName=Acme --set support.installation.adminEmail=ops@acme.example \
  --set support.installation.environment=test)"
expect "${services}" EPISTOLA_SUPPORT_SERVICES "feedback,mirror,translation"

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
reject "a support endpoint that is not a URL" --set support.endpoint=support.example:443
# Plaintext needs epistola.support.allow-http, a local-development setting the chart does not expose.
reject "a plaintext support endpoint" --set support.endpoint=http://support.internal
reject "a non-boolean support.autoRegister" --set support.autoRegister=maybe
reject "an unknown requests preset" --set support.tenantDefaults.requests=maybe
reject "an unknown mirror preset" --set support.tenantDefaults.mirror=some
reject "an unknown tenant identity" --set support.privacy.tenantIdentity=anonymous
reject "a non-boolean support.telemetry.enabled" --set support.telemetry.enabled=maybe
reject "support.services given as one string" --set support.services=feedback
reject "a malformed support service name" --set 'support.services={Feedback Service}'
# Keys ADR 0030 replaced fail loudly instead of being ignored.
reject "the old versionCheck.wellKnownUrl key" --set versionCheck.wellKnownUrl=https://mirror.example/releases.json
reject "the old installationCheck.url key" --set 'installationCheck.url=https://mirror.example/installation/{id}.json'
reject "the old exchange.discoveryUrl key" --set exchange.discoveryUrl=https://epistola.app/.well-known/epistola/exchange.json
reject "the old support.hub.discoveryUrl key" --set support.hub.discoveryUrl=https://epistola.app/.well-known/epistola/hub.json
reject "the old support.hub.endpoint key" --set support.hub.endpoint=hub.internal:443

echo "sharing render: ok"

# Partner connections render on their own, without support.enabled, with the token from a Secret.
partners="$(helm template sharing-test "${chart_dir}" --set encryption.enabled=false \
  --set 'support.connections[0].name=Acme Partner' --set 'support.connections[0].endpoint=https://desk.acme.example' \
  --set 'support.connections[0].services={requests,usage}' --set 'support.connections[0].tokenSecret.name=acme-token' \
  --set 'support.connections[0].tenantIdentity=named' --set 'support.connections[0].escalatesTo=https://support.epistola.app' \
  --set 'support.connections[0].tenantDefaults.requests=share')"
expect "${partners}" EPISTOLA_SUPPORT_CONNECTIONS_0_ESCALATES_TO "https://support.epistola.app"
expect "${partners}" EPISTOLA_SUPPORT_CONNECTIONS_0_TENANT_DEFAULTS_REQUESTS share
expect "${partners}" EPISTOLA_SUPPORT_CONNECTIONS_0_TENANT_IDENTITY named
expect "${partners}" EPISTOLA_SUPPORT_CONNECTIONS_0_NAME "Acme Partner"
expect "${partners}" EPISTOLA_SUPPORT_CONNECTIONS_0_ENDPOINT "https://desk.acme.example"
expect "${partners}" EPISTOLA_SUPPORT_CONNECTIONS_0_SERVICES "requests,usage"
if ! grep -A3 -- "- name: EPISTOLA_SUPPORT_CONNECTIONS_0_TOKEN$" <<<"${partners}" | grep -q "name: \"acme-token\""; then
  echo "Expected EPISTOLA_SUPPORT_CONNECTIONS_0_TOKEN from Secret acme-token" >&2
  exit 1
fi
if helm template sharing-test "${chart_dir}" --set encryption.enabled=false --set 'support.connections[0].name=X' \
  --set 'support.connections[0].endpoint=http://plain.example' --set 'support.connections[0].tokenSecret.name=s' >/dev/null 2>&1; then
  echo "Expected a plaintext partner endpoint to be refused" >&2
  exit 1
fi
# A connection that registers with the installation's own key needs no token.
registering="$(helm template sharing-test "${chart_dir}" --set encryption.enabled=false \
  --set 'support.connections[0].name=Hosting Partner' --set 'support.connections[0].endpoint=https://support.hosting.example' \
  --set 'support.connections[0].auth=register')"
expect "${registering}" EPISTOLA_SUPPORT_CONNECTIONS_0_AUTH register
if grep -q -- "- name: EPISTOLA_SUPPORT_CONNECTIONS_0_TOKEN$" <<<"${registering}"; then
  echo "Expected no token for a connection that registers" >&2
  exit 1
fi
if helm template sharing-test "${chart_dir}" --set encryption.enabled=false --set 'support.connections[0].name=X' \
  --set 'support.connections[0].endpoint=https://token.example' >/dev/null 2>&1; then
  echo "Expected a token connection without tokenSecret to be refused" >&2
  exit 1
fi
# Who handles new support requests: inbox or a connection's endpoint, no keyword.
handled="$(helm template sharing-test "${chart_dir}" --set encryption.enabled=false --set 'support.requests.handledBy=https://support.epistola.app')"
expect "${handled}" EPISTOLA_SUPPORT_REQUESTS_HANDLEDBY "https://support.epistola.app"
if helm template sharing-test "${chart_dir}" --set encryption.enabled=false --set 'support.requests.handledBy=epistola' >/dev/null 2>&1; then
  echo "Expected handledBy=epistola to be refused" >&2
  exit 1
fi
# The usage backlog renders when set, and is left to the suite's default otherwise.
expect "${mirror}" EPISTOLA_SUPPORT_USAGE_BACKLOG 48h
if grep -q -- "- name: EPISTOLA_SUPPORT_USAGE_BACKLOG$" <<<"${defaults}"; then
  echo "Expected EPISTOLA_SUPPORT_USAGE_BACKLOG not to be rendered by default" >&2
  exit 1
fi
