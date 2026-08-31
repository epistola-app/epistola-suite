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
