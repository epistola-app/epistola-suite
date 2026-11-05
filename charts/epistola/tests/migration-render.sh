#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

set -euo pipefail

chart_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
rendered="$(helm template migration-test "${chart_dir}" --set encryption.enabled=false)"
job="$(awk '/^# Source: epistola\/templates\/migration-job.yaml$/{capture=1} capture{print} capture && /^---$/{exit}' <<<"${rendered}")"

for expected in \
  'kind: Job' \
  'name: migration-test-epistola-migrate' \
  'serviceAccountName: default' \
  'automountServiceAccountToken: false'; do
  if ! grep -Fq "${expected}" <<<"${job}"; then
    echo "Expected migration Job render to contain: ${expected}" >&2
    exit 1
  fi
done

# The rollback guard switch reaches every process that runs the guard: the migration Job, the app
# pods (which validate or migrate), and the init container in initContainer mode.
source_of() {
  awk -v src="# Source: epistola/templates/$1" '$0==src{capture=1;next} capture && /^---$/{exit} capture{print}' <<<"$2"
}

guard_values() {
  grep -A1 -F -- '- name: EPISTOLA_MIGRATION_ROLLBACKGUARD_ENABLED' <<<"$1" | grep -F 'value:' | sed 's/.*value: *//' | sort -u || true
}

expect_guard() {
  local what="$1" expected="$2" actual
  actual="$(guard_values "$3")"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "Expected ${what} to set EPISTOLA_MIGRATION_ROLLBACKGUARD_ENABLED=${expected}, got: ${actual:-nothing}" >&2
    exit 1
  fi
}

expect_guard 'the migration Job by default' '"true"' "${job}"
expect_guard 'the app pods by default' '"true"' "$(source_of deployment.yaml "${rendered}")"

disabled="$(helm template migration-test "${chart_dir}" --set encryption.enabled=false --set migration.rollbackGuard.enabled=false)"
expect_guard 'the migration Job when disabled' '"false"' "$(source_of migration-job.yaml "${disabled}")"
expect_guard 'the app pods when disabled' '"false"' "$(source_of deployment.yaml "${disabled}")"

init="$(helm template migration-test "${chart_dir}" --set encryption.enabled=false --set migration.mode=initContainer --set migration.rollbackGuard.enabled=false)"
# Two containers in one pod: the init container's and the app container's, both off.
if [[ "$(guard_values "$(source_of deployment.yaml "${init}")" | wc -l | tr -d ' ')" != 1 ]] \
  || [[ "$(grep -c -F -- '- name: EPISTOLA_MIGRATION_ROLLBACKGUARD_ENABLED' <<<"$(source_of deployment.yaml "${init}")")" != 2 ]]; then
  echo "Expected both containers of an initContainer-mode pod to set the rollback guard switch" >&2
  exit 1
fi
expect_guard 'an initContainer-mode pod when disabled' '"false"' "$(source_of deployment.yaml "${init}")"
