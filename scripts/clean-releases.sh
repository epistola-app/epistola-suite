#!/usr/bin/env bash

set -euo pipefail

# Disable pager for gh commands to prevent interactive prompts
export GH_PAGER=""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
DRY_RUN=false
KEEP_LATEST=0
DELETE_RELEASES=true
DELETE_PACKAGES=true
CONFIRMED=false

# Usage information
usage() {
    cat << EOF
Usage: $(basename "$0") [OPTIONS]

Delete GitHub releases and container packages (by default, both are deleted).

OPTIONS:
    -d, --dry-run           Show what would be deleted without actually deleting
    -k, --keep N            Keep the N most recent releases and packages (default: 0 = delete all)
    --releases-only         Delete only releases, skip packages
    --packages-only         Delete only packages, skip releases
    -y, --yes               Skip confirmation prompt
    -h, --help              Show this help message

EXAMPLES:
    # Dry run - see what would be deleted (both releases and packages)
    $(basename "$0") --dry-run

    # Delete all releases and packages with confirmation
    $(basename "$0")

    # Keep the 3 most recent releases and 3 most recent package versions
    $(basename "$0") --keep 3

    # Delete only releases, keep packages
    $(basename "$0") --releases-only

    # Delete only packages, keep releases
    $(basename "$0") --packages-only

    # Delete only releases, keep 5 most recent
    $(basename "$0") --releases-only --keep 5

    # Dry run, keep 2 of each
    $(basename "$0") --dry-run --keep 2

NOTE:
  - This script only deletes GitHub releases, not the underlying git tags.
  - Package versions (container images) are permanently deleted and cannot be recovered.
  - By default, BOTH releases and packages are deleted.
EOF
    exit 0
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -d|--dry-run)
            DRY_RUN=true
            shift
            ;;
        -k|--keep)
            KEEP_LATEST="$2"
            shift 2
            ;;
        --releases-only)
            DELETE_PACKAGES=false
            shift
            ;;
        --packages-only)
            DELETE_RELEASES=false
            shift
            ;;
        -y|--yes)
            CONFIRMED=true
            shift
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo -e "${RED}Error: Unknown option $1${NC}" >&2
            usage
            ;;
    esac
done

# Validate --keep argument
if ! [[ "$KEEP_LATEST" =~ ^[0-9]+$ ]]; then
    echo -e "${RED}Error: --keep must be a non-negative integer${NC}" >&2
    exit 1
fi

# Validate flag conflicts
if [[ $DELETE_RELEASES == false ]] && [[ $DELETE_PACKAGES == false ]]; then
    echo -e "${RED}Error: Cannot use --releases-only and --packages-only together${NC}" >&2
    exit 1
fi

# Check if gh CLI is installed
if ! command -v gh &> /dev/null; then
    echo -e "${RED}Error: GitHub CLI (gh) is not installed${NC}" >&2
    echo "Install it from: https://cli.github.com/" >&2
    exit 1
fi

# Check if we're in a git repository
if ! git rev-parse --git-dir > /dev/null 2>&1; then
    echo -e "${RED}Error: Not in a git repository${NC}" >&2
    exit 1
fi

# Get repository owner and name
REPO_INFO=$(gh repo view --json owner,name --jq '.owner.login + "/" + .name')
REPO_OWNER=$(echo "$REPO_INFO" | cut -d'/' -f1)
REPO_NAME=$(echo "$REPO_INFO" | cut -d'/' -f2)

# Get all releases, sorted by creation date (newest first)
ALL_RELEASES=()
TOTAL_RELEASES=0

