#!/usr/bin/env python3
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

"""Apply or check SPDX headers for first-party commentable files."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

COPYRIGHT_TAG = "SPDX-" + "FileCopyrightText:"
LICENSE_TAG = "SPDX-" + "License-Identifier:"
COPYRIGHT = f"{COPYRIGHT_TAG} Epistola Nederland B.V."
LICENSE = f"{LICENSE_TAG} AGPL-3.0-only"
MARKERS = (COPYRIGHT_TAG, LICENSE_TAG)

LINE_COMMENT_EXTENSIONS = {
    ".kt": "//",
    ".kts": "//",
    ".java": "//",
    ".ts": "//",
    ".js": "//",
    ".mjs": "//",
    ".yml": "#",
    ".yaml": "#",
    ".toml": "#",
    ".properties": "#",
    ".factories": "#",
    ".sh": "#",
    ".sql": "--",
}

BLOCK_COMMENT_EXTENSIONS = {
    ".css": ("/*", " * ", " */"),
    ".html": ("<!--", "  ", "-->"),
    ".md": ("<!--", "  ", "-->"),
    ".svg": ("<!--", "  ", "-->"),
    ".xml": ("<!--", "  ", "-->"),
}

SPECIAL_LINE_COMMENT_FILES = {
    ".github/CODEOWNERS": "#",
    ".husky/commit-msg": "#",
    ".husky/post-merge": "#",
    ".husky/pre-commit": "#",
    "apps/epistola/docker/run-image/Dockerfile": "#",
    "gradlew": "#",
}

SPECIAL_LINE_COMMENT_NAMES = {
    ".aiignore": "#",
    ".editorconfig": "#",
    ".gitattributes": "#",
    ".gitignore": "#",
    ".helmignore": "#",
}

SPECIAL_BAT_FILES = {"gradlew.bat"}

SKIP_PREFIXES = (
    "LICENSES/",
    "modules/epistola-core/src/main/resources/epistola/fonts/",
    "modules/generation/src/main/resources/fonts/",
    "org/",
)

SKIP_FILES = {
    "LICENSE",
    "pnpm-lock.yaml",
    "modules/generation/src/main/resources/color/sRGB.icc",
}

SKIP_EXTENSIONS = {
    ".json",
    ".png",
    ".ttf",
    ".txt",
    ".tpl",
    ".TestExecutionListener",
    ".LauncherSessionListener",
}


def git_files() -> list[Path]:
    output = subprocess.check_output(["git", "ls-files", "-z"])
    return [Path(item.decode()) for item in output.split(b"\0") if item]


def is_skipped(path: Path) -> bool:
    name = path.as_posix()
    return (
        name in SKIP_FILES
        or any(name.startswith(prefix) for prefix in SKIP_PREFIXES)
        or path.suffix in SKIP_EXTENSIONS
    )


def line_header(prefix: str) -> str:
    return f"{prefix} {COPYRIGHT}\n{prefix}\n{prefix} {LICENSE}\n\n"


def block_header(start: str, prefix: str, end: str) -> str:
    return f"{start}\n{prefix}{COPYRIGHT}\n\n{prefix}{LICENSE}\n{end}\n\n"


def header_for(path: Path) -> str | None:
    name = path.as_posix()
    if name in SPECIAL_LINE_COMMENT_FILES:
        return line_header(SPECIAL_LINE_COMMENT_FILES[name])
    if path.name in SPECIAL_LINE_COMMENT_NAMES:
        return line_header(SPECIAL_LINE_COMMENT_NAMES[path.name])
    if name in SPECIAL_BAT_FILES:
        return f"rem {COPYRIGHT}\nrem\nrem {LICENSE}\n\n"
    if path.suffix in LINE_COMMENT_EXTENSIONS:
        return line_header(LINE_COMMENT_EXTENSIONS[path.suffix])
    if path.suffix in BLOCK_COMMENT_EXTENSIONS:
        return block_header(*BLOCK_COMMENT_EXTENSIONS[path.suffix])
    return None


def insertion_offset(text: str) -> int:
    if text.startswith("#!"):
        first_newline = text.find("\n")
        if first_newline != -1:
            return first_newline + 1

    for prefix in ("<?xml", "<!DOCTYPE html", "<!doctype html"):
        if text.startswith(prefix):
            first_newline = text.find("\n")
            if first_newline != -1:
                return first_newline + 1

    return 0


def has_spdx_header(text: str) -> bool:
    head = "\n".join(text.splitlines()[:12])
    return all(marker in head for marker in MARKERS)


def process_file(path: Path, check: bool) -> bool:
    if is_skipped(path):
        return True

    header = header_for(path)
    if header is None:
        print(f"unsupported comment style: {path}", file=sys.stderr)
        return False

    text = path.read_text()
    if has_spdx_header(text):
        return True

    if check:
        print(f"missing SPDX header: {path}", file=sys.stderr)
        return False

    offset = insertion_offset(text)
    path.write_text(text[:offset] + header + text[offset:])
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="fail if a header is missing")
    mode.add_argument("--fix", action="store_true", help="insert missing headers")
    args = parser.parse_args()

    ok = all(process_file(path, check=args.check) for path in git_files())
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
