#!/usr/bin/env sh
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

set -eu

before_ref="${1:-ORIG_HEAD}"
after_ref="${2:-HEAD}"

if ! git rev-parse --verify "$before_ref" >/dev/null 2>&1; then
  echo "build-after-merge: $before_ref is not available; skipping frontend build check." >&2
  exit 0
fi

if ! git rev-parse --verify "$after_ref" >/dev/null 2>&1; then
  echo "build-after-merge: $after_ref is not available; skipping frontend build check." >&2
  exit 0
fi

changed_files="$(git diff --name-only "$before_ref" "$after_ref" -- \
  package.json \
  pnpm-lock.yaml \
  pnpm-workspace.yaml \
  .npmrc \
  .mise.toml \
  modules/editor \
  modules/design-system)"

if [ -z "$changed_files" ]; then
  exit 0
fi

echo "Frontend build inputs changed during merge:"
printf '%s\n' "$changed_files" | sed 's/^/  - /'

if ! command -v pnpm >/dev/null 2>&1; then
  echo "pnpm not found; skipping automatic frontend build. Run 'mise install' or install pnpm, then run 'pnpm build'." >&2
  exit 0
fi

echo "Running pnpm build..."
pnpm build
