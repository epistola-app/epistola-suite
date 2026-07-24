#!/bin/sh
# Gradle wrapper that uses mise-managed toolchain
# All tool versions are defined in .mise.toml

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Run gradle within mise environment
exec mise exec -- gradle "$@"
