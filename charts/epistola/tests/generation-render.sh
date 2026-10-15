#!/usr/bin/env bash
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

set -euo pipefail

chart_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

# Empty values keep the application defaults: nothing rendered.
defaults="$(helm template generation-test "${chart_dir}" --set encryption.enabled=false)"
for absent in EPISTOLA_GENERATION_FONTCACHE_MAXSIZE EPISTOLA_GENERATION_FONTCACHE_MAXFACES; do
  if grep -Fq "name: ${absent}" <<<"${defaults}"; then
    echo "Default render must not set ${absent}" >&2
    exit 1
  fi
done

configured="$(helm template generation-test "${chart_dir}" \
  --set encryption.enabled=false \
  --set generation.fontCache.maxSize=512MB \
  --set generation.fontCache.maxFaces=32)"
for pair in \
  'EPISTOLA_GENERATION_FONTCACHE_MAXSIZE|"512MB"' \
  'EPISTOLA_GENERATION_FONTCACHE_MAXFACES|"32"'; do
  name="${pair%%|*}"
  value="${pair#*|}"
  if ! grep -A1 -F "name: ${name}" <<<"${configured}" | grep -Fq "value: ${value}"; then
    echo "Expected ${name} to render as ${value}" >&2
    exit 1
  fi
done

# 0 turns the cache off, so it must render although it is falsy.
disabled="$(helm template generation-test "${chart_dir}" --set encryption.enabled=false --set generation.fontCache.maxFaces=0)"
if ! grep -A1 -F 'name: EPISTOLA_GENERATION_FONTCACHE_MAXFACES' <<<"${disabled}" | grep -Fq 'value: "0"'; then
  echo 'maxFaces=0 must render EPISTOLA_GENERATION_FONTCACHE_MAXFACES as "0"' >&2
  exit 1
fi

# The schema refuses values the application could not bind.
for bad in 'generation.fontCache.maxSize=lots' 'generation.fontCache.maxFaces=-1'; do
  if helm template generation-test "${chart_dir}" --set encryption.enabled=false --set "${bad}" >/dev/null 2>&1; then
    echo "Schema must refuse ${bad}" >&2
    exit 1
  fi
done

echo "Font cache rendering checks passed."
