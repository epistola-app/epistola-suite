#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

# Encryption on is refused at render time without a key to encrypt with, or with a primary key
# that is none of the keys; a complete keyset renders.
set -euo pipefail

chart_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

refused() {
  local expected="$1"; shift
  local out
  if out="$(helm template encryption-test "${chart_dir}" "$@" 2>&1)"; then
    echo "Expected the chart to refuse: $*" >&2
    exit 1
  fi
  if ! grep -q -- "${expected}" <<<"${out}"; then
    echo "Expected '${expected}' in the refusal, got: ${out}" >&2
    exit 1
  fi
}

refused "encryption.keys must name at least one key" --set encryption.primaryKeyId=k1
refused "names none of encryption.keys" --set encryption.primaryKeyId=k2 \
  --set 'encryption.keys[0].id=k1' --set 'encryption.keys[0].existingSecret=epistola-encryption'

rendered="$(helm template encryption-test "${chart_dir}" --set encryption.primaryKeyId=k1 \
  --set 'encryption.keys[0].id=k1' --set 'encryption.keys[0].existingSecret=epistola-encryption')"
grep -q -- "- name: EPISTOLA_ENCRYPTION_KEYS_0_ID" <<<"${rendered}" || { echo "Expected key k1 rendered" >&2; exit 1; }

helm template encryption-test "${chart_dir}" --set encryption.enabled=false >/dev/null

echo "encryption render: ok"