if [[ $DELETE_RELEASES == true ]]; then
    echo -e "${BLUE}Fetching releases from GitHub...${NC}"
    while IFS= read -r tag; do
        ALL_RELEASES+=("$tag")
    done < <(gh release list --limit 1000 --json tagName,isLatest,createdAt --jq '.[] | .tagName')
    TOTAL_RELEASES=${#ALL_RELEASES[@]}
fi

# Fetch packages if requested
PACKAGE_VERSIONS_TO_DELETE=()
PACKAGE_VERSIONS_TO_KEEP=()
TOTAL_PACKAGE_VERSIONS=0
PACKAGE_VERSIONS_DELETE_COUNT=0
PACKAGE_VERSIONS_KEEP_COUNT=0

if [[ $DELETE_PACKAGES == true ]]; then
    echo -e "${BLUE}Fetching packages from GitHub...${NC}"

    # Fetch container packages from organization using REST API
    # Container packages are stored at org/user level, not repository level
    PACKAGES_JSON=$(gh api "/orgs/$REPO_OWNER/packages?package_type=container&per_page=100" 2>/dev/null || \
                    gh api "/users/$REPO_OWNER/packages?package_type=container&per_page=100" 2>/dev/null || echo "[]")

    # Process each package
    if [[ -n "$PACKAGES_JSON" && "$PACKAGES_JSON" != "null" && "$PACKAGES_JSON" != "[]" ]]; then
        while IFS= read -r package_line; do
            [[ -z "$package_line" ]] && continue

            pkg_name=$(echo "$package_line" | jq -r '.name')
            pkg_type=$(echo "$package_line" | jq -r '.package_type')

            # URL-encode package name for API call (replace / with %2F)
            pkg_name_encoded="${pkg_name//\//%2F}"

            # Fetch versions for this package
            pkg_versions=$(gh api "/orgs/$REPO_OWNER/packages/container/$pkg_name_encoded/versions?per_page=100" 2>/dev/null || \
                          gh api "/users/$REPO_OWNER/packages/container/$pkg_name_encoded/versions?per_page=100" 2>/dev/null || echo "[]")

            # Get all version IDs for this package
            version_ids=()
            version_names=()
            while IFS='|' read -r version_id version_tags; do
                [[ -z "$version_id" ]] && continue
                version_ids+=("$version_id")
                # Use first meaningful tag or show as "untagged"
                version_names+=("$version_tags")
                ((TOTAL_PACKAGE_VERSIONS++))
            done < <(echo "$pkg_versions" | jq -r '.[] | "\(.id)|\(if (.metadata.container.tags | length) > 0 then .metadata.container.tags[0] else "untagged" end)"')

            # Determine which versions to keep/delete (same as releases)
            if [[ $KEEP_LATEST -gt 0 ]] && [[ ${#version_ids[@]} -gt $KEEP_LATEST ]]; then
                # Keep first N versions
                for ((i=0; i<KEEP_LATEST; i++)); do
                    PACKAGE_VERSIONS_TO_KEEP+=("${pkg_name}|${pkg_type}|${version_ids[$i]}|${version_names[$i]}")
                    ((PACKAGE_VERSIONS_KEEP_COUNT++))
                done
                # Delete the rest
                for ((i=KEEP_LATEST; i<${#version_ids[@]}; i++)); do
                    PACKAGE_VERSIONS_TO_DELETE+=("${pkg_name}|${pkg_type}|${version_ids[$i]}|${version_names[$i]}")
                    ((PACKAGE_VERSIONS_DELETE_COUNT++))
                done
            elif [[ $KEEP_LATEST -eq 0 ]]; then
                # Delete all versions
                for ((i=0; i<${#version_ids[@]}; i++)); do
                    PACKAGE_VERSIONS_TO_DELETE+=("${pkg_name}|${pkg_type}|${version_ids[$i]}|${version_names[$i]}")
                    ((PACKAGE_VERSIONS_DELETE_COUNT++))
                done
            else
                # Keep all versions (KEEP_LATEST >= total versions)
                for ((i=0; i<${#version_ids[@]}; i++)); do
                    PACKAGE_VERSIONS_TO_KEEP+=("${pkg_name}|${pkg_type}|${version_ids[$i]}|${version_names[$i]}")
                    ((PACKAGE_VERSIONS_KEEP_COUNT++))
                done
            fi
        done < <(echo "$PACKAGES_JSON" | jq -c '.[]')
    fi
fi

# Check if there's anything to do
if [[ $TOTAL_RELEASES -eq 0 ]] && [[ $TOTAL_PACKAGE_VERSIONS -eq 0 ]]; then
    echo -e "${GREEN}No releases or packages found. Nothing to do.${NC}"
    exit 0
fi

# Determine which releases to delete
if [[ $DELETE_RELEASES == true ]]; then
    if [[ $KEEP_LATEST -gt 0 ]]; then
        if [[ $KEEP_LATEST -ge $TOTAL_RELEASES ]]; then
            # Keep all releases
            RELEASES_TO_DELETE=()
            RELEASES_TO_KEEP=("${ALL_RELEASES[@]}")
        else
            RELEASES_TO_DELETE=("${ALL_RELEASES[@]:$KEEP_LATEST}")
            RELEASES_TO_KEEP=("${ALL_RELEASES[@]:0:$KEEP_LATEST}")
        fi
    else
        RELEASES_TO_DELETE=("${ALL_RELEASES[@]}")
        RELEASES_TO_KEEP=()
    fi
else
    RELEASES_TO_DELETE=()
    RELEASES_TO_KEEP=()
fi

DELETE_COUNT=${#RELEASES_TO_DELETE[@]}
KEEP_COUNT=${#RELEASES_TO_KEEP[@]}

# Display summary
echo ""
echo -e "${BLUE}Summary:${NC}"

if [[ $DELETE_RELEASES == true ]]; then
    echo -e "  ${BLUE}Releases:${NC}"
    echo -e "    Total found:     ${TOTAL_RELEASES}"
    echo -e "    To delete:       ${RED}${DELETE_COUNT}${NC}"
    echo -e "    To keep:         ${GREEN}${KEEP_COUNT}${NC}"
fi

if [[ $DELETE_PACKAGES == true ]]; then
    echo -e "  ${BLUE}Package versions:${NC}"
    echo -e "    Total found:     ${TOTAL_PACKAGE_VERSIONS}"
    echo -e "    To delete:       ${RED}${PACKAGE_VERSIONS_DELETE_COUNT}${NC}"
    echo -e "    To keep:         ${GREEN}${PACKAGE_VERSIONS_KEEP_COUNT}${NC}"
fi
echo ""

# Show releases to keep (if any)
if [[ $KEEP_COUNT -gt 0 ]]; then
    echo -e "${GREEN}Releases to KEEP:${NC}"
    for tag in "${RELEASES_TO_KEEP[@]}"; do
        echo -e "  ${GREEN}✓${NC} $tag"
    done
    echo ""
fi

# Show releases to delete
if [[ $DELETE_COUNT -gt 0 ]]; then
    echo -e "${RED}Releases to DELETE:${NC}"
    for tag in "${RELEASES_TO_DELETE[@]}"; do
        echo -e "  ${RED}✗${NC} $tag"
    done
    echo ""
fi

# Show package versions to keep
if [[ $PACKAGE_VERSIONS_KEEP_COUNT -gt 0 ]]; then
    echo -e "${GREEN}Package versions to KEEP:${NC}"
    for entry in "${PACKAGE_VERSIONS_TO_KEEP[@]}"; do
        IFS='|' read -r pkg_name pkg_type version_id version_name <<< "$entry"
        echo -e "  ${GREEN}✓${NC} $pkg_name:$version_name ($pkg_type)"
    done
    echo ""
fi

# Show package versions to delete
if [[ $PACKAGE_VERSIONS_DELETE_COUNT -gt 0 ]]; then
    echo -e "${RED}Package versions to DELETE:${NC}"
    for entry in "${PACKAGE_VERSIONS_TO_DELETE[@]}"; do
        IFS='|' read -r pkg_name pkg_type version_id version_name <<< "$entry"
        echo -e "  ${RED}✗${NC} $pkg_name:$version_name ($pkg_type)"
    done
    echo ""
fi

# Dry run mode
if [[ $DRY_RUN == true ]]; then
    echo -e "${YELLOW}DRY RUN MODE - Nothing was deleted${NC}"
    echo ""
    echo "To actually delete, run without --dry-run:"
    cmd="  $(basename "$0")"
    [[ $KEEP_LATEST -gt 0 ]] && cmd="$cmd --keep $KEEP_LATEST"
    [[ $DELETE_RELEASES == false ]] && cmd="$cmd --packages-only"
    [[ $DELETE_PACKAGES == false ]] && cmd="$cmd --releases-only"
    echo "$cmd"
    exit 0
fi

# Confirmation prompt
if [[ $CONFIRMED == false ]]; then
    echo -e "${YELLOW}WARNING: This will permanently delete:${NC}"
    [[ $DELETE_COUNT -gt 0 ]] && echo -e "${YELLOW}  - ${DELETE_COUNT} GitHub release(s)${NC}"
    [[ $PACKAGE_VERSIONS_DELETE_COUNT -gt 0 ]] && echo -e "${YELLOW}  - ${PACKAGE_VERSIONS_DELETE_COUNT} container package version(s)${NC}"
    if [[ $DELETE_RELEASES == true ]]; then
        echo -e "${YELLOW}Git tags will NOT be deleted - only the GitHub releases${NC}"
    fi
    if [[ $DELETE_PACKAGES == true ]]; then
        echo -e "${YELLOW}Container images CANNOT be recovered once deleted${NC}"
    fi
    echo ""
    read -p "Are you sure you want to continue? (type 'yes' to confirm): " confirmation
    if [[ "$confirmation" != "yes" ]]; then
        echo -e "${BLUE}Aborted. Nothing was deleted.${NC}"
        exit 0
    fi
fi

# Delete releases
RELEASES_DELETED=0
RELEASES_FAILED=0

if [[ $DELETE_COUNT -gt 0 ]]; then
    echo ""
    echo -e "${BLUE}Deleting releases...${NC}"
    for tag in "${RELEASES_TO_DELETE[@]}"; do
        echo -n "Deleting release $tag... "
        if gh release delete "$tag" --yes --cleanup-tag=false 2>/dev/null; then
            echo -e "${GREEN}✓${NC}"
            ((RELEASES_DELETED++))
        else
            echo -e "${RED}✗ Failed${NC}"
            ((RELEASES_FAILED++))
        fi
    done
fi

# Delete package versions
PACKAGES_DELETED=0
PACKAGES_FAILED=0

if [[ $PACKAGE_VERSIONS_DELETE_COUNT -gt 0 ]]; then
    echo ""
    echo -e "${BLUE}Deleting package versions...${NC}"
    for entry in "${PACKAGE_VERSIONS_TO_DELETE[@]}"; do
        IFS='|' read -r pkg_name pkg_type version_id version_name <<< "$entry"

        # URL-encode package name (replace / with %2F)
        pkg_name_encoded="${pkg_name//\//%2F}"

        echo -n "Deleting $pkg_name:$version_name... "
        # Try org endpoint first, then user endpoint
        if gh api --method DELETE "/orgs/$REPO_OWNER/packages/$pkg_type/$pkg_name_encoded/versions/$version_id" 2>/dev/null || \
           gh api --method DELETE "/users/$REPO_OWNER/packages/$pkg_type/$pkg_name_encoded/versions/$version_id" 2>/dev/null; then
            echo -e "${GREEN}✓${NC}"
            ((PACKAGES_DELETED++))
        else
            echo -e "${RED}✗ Failed${NC}"
            ((PACKAGES_FAILED++))
        fi
    done
fi

# Final summary
echo ""
echo -e "${BLUE}Deletion complete:${NC}"

if [[ $DELETE_RELEASES == true ]] && [[ $DELETE_COUNT -gt 0 ]]; then
    echo -e "  ${BLUE}Releases:${NC}"
    echo -e "    ${GREEN}Successfully deleted: ${RELEASES_DELETED}${NC}"
    [[ $RELEASES_FAILED -gt 0 ]] && echo -e "    ${RED}Failed: ${RELEASES_FAILED}${NC}"
fi

if [[ $DELETE_PACKAGES == true ]] && [[ $PACKAGE_VERSIONS_DELETE_COUNT -gt 0 ]]; then
    echo -e "  ${BLUE}Package versions:${NC}"
    echo -e "    ${GREEN}Successfully deleted: ${PACKAGES_DELETED}${NC}"
    [[ $PACKAGES_FAILED -gt 0 ]] && echo -e "    ${RED}Failed: ${PACKAGES_FAILED}${NC}"
fi

if [[ $DELETE_RELEASES == true ]]; then
    echo ""
    echo -e "${YELLOW}Note: Git tags were preserved. To delete tags, use:${NC}"
    echo -e "  git tag -d <tag-name>          # Delete local tag"
    echo -e "  git push origin :refs/tags/<tag-name>  # Delete remote tag"
fi