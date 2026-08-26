#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

set -euo pipefail

chart_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
render_args=(--set encryption.enabled=false)

disabled="$(helm template vpa-test "${chart_dir}" "${render_args[@]}")"
if grep -q 'kind: VerticalPodAutoscaler' <<<"${disabled}"; then
  echo 'VPA must not render when vpa.enabled is false' >&2
  exit 1
fi

enabled="$(helm template vpa-test "${chart_dir}" "${render_args[@]}" --set vpa.enabled=true)"
for expected in \
  'apiVersion: autoscaling.k8s.io/v1' \
  'kind: VerticalPodAutoscaler' \
  'name: vpa-test-epistola' \
  'kind: Deployment' \
  'updateMode: "Off"' \
  'minReplicas: 1' \
  'containerName: epistola' \
  'controlledValues: "RequestsOnly"' \
  'cpu: 750m' \
  'memory: 1536Mi' \
  'memory: 5Gi'; do
  if ! grep -Fq "${expected}" <<<"${enabled}"; then
    echo "Expected enabled VPA render to contain: ${expected}" >&2
    exit 1
  fi
done

custom="$(helm template vpa-test "${chart_dir}" \
  "${render_args[@]}" \
  --set vpa.enabled=true \
  --set vpa.updateMode=Initial \
  --set vpa.minReplicas=3 \
  --set vpa.controlledValues=RequestsAndLimits \
  --set vpa.resourcePolicy.minAllowed.cpu=250m \
  --set vpa.resourcePolicy.maxAllowed.memory=2Gi)"
for expected in \
  'updateMode: "Initial"' \
  'minReplicas: 3' \
  'controlledValues: "RequestsAndLimits"' \
  'cpu: 250m' \
  'memory: 2Gi'; do
  if ! grep -Fq "${expected}" <<<"${custom}"; then
    echo "Expected configurable VPA render to contain: ${expected}" >&2
    exit 1
  fi
done
