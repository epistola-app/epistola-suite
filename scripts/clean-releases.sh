#!/usr/bin/env bash

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
DRY_RUN=false
KEEP_LATEST=0
CONFIRMED=false

# Usage information
usage() {
    cat << EOF
Usage: $(basename "$0") [OPTIONS]

Delete GitHub releases while preserving git tags.

OPTIONS:
    -d, --dry-run           Show what would be deleted without actually deleting
    -k, --keep N            Keep the N most recent releases (default: 0 = delete all)
    -y, --yes               Skip confirmation prompt
    -h, --help              Show this help message

EXAMPLES:
    # Dry run - see what would be deleted
    $(basename "$0") --dry-run

    # Delete all releases with confirmation
    $(basename "$0")

    # Keep the 3 most recent releases, delete the rest
    $(basename "$0") --keep 3

    # Delete all releases without confirmation (use with caution!)
    $(basename "$0") --yes

    # Dry run keeping 5 releases
    $(basename "$0") --dry-run --keep 5

NOTE: This script only deletes GitHub releases, not the underlying git tags.
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

# Get all releases, sorted by creation date (newest first)
echo -e "${BLUE}Fetching releases from GitHub...${NC}"
ALL_RELEASES=()
while IFS= read -r tag; do
    ALL_RELEASES+=("$tag")
done < <(gh release list --limit 1000 --json tagName,isLatest,createdAt --jq '.[] | .tagName')

TOTAL_RELEASES=${#ALL_RELEASES[@]}

if [[ $TOTAL_RELEASES -eq 0 ]]; then
    echo -e "${GREEN}No releases found. Nothing to do.${NC}"
    exit 0
fi

# Determine which releases to delete
if [[ $KEEP_LATEST -gt 0 ]]; then
    if [[ $KEEP_LATEST -ge $TOTAL_RELEASES ]]; then
        echo -e "${YELLOW}Warning: --keep $KEEP_LATEST is >= total releases ($TOTAL_RELEASES)${NC}"
        echo -e "${GREEN}No releases will be deleted.${NC}"
        exit 0
    fi
    RELEASES_TO_DELETE=("${ALL_RELEASES[@]:$KEEP_LATEST}")
    RELEASES_TO_KEEP=("${ALL_RELEASES[@]:0:$KEEP_LATEST}")
else
    RELEASES_TO_DELETE=("${ALL_RELEASES[@]}")
    RELEASES_TO_KEEP=()
fi

DELETE_COUNT=${#RELEASES_TO_DELETE[@]}
KEEP_COUNT=${#RELEASES_TO_KEEP[@]}

# Display summary
echo ""
echo -e "${BLUE}Summary:${NC}"
echo -e "  Total releases found: ${TOTAL_RELEASES}"
echo -e "  Releases to delete:   ${RED}${DELETE_COUNT}${NC}"
echo -e "  Releases to keep:     ${GREEN}${KEEP_COUNT}${NC}"
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

# Dry run mode
if [[ $DRY_RUN == true ]]; then
    echo -e "${YELLOW}DRY RUN MODE - No releases were deleted${NC}"
    echo ""
    echo "To actually delete these releases, run without --dry-run:"
    if [[ $KEEP_LATEST -gt 0 ]]; then
        echo "  $(basename "$0") --keep $KEEP_LATEST"
    else
        echo "  $(basename "$0")"
    fi
    exit 0
fi

# Confirmation prompt
if [[ $CONFIRMED == false ]]; then
    echo -e "${YELLOW}WARNING: This will permanently delete ${DELETE_COUNT} GitHub release(s)${NC}"
    echo -e "${YELLOW}Git tags will NOT be deleted - only the GitHub releases${NC}"
    echo ""
    read -p "Are you sure you want to continue? (type 'yes' to confirm): " confirmation
    if [[ "$confirmation" != "yes" ]]; then
        echo -e "${BLUE}Aborted. No releases were deleted.${NC}"
        exit 0
    fi
fi

# Delete releases
echo ""
echo -e "${BLUE}Deleting releases...${NC}"
DELETED=0
FAILED=0

for tag in "${RELEASES_TO_DELETE[@]}"; do
    echo -n "Deleting $tag... "
    if gh release delete "$tag" --yes --cleanup-tag=false 2>/dev/null; then
        echo -e "${GREEN}✓${NC}"
        ((DELETED++))
    else
        echo -e "${RED}✗ Failed${NC}"
        ((FAILED++))
    fi
done

# Final summary
echo ""
echo -e "${BLUE}Deletion complete:${NC}"
echo -e "  ${GREEN}Successfully deleted: ${DELETED}${NC}"
if [[ $FAILED -gt 0 ]]; then
    echo -e "  ${RED}Failed: ${FAILED}${NC}"
fi
echo ""
echo -e "${YELLOW}Note: Git tags were preserved. To delete tags, use:${NC}"
echo -e "  git tag -d <tag-name>          # Delete local tag"
echo -e "  git push origin :refs/tags/<tag-name>  # Delete remote tag"