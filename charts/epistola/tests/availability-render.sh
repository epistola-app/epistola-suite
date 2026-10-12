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

render() {
  helm template avail-test "${chart_dir}" "${render_args[@]}" "$@"
}

notes() {
  helm install avail-test "${chart_dir}" --dry-run=client "${render_args[@]}" "$@" \
    | sed -n '/^NOTES:/,$p'
}

# --- PodDisruptionBudget ---------------------------------------------------

if render | grep -q 'kind: PodDisruptionBudget'; then
  fail 'PDB must not render when podDisruptionBudget.enabled is false'
fi

pdb="$(render --set podDisruptionBudget.enabled=true --set replicaCount=2 \
  --show-only templates/pdb.yaml)"
for expected in \
  'apiVersion: policy/v1' \
  'kind: PodDisruptionBudget' \
  'name: avail-test-epistola' \
  'minAvailable: 1' \
  'app.kubernetes.io/instance: avail-test'; do
  grep -Fq "${expected}" <<<"${pdb}" || fail "Expected PDB render to contain: ${expected}"
done

max_unavailable="$(render --set podDisruptionBudget.enabled=true \
  --set podDisruptionBudget.minAvailable=null \
  --set podDisruptionBudget.maxUnavailable=1 --show-only templates/pdb.yaml)"
grep -Fq 'maxUnavailable: 1' <<<"${max_unavailable}" || fail 'PDB must render maxUnavailable'
if grep -Fq 'minAvailable' <<<"${max_unavailable}"; then
  fail 'PDB must not render minAvailable once it is cleared'
fi

if render --set podDisruptionBudget.enabled=true --set podDisruptionBudget.maxUnavailable=1 \
  >/dev/null 2>&1; then
  fail 'PDB must refuse minAvailable and maxUnavailable together'
fi

# With a single replica, minAvailable: 1 blocks every eviction — node drains
# hang. Warn, so the operator raises replicas rather than discovering it then.
grep -Fq 'podDisruptionBudget' <<<"$(notes --set podDisruptionBudget.enabled=true)" \
  || fail 'NOTES must warn when the PDB is combined with a single replica'
if notes --set podDisruptionBudget.enabled=true --set replicaCount=2 | grep -Fq 'podDisruptionBudget'; then
  fail 'NOTES must not warn when two replicas back the PDB'
fi
grep -Fq 'podDisruptionBudget' <<<"$(notes --set podDisruptionBudget.enabled=true \
  --set replicaCount=3 --set autoscaling.hpa.enabled=true)" \
  || fail 'NOTES must judge the PDB by autoscaling.hpa.minReplicas when the HPA owns replicas'

# --- Pod anti-affinity ---------------------------------------------------------

deployment() {
  render --show-only templates/deployment.yaml "$@"
}

soft="$(deployment)"
for expected in \
  'podAntiAffinity:' \
  'preferredDuringSchedulingIgnoredDuringExecution:' \
  'topologyKey: kubernetes.io/hostname' \
  'app.kubernetes.io/instance: avail-test'; do
  grep -Fq "${expected}" <<<"${soft}" || fail "Expected default soft anti-affinity to contain: ${expected}"
done

grep -Fq 'requiredDuringSchedulingIgnoredDuringExecution:' <<<"$(deployment --set podAntiAffinity=hard)" \
  || fail 'podAntiAffinity=hard must render a required rule'

if deployment --set podAntiAffinity=none | grep -Fq 'affinity:'; then
  fail 'podAntiAffinity=none must render no affinity'
fi

explicit="$(deployment --set 'affinity.nodeAffinity.requiredDuringSchedulingIgnoredDuringExecution.nodeSelectorTerms[0].matchExpressions[0].key=zone' \
  --set 'affinity.nodeAffinity.requiredDuringSchedulingIgnoredDuringExecution.nodeSelectorTerms[0].matchExpressions[0].operator=Exists')"
grep -Fq 'nodeAffinity:' <<<"${explicit}" || fail 'An explicit affinity must render'
if grep -Fq 'podAntiAffinity:' <<<"${explicit}"; then
  fail 'An explicit affinity must replace the podAntiAffinity preset'
fi

if render --set podAntiAffinity=sometimes >/dev/null 2>&1; then
  fail 'Schema must reject an unknown podAntiAffinity'
fi

# --- Termination grace period --------------------------------------------------

grep -Fq 'terminationGracePeriodSeconds: 60' <<<"$(deployment)" \
  || fail 'Deployment must default terminationGracePeriodSeconds to 60 (room for the render drain)'
grep -Fq 'terminationGracePeriodSeconds: 120' <<<"$(deployment --set terminationGracePeriodSeconds=120)" \
  || fail 'terminationGracePeriodSeconds must be configurable'
