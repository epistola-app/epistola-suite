#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

# Bearer tokens are held to an audience only when oidc.audiences names one: by default nothing is
# rendered and only the issuer is checked, as before.
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

oidc=(--set encryption.enabled=false --set oidc.enabled=true --set oidc.existingSecret=oidc --set oidc.issuerUri=https://sso.example.com/realms/epistola)

defaults="$(helm template oidc-test "${chart_dir}" "${oidc[@]}")"
if grep -q -- "- name: SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES$" <<<"${defaults}"; then
  echo "Expected no audiences rendered by default" >&2
  exit 1
fi

named="$(helm template oidc-test "${chart_dir}" "${oidc[@]}" --set 'oidc.audiences={epistola-api,epistola-suite}')"
expect "${named}" SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES epistola-api,epistola-suite

echo "oidc render: ok"
