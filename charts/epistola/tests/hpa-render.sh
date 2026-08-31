#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

set -euo pipefail

chart_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

disabled="$(helm template hpa-test "${chart_dir}" --set encryption.enabled=false)"
if grep -q 'kind: HorizontalPodAutoscaler' <<<"${disabled}"; then
  echo 'HPA must not render when autoscaling.enabled is false' >&2
  exit 1
fi

enabled="$(helm template hpa-test "${chart_dir}" \
  --set encryption.enabled=false \
  --set autoscaling.enabled=true)"
for expected in \
  'apiVersion: autoscaling/v2' \
  'kind: HorizontalPodAutoscaler' \
  'kind: Deployment' \
  'name: hpa-test-epistola' \
  'minReplicas: 1' \
  'maxReplicas: 10' \
  'name: cpu' \
  'averageUtilization: 80'; do
  if ! grep -Fq "${expected}" <<<"${enabled}"; then
    echo "Expected enabled HPA render to contain: ${expected}" >&2
    exit 1
  fi
done

if grep -Fq 'name: memory' <<<"${enabled}"; then
  echo 'Default HPA must not use retained JVM memory as a scaling metric' >&2
  exit 1
fi

memory_enabled="$(helm template hpa-test "${chart_dir}" \
  --set encryption.enabled=false \
  --set autoscaling.enabled=true \
  --set autoscaling.targetMemoryUtilizationPercentage=80)"
if ! grep -Fq 'name: memory' <<<"${memory_enabled}"; then
  echo 'HPA must render memory when targetMemoryUtilizationPercentage is enabled' >&2
  exit 1
fi
