#!/usr/bin/env python3
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only
"""Deny a whole-file read of CHANGELOG.md.

CHANGELOG.md is ~700 KB and every new entry goes at the top, so an assistant that reads it
whole burns a large part of its context on released history it does not need — and a read
that large can fail outright. Reading the first dozen lines is enough to insert an entry.

Wired as a PreToolUse hook on Read in .claude/settings.json. Reads the hook payload as JSON on
stdin and prints a deny decision when the read has no small `limit`; prints nothing otherwise,
which leaves the read allowed. Any unexpected input allows the read: a guard that cannot parse
its input must not block work.

Agent-neutral by design (JSON in, JSON out), so Codex's .codex/hooks.json can call the same file.
"""

import json
import sys

MAX_LIMIT = 200
REASON = (
    "CHANGELOG.md is ~700 KB, and a whole-file read spends context on released history. "
    "Read the first lines instead (limit: 12) and insert the new entry at the top of "
    "[Unreleased]. Entry format and rationale: AGENTS.md."
)


def main() -> int:
    try:
        payload = json.load(sys.stdin)
        tool_input = payload.get("tool_input") or {}
        file_path = str(tool_input.get("file_path") or "")
        limit = tool_input.get("limit")
    except Exception:
        return 0

    if not file_path.endswith("CHANGELOG.md"):
        return 0
    if isinstance(limit, int) and 0 < limit <= MAX_LIMIT:
        return 0

    json.dump(
        {
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "deny",
                "permissionDecisionReason": REASON,
            }
        },
        sys.stdout,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
