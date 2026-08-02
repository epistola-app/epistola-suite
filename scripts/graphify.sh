#!/usr/bin/env sh
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

set -eu

UV_VERSION="0.11.29" # renovate: datasource=github-releases depName=astral-sh/uv
GRAPHIFY_VERSION="0.9.32" # renovate: datasource=pypi depName=graphifyy
SCOPE_SCHEMA_VERSION="1"
DEFAULT_QUERY_BUDGET="1500"
DEFAULT_AFFECTED_DEPTH="1"

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
graphify_root="$repo_root/graphify-out"
corpora_root="$graphify_root/corpora"
scoped_root="$graphify_root/scoped"

usage() {
    cat <<'EOF'
Usage: scripts/graphify.sh <command> [arguments]

Commands:
  build [all|backend|editor|support|migrations]
  ensure <backend|editor|support|migrations>
  query <scope> "<question>" [graphify query options]
  affected <scope> "<symbol>" [depth]
  explain <scope> "<symbol>"
  path <scope> "<from>" "<to>"
  benchmark [all|backend|editor|support|migrations]
  status
  verify-scopes
EOF
}

die() {
    printf 'graphify: %s\n' "$*" >&2
    exit 1
}

is_scope() {
    case "$1" in
        backend | editor | support | migrations) return 0 ;;
        *) return 1 ;;
    esac
}

require_scope() {
    [ "$#" -ge 1 ] || die "a scope is required"
    is_scope "$1" || die "unknown scope '$1' (expected backend, editor, support, or migrations)"
}

all_source_files() {
    git -C "$repo_root" ls-files --cached --others --exclude-standard | LC_ALL=C sort -u
}

is_editor_test_file() {
    case "$1" in
        *.test.ts | *.test.js | *.spec.ts | *.spec.js | *-test-helpers.ts | */test-helpers.ts) return 0 ;;
        *) return 1 ;;
    esac
}

