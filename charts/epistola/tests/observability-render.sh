#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

set -euo pipefail

chart_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

port_of() {
  helm template observability-test "${chart_dir}" --set encryption.enabled=false --set observability.prometheus.enabled=true "$@" \
    | sed -n 's/.*prometheus.io\/port: "\([0-9]*\)".*/\1/p' | head -1
}

# The actuator serves on the management port in every profile, so the scrape follows it there.
for profiles in "" "prod" "demo\\,localauth"; do
  port="$(port_of --set-string "config.profiles=${profiles}")"
  if [[ "${port}" != "4040" ]]; then
    echo "Profiles '${profiles}' must scrape port 4040, got '${port}'" >&2
    exit 1
  fi
done

# An explicit port wins.
port="$(port_of --set observability.prometheus.port=9100)"
if [[ "${port}" != "9100" ]]; then
  echo "An explicit scrape port must win, got '${port}'" >&2
  exit 1
fi

# The management port is never on the Service, so no ingress can reach it.
if helm template observability-test "${chart_dir}" --set encryption.enabled=false | awk '/^kind: Service$/,/^---$/' | grep -q '4040'; then
  echo "The Service must not expose the management port" >&2
  exit 1
fi

echo "observability render OK"
