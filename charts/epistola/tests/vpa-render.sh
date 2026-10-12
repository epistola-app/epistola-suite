#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

set -euo pipefail

chart_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
render_args=(--set encryption.enabled=false)

disabled="$(helm template vpa-test "${chart_dir}" "${render_args[@]}")"
if grep -q 'kind: VerticalPodAutoscaler' <<<"${disabled}"; then
  echo 'VPA must not render when autoscaling.vpa.enabled is false' >&2
  exit 1
fi

enabled="$(helm template vpa-test "${chart_dir}" "${render_args[@]}" --set autoscaling.vpa.enabled=true)"
for expected in \
  'apiVersion: autoscaling.k8s.io/v1' \
  'kind: VerticalPodAutoscaler' \
  'name: vpa-test-epistola' \
  'kind: Deployment' \
  'updateMode: "Off"' \
  'minReplicas: 2' \
  'containerName: epistola' \
  'controlledValues: "RequestsOnly"' \
  '- cpu' \
  '- memory' \
  'cpu: 750m' \
  'memory: 1536Mi' \
  'cpu: "3"' \
  'memory: 4Gi'; do
  if ! grep -Fq -- "${expected}" <<<"${enabled}"; then
    echo "Expected enabled VPA render to contain: ${expected}" >&2
    exit 1
  fi
done

# The VPA lives under autoscaling, but autoscaling.enabled still means the HPA:
# enabling the VPA alone must not render an HPA.
if grep -q 'kind: HorizontalPodAutoscaler' <<<"${enabled}"; then
  echo 'Enabling autoscaling.vpa must not render an HPA' >&2
  exit 1
fi

custom="$(helm template vpa-test "${chart_dir}" \
  "${render_args[@]}" \
  --set autoscaling.vpa.enabled=true \
  --set autoscaling.vpa.updateMode=Initial \
  --set autoscaling.vpa.minReplicas=3 \
  --set autoscaling.vpa.controlledValues=RequestsAndLimits \
  --set autoscaling.vpa.resourcePolicy.minAllowed.cpu=250m \
  --set autoscaling.vpa.resourcePolicy.maxAllowed.memory=2Gi)"
for expected in \
  'updateMode: "Initial"' \
  'minReplicas: 3' \
  'controlledValues: "RequestsAndLimits"' \
  'cpu: 250m' \
  'memory: 2Gi'; do
  if ! grep -Fq -- "${expected}" <<<"${custom}"; then
    echo "Expected configurable VPA render to contain: ${expected}" >&2
    exit 1
  fi
done

# values.schema.json validates the VPA block at its autoscaling.vpa location.
if helm template vpa-test "${chart_dir}" "${render_args[@]}" \
  --set autoscaling.vpa.updateMode=Sometimes >/dev/null 2>&1; then
  echo 'Schema must reject an unknown autoscaling.vpa.updateMode' >&2
  exit 1
fi

# VPA in-place resize (Kubernetes >= 1.33, recent VPA releases) is accepted.
in_place="$(helm template vpa-test "${chart_dir}" "${render_args[@]}" \
  --set autoscaling.vpa.enabled=true \
  --set autoscaling.vpa.updateMode=InPlaceOrRecreate)"
grep -Fq 'updateMode: "InPlaceOrRecreate"' <<<"${in_place}" \
  || { echo 'VPA must accept updateMode InPlaceOrRecreate' >&2; exit 1; }

# controlledResources narrows what the VPA manages.
memory_only="$(helm template vpa-test "${chart_dir}" "${render_args[@]}" \
  --set autoscaling.vpa.enabled=true \
  --set 'autoscaling.vpa.controlledResources={memory}' \
  --show-only templates/vpa.yaml)"
if grep -Fq -- '- cpu' <<<"${memory_only}"; then
  echo 'VPA controlledResources={memory} must not control cpu' >&2
  exit 1
fi

# An acting VPA must never manage a resource the HPA scales on: a VPA-raised
# request lowers the HPA's utilisation, which scales in, which raises usage...
vpa_hpa() {
  helm template vpa-test "${chart_dir}" "${render_args[@]}" \
    --set autoscaling.hpa.enabled=true --set autoscaling.vpa.enabled=true "$@"
}
if out="$(vpa_hpa --set autoscaling.vpa.updateMode=Initial 2>&1)"; then
  echo 'Render must fail when an acting VPA controls the cpu the HPA scales on' >&2
  exit 1
fi
grep -Fq 'controlledResources' <<<"${out}" || { echo "Conflict failure must name controlledResources, got: ${out}" >&2; exit 1; }
if out="$(vpa_hpa --set autoscaling.vpa.updateMode=Initial \
  --set 'autoscaling.vpa.controlledResources={cpu}' \
  --set autoscaling.hpa.targetCPUUtilizationPercentage=0 \
  --set autoscaling.hpa.targetMemoryUtilizationPercentage=80 2>&1)"; then
  :
else
  echo "HPA on memory with a cpu-only VPA must render, got: ${out}" >&2
  exit 1
fi
vpa_hpa >/dev/null 2>&1 || { echo 'A recommendation-only (Off) VPA must render beside the HPA' >&2; exit 1; }
vpa_hpa --set autoscaling.vpa.updateMode=Recreate --set 'autoscaling.vpa.controlledResources={memory}' >/dev/null 2>&1 \
  || { echo 'HPA on cpu with a memory-only VPA must render' >&2; exit 1; }
if vpa_hpa --set autoscaling.vpa.updateMode=Recreate --set 'autoscaling.vpa.controlledResources={memory}' \
  --set autoscaling.hpa.targetMemoryUtilizationPercentage=80 >/dev/null 2>&1; then
  echo 'Render must fail when an acting VPA controls the memory the HPA scales on' >&2
  exit 1
fi
