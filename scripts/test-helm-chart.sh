#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

# Exercise the application Helm chart in a disposable local Kind cluster. This
# intentionally uses one ephemeral PostgreSQL container, not a database operator
# or a persistent cluster. Run `mise install` once to install Kind and kubectl.

set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
chart_dir="${repo_root}/charts/epistola"
fixture_dir="${chart_dir}/tests/kind"
cluster_name="epistola-chart-test-$$"
namespace="epistola-chart-test"
timeout="8m"
image_tag=""
keep_cluster=false
completed=false
cluster_created=false

usage() {
  cat <<'EOF'
Usage: scripts/test-helm-chart.sh [--image-tag TAG] [--keep-cluster] [--timeout DURATION]

Runs the Epistola application chart against one temporary Kind cluster and one
ephemeral PostgreSQL container. The cluster is deleted on exit unless
--keep-cluster is supplied.
EOF
}

while (($#)); do
  case "$1" in
    --image-tag)
      image_tag="${2:?--image-tag requires a tag}"
      shift 2
      ;;
    --keep-cluster)
      keep_cluster=true
      shift
      ;;
    --timeout)
      timeout="${2:?--timeout requires a duration}"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

resolve_command() {
  if command -v "$1" >/dev/null; then
    command -v "$1"
  elif command -v mise >/dev/null && mise which "$1" >/dev/null 2>&1; then
    mise which "$1"
  else
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

diagnose() {
  echo >&2
  echo "Chart smoke test failed; cluster diagnostics follow." >&2
  "${kubectl_bin}" --context "kind-${cluster_name}" -n "${namespace}" get all 2>&1 || true
  "${kubectl_bin}" --context "kind-${cluster_name}" -n "${namespace}" get events --sort-by=.lastTimestamp 2>&1 || true
  "${kubectl_bin}" --context "kind-${cluster_name}" -n "${namespace}" describe deployment epistola 2>&1 || true
  "${kubectl_bin}" --context "kind-${cluster_name}" -n "${namespace}" logs deployment/epistola --all-containers=true 2>&1 || true
  "${kubectl_bin}" --context "kind-${cluster_name}" -n "${namespace}" logs deployment/postgres --all-containers=true 2>&1 || true
}

cleanup() {
  local result=$?
  if [[ "${cluster_created}" == true && "${completed}" != true ]]; then
    diagnose
  fi
  if [[ "${cluster_created}" == true && "${keep_cluster}" != true ]]; then
    "${kind_bin}" delete cluster --name "${cluster_name}" || true
  elif [[ "${cluster_created}" == true ]]; then
    echo "Kept Kind cluster ${cluster_name}; use 'kind delete cluster --name ${cluster_name}' when finished."
  fi
  exit "${result}"
}
trap cleanup EXIT

docker_bin="$(resolve_command docker)"
helm_bin="$(resolve_command helm)"
kind_bin="$(resolve_command kind)"
kubectl_bin="$(resolve_command kubectl)"
"${docker_bin}" info >/dev/null

"${helm_bin}" lint "${chart_dir}"
"${chart_dir}/tests/hpa-render.sh"
"${chart_dir}/tests/vpa-render.sh"

"${kind_bin}" create cluster --name "${cluster_name}" --image kindest/node:v1.34.0 --wait 60s
cluster_created=true

"${kubectl_bin}" --context "kind-${cluster_name}" create namespace "${namespace}"
"${kubectl_bin}" --context "kind-${cluster_name}" -n "${namespace}" apply -f "${fixture_dir}/postgres.yaml"
"${kubectl_bin}" --context "kind-${cluster_name}" -n "${namespace}" rollout status deployment/postgres --timeout "${timeout}"

helm_args=(
  upgrade --install epistola "${chart_dir}"
  --namespace "${namespace}"
  --kube-context "kind-${cluster_name}"
  --values "${fixture_dir}/values.yaml"
  --wait
  --timeout "${timeout}"
)
if [[ -n "${image_tag}" ]]; then
  helm_args+=(--set-string "image.tag=${image_tag}")
fi
"${helm_bin}" "${helm_args[@]}"

"${kubectl_bin}" --context "kind-${cluster_name}" -n "${namespace}" rollout status deployment/epistola --timeout "${timeout}"

helm_args+=(--set autoscaling.enabled=true)
"${helm_bin}" "${helm_args[@]}"
test "$("${kubectl_bin}" --context "kind-${cluster_name}" -n "${namespace}" get hpa epistola -o jsonpath='{.spec.scaleTargetRef.kind}')" = Deployment
test "$("${kubectl_bin}" --context "kind-${cluster_name}" -n "${namespace}" get hpa epistola -o jsonpath='{.spec.scaleTargetRef.name}')" = epistola

"${helm_bin}" test epistola --namespace "${namespace}" --kube-context "kind-${cluster_name}" --logs --timeout "${timeout}"

completed=true
echo "Helm chart smoke test passed."
