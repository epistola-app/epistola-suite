#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

set -euo pipefail

chart_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
render_args=(--set encryption.enabled=false)

fail() {
  echo "$1" >&2
  exit 1
}

expect_all() {
  local label="$1" rendered="$2"
  shift 2
  for expected in "$@"; do
    grep -Fq -- "${expected}" <<<"${rendered}" || fail "Expected ${label} to contain: ${expected}"
  done
}

deployment() {
  helm template hpa-test "${chart_dir}" "${render_args[@]}" --show-only templates/deployment.yaml "$@"
}

notes() {
  helm install hpa-test "${chart_dir}" --dry-run=client "${render_args[@]}" "$@" \
    | sed -n '/^NOTES:/,$p'
}

disabled="$(helm template hpa-test "${chart_dir}" "${render_args[@]}")"
if grep -q 'kind: HorizontalPodAutoscaler' <<<"${disabled}"; then
  fail 'HPA must not render when autoscaling.hpa.enabled is false'
fi
grep -Eq '^  replicas: 1$' <<<"$(deployment)" || fail 'Deployment must set replicas when the HPA is disabled'

enabled="$(helm template hpa-test "${chart_dir}" "${render_args[@]}" --set autoscaling.hpa.enabled=true)"
expect_all 'enabled HPA render' "${enabled}" \
  'apiVersion: autoscaling/v2' \
  'kind: HorizontalPodAutoscaler' \
  'kind: Deployment' \
  'name: hpa-test-epistola' \
  'minReplicas: 1' \
  'maxReplicas: 10' \
  'name: cpu' \
  'averageUtilization: 80'
if grep -Fq 'name: memory' <<<"${enabled}"; then
  fail 'Default HPA must not use retained JVM memory as a scaling metric'
fi
# Scale-down aborts renders still running after a short drain (they are
# re-queued), so the default
# removes at most one pod per period after a stabilisation window.
expect_all 'default HPA behavior' "${enabled}" \
  'behavior:' \
  'scaleDown:' \
  'stabilizationWindowSeconds: 300' \
  'type: Pods' \
  'periodSeconds: 180'
no_behavior="$(helm template hpa-test "${chart_dir}" "${render_args[@]}" \
  --set autoscaling.hpa.enabled=true --set autoscaling.hpa.behavior=null)"
if grep -Fq 'behavior:' <<<"${no_behavior}"; then
  fail 'autoscaling.hpa.behavior=null must leave the Kubernetes defaults'
fi
if grep -Eq '^  replicas:' <<<"$(deployment --set autoscaling.hpa.enabled=true)"; then
  fail 'Deployment must leave replicas to the HPA when autoscaling.hpa.enabled is true'
fi

memory_enabled="$(helm template hpa-test "${chart_dir}" "${render_args[@]}" \
  --set autoscaling.hpa.enabled=true \
  --set autoscaling.hpa.targetMemoryUtilizationPercentage=80)"
grep -Fq 'name: memory' <<<"${memory_enabled}" \
  || fail 'HPA must render memory when autoscaling.hpa.targetMemoryUtilizationPercentage is enabled'

# Deprecated flat keys (chart 1.0.0) keep working, each one winning over its
# autoscaling.hpa.* counterpart, and an unset flat key falls back to it.
legacy="$(helm template hpa-test "${chart_dir}" "${render_args[@]}" \
  --set autoscaling.enabled=true \
  --set autoscaling.maxReplicas=7 \
  --set autoscaling.targetMemoryUtilizationPercentage=80 \
  --set autoscaling.hpa.minReplicas=2)"
expect_all 'legacy HPA render' "${legacy}" \
  'kind: HorizontalPodAutoscaler' \
  'minReplicas: 2' \
  'maxReplicas: 7' \
  'name: memory'
if grep -Eq '^  replicas:' <<<"$(deployment --set autoscaling.enabled=true)"; then
  fail 'Deployment must leave replicas to the HPA when the deprecated autoscaling.enabled is true'
fi

legacy_notes="$(notes --set autoscaling.enabled=true)"
expect_all 'NOTES for deprecated keys' "${legacy_notes}" 'DEPRECATED' 'autoscaling.enabled' 'autoscaling.hpa'
if notes | grep -Fq 'DEPRECATED'; then
  fail 'NOTES must not warn when no deprecated autoscaling key is set'
fi

# Mixing styles is refused: a leftover 1.0.0 `autoscaling.enabled: false` would
# otherwise silently switch off an HPA enabled through autoscaling.hpa.
if mixed="$(helm template hpa-test "${chart_dir}" "${render_args[@]}" \
  --set autoscaling.hpa.enabled=true \
  --set autoscaling.enabled=false 2>&1)"; then
  fail 'Render must fail when autoscaling.hpa.enabled is combined with deprecated flat keys'
fi
grep -Fq 'autoscaling.hpa' <<<"${mixed}" || fail "Mixed-style failure must point at autoscaling.hpa, got: ${mixed}"