scope_for_file() {
    path=$1

    case "$path" in
        */src/test/* | modules/testing/*) return 1 ;;
    esac

    case "$path" in
        apps/*/src/main/resources/db/migration/*.sql | modules/*/src/main/resources/db/migration/*.sql)
            printf '%s\n' migrations
            return 0
            ;;
    esac

    case "$path" in
        modules/editor/src/main/typescript/*.ts | modules/editor/src/main/typescript/*.js)
            if ! is_editor_test_file "$path"; then
                printf '%s\n' editor
                return 0
            fi
            return 1
            ;;
        modules/design-system/icons/generate-sprite.js)
            printf '%s\n' editor
            return 0
            ;;
    esac

    case "$path" in
        apps/epistola/src/main/kotlin/*.kt | \
            apps/epistola/src/main/resources/static/*.js | \
            apps/pdfrender/src/main/kotlin/*.kt | \
            modules/epistola-core/src/main/kotlin/*.kt | \
            modules/epistola-crypto/src/main/kotlin/*.kt | \
            modules/epistola-mcp/src/main/kotlin/*.kt | \
            modules/epistola-web/src/main/kotlin/*.kt | \
            modules/generation/src/main/kotlin/*.kt | \
            modules/rest-api/src/main/kotlin/*.kt)
            printf '%s\n' backend
            return 0
            ;;
    esac

    case "$path" in
        modules/epistola-audit/src/main/kotlin/*.kt | \
            modules/epistola-audit/src/main/resources/static/*.js | \
            modules/epistola-quality/src/main/kotlin/*.kt | \
            modules/epistola-quality/src/main/resources/static/*.js | \
            modules/epistola-support/src/main/kotlin/*.kt | \
            modules/epistola-support/src/main/resources/static/*.js | \
            modules/epistola-support-*/src/main/kotlin/*.kt | \
            modules/epistola-support-*/src/main/resources/static/*.js | \
            modules/epistola-version-check/src/main/kotlin/*.kt | \
            modules/loadtest/src/main/kotlin/*.kt | \
            modules/loadtest/src/main/resources/static/*.js)
            printf '%s\n' support
            return 0
            ;;
    esac

    return 1
}

is_supported_candidate() {
    case "$1" in
        apps/*/src/main/*.kt | apps/*/src/main/*.ts | apps/*/src/main/*.js | apps/*/src/main/*.sql | \
            modules/*/src/main/*.kt | modules/*/src/main/*.ts | modules/*/src/main/*.js | modules/*/src/main/*.sql | \
            modules/design-system/icons/generate-sprite.js)
            return 0
            ;;
        *) return 1 ;;
    esac
}

is_explicitly_excluded() {
    case "$1" in
        modules/testing/*) return 0 ;;
    esac
    is_editor_test_file "$1"
}

list_scope() {
    scope=$1
    require_scope "$scope"
    all_source_files | while IFS= read -r path; do
        assigned=$(scope_for_file "$path" || true)
        if [ "$assigned" = "$scope" ]; then
            printf '%s\n' "$path"
        fi
    done
}

verify_scopes() {
    failures=0
    candidates=0

    while IFS= read -r path; do
        if ! is_supported_candidate "$path"; then
            continue
        fi
        candidates=$((candidates + 1))
        assigned=$(scope_for_file "$path" || true)
        if [ -n "$assigned" ]; then
            continue
        fi
        if is_explicitly_excluded "$path"; then
            continue
        fi
        printf 'Unassigned Graphify source: %s\n' "$path" >&2
        failures=$((failures + 1))
    done <<EOF
$(all_source_files)
EOF

    [ "$failures" -eq 0 ] || die "$failures supported production source file(s) are not assigned to a scope"

    for scope in backend editor support migrations; do
        count=$(list_scope "$scope" | wc -l | tr -d ' ')
        [ "$count" -gt 0 ] || die "scope '$scope' is empty"
        printf '%-10s %s files\n' "$scope" "$count"
    done
    printf 'Scope verification passed (%s supported candidates inspected).\n' "$candidates"
}

run_graphify() {
    command -v mise >/dev/null 2>&1 || die "mise is required; install it and retry"
    mkdir -p "$graphify_root/tooling/uv-cache" "$graphify_root/tooling/uv-tools"
    env \
        UV_CACHE_DIR="$graphify_root/tooling/uv-cache" \
        UV_TOOL_DIR="$graphify_root/tooling/uv-tools" \
        mise x "github:astral-sh/uv@$UV_VERSION" -- \
        uvx --from "graphifyy[sql]==$GRAPHIFY_VERSION" graphify "$@"
}

materialize_scope() {
    scope=$1
    corpus_dir="$corpora_root/$scope"
    temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/epistola-graphify-$scope.XXXXXX")
    temp_corpus="$temp_dir/corpus"
    file_list="$temp_dir/files.txt"
    mkdir -p "$temp_corpus" "$corpus_dir"
    list_scope "$scope" >"$file_list"
    rsync -a --files-from="$file_list" "$repo_root/" "$temp_corpus/"
    rsync -a --delete "$temp_corpus/" "$corpus_dir/"
    rm -rf "$temp_dir"
}

scope_fingerprint() {
    scope=$1
    temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/epistola-graphify-fingerprint-$scope.XXXXXX")
    paths_file="$temp_dir/paths.txt"
    hashes_file="$temp_dir/hashes.txt"
    list_scope "$scope" >"$paths_file"
    git -C "$repo_root" hash-object --stdin-paths <"$paths_file" >"$hashes_file"
    paste "$paths_file" "$hashes_file" | git hash-object --stdin
    rm -rf "$temp_dir"
}

state_compatible() {
    state_file=$1
    [ -f "$state_file" ] || return 1
    grep -qx "graphify_version=$GRAPHIFY_VERSION" "$state_file" && \
        grep -qx "scope_schema_version=$SCOPE_SCHEMA_VERSION" "$state_file"
}

state_matches() {
    state_file=$1
    source_fingerprint=$2
    state_compatible "$state_file" && grep -qx "source_fingerprint=$source_fingerprint" "$state_file"
}

write_state() {
    scope=$1
    state_file=$2
    source_fingerprint=$3
    ensured_commit=$(git -C "$repo_root" rev-parse HEAD)
    ensured_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
    cat >"$state_file" <<EOF
graphify_version=$GRAPHIFY_VERSION
scope_schema_version=$SCOPE_SCHEMA_VERSION
scope=$scope
source_fingerprint=$source_fingerprint
ensured_commit=$ensured_commit
ensured_at=$ensured_at
EOF
}

refresh_scope() {
    scope=$1
    force=${2:-false}
    require_scope "$scope"

    corpus_dir="$corpora_root/$scope"
    output_dir="$scoped_root/$scope"
    graph_path="$output_dir/graph.json"
    state_file="$output_dir/.epistola-state"
    source_fingerprint=$(scope_fingerprint "$scope")

    if [ "$force" != true ] && [ -f "$graph_path" ] && state_matches "$state_file" "$source_fingerprint"; then
        printf 'Graphify scope %s is up to date.\n' "$scope"
        return
    fi

    materialize_scope "$scope"
    mkdir -p "$output_dir"

    full_rebuild=$force
    if [ ! -f "$graph_path" ] || ! state_compatible "$state_file"; then
        full_rebuild=true
    fi

    if [ "$full_rebuild" = true ]; then
        printf 'Building Graphify scope %s...\n' "$scope"
        GRAPHIFY_OUT="$output_dir" run_graphify extract "$corpus_dir" \
            --code-only \
            --force \
            --no-gitignore \
            --max-workers "${GRAPHIFY_MAX_WORKERS:-6}"
    else
        printf 'Refreshing Graphify scope %s...\n' "$scope"
        GRAPHIFY_OUT="$output_dir" run_graphify extract "$corpus_dir" \
            --code-only \
            --no-gitignore \
            --max-workers "${GRAPHIFY_MAX_WORKERS:-6}"
    fi

    GRAPHIFY_OUT="$output_dir" GRAPHIFY_VIZ_NODE_LIMIT="${GRAPHIFY_VIZ_NODE_LIMIT:-10000}" \
        run_graphify cluster-only "$corpus_dir" --graph "$graph_path" --no-label
    write_state "$scope" "$state_file" "$source_fingerprint"
}

for_requested_scopes() {
    requested=$1
    operation=$2
    if [ "$requested" = all ]; then
        for scope in backend editor support migrations; do
            "$operation" "$scope"
        done
    else
        require_scope "$requested"
        "$operation" "$requested"
    fi
}

build_one() {
    refresh_scope "$1" true
}

benchmark_one() {
    scope=$1
    refresh_scope "$scope" false
    run_graphify benchmark "$scoped_root/$scope/graph.json"
}

status() {
    printf 'Pinned Graphify %s via uv %s (scope schema %s)\n' \
        "$GRAPHIFY_VERSION" "$UV_VERSION" "$SCOPE_SCHEMA_VERSION"
    for scope in backend editor support migrations; do
        graph_path="$scoped_root/$scope/graph.json"
        state_file="$scoped_root/$scope/.epistola-state"
        if [ -f "$graph_path" ]; then
            size=$(du -h "$graph_path" | awk '{print $1}')
            ensured_at=$(sed -n 's/^ensured_at=//p' "$state_file" 2>/dev/null || true)
            printf '%-10s ready (%s, ensured %s)\n' "$scope" "$size" "${ensured_at:-unknown}"
        else
            printf '%-10s missing (run scripts/graphify.sh ensure %s)\n' "$scope" "$scope"
        fi
    done
}

command_name=${1:-}
if [ -z "$command_name" ]; then
    usage
    exit 1
fi
shift

case "$command_name" in
    build)
        requested=${1:-all}
        for_requested_scopes "$requested" build_one
        ;;
    ensure)
        [ "$#" -eq 1 ] || die "usage: scripts/graphify.sh ensure <scope>"
        refresh_scope "$1" false
        ;;
    query)
        [ "$#" -ge 2 ] || die "usage: scripts/graphify.sh query <scope> \"<question>\" [options]"
        scope=$1
        question=$2
        shift 2
        refresh_scope "$scope" false
        run_graphify query "$question" --budget "${GRAPHIFY_QUERY_BUDGET:-$DEFAULT_QUERY_BUDGET}" \
            --graph "$scoped_root/$scope/graph.json" "$@"
        ;;
    affected)
        [ "$#" -ge 2 ] && [ "$#" -le 3 ] || die "usage: scripts/graphify.sh affected <scope> \"<symbol>\" [depth]"
        scope=$1
        symbol=$2
        depth=${3:-$DEFAULT_AFFECTED_DEPTH}
        refresh_scope "$scope" false
        run_graphify affected "$symbol" --depth "$depth" --graph "$scoped_root/$scope/graph.json"
        ;;
    explain)
        [ "$#" -eq 2 ] || die "usage: scripts/graphify.sh explain <scope> \"<symbol>\""
        scope=$1
        symbol=$2
        refresh_scope "$scope" false
        run_graphify explain "$symbol" --graph "$scoped_root/$scope/graph.json"
        ;;
    path)
        [ "$#" -eq 3 ] || die "usage: scripts/graphify.sh path <scope> \"<from>\" \"<to>\""
        scope=$1
        from=$2
        to=$3
        refresh_scope "$scope" false
        run_graphify path "$from" "$to" --graph "$scoped_root/$scope/graph.json"
        ;;
    benchmark)
        requested=${1:-all}
        for_requested_scopes "$requested" benchmark_one
        ;;
    status)
        [ "$#" -eq 0 ] || die "usage: scripts/graphify.sh status"
        status
        ;;
    verify-scopes)
        [ "$#" -eq 0 ] || die "usage: scripts/graphify.sh verify-scopes"
        verify_scopes
        ;;
    -h | --help | help)
        usage
        ;;
    *)
        usage >&2
        die "unknown command '$command_name'"
        ;;
esac
