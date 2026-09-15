#!/usr/bin/env python3
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only
"""Deny a whole-file read of CHANGELOG.md.

CHANGELOG.md is ~600 KB of released history, so an assistant that reads it whole burns a large
part of its context on releases it does not need — and a read that large can fail outright.
Nothing is inserted here any more: a notable change adds a file under changelog/unreleased/.

Wired as a PreToolUse hook on Read in .claude/settings.json. Reads the hook payload as JSON on
stdin and prints a deny decision when the read has no small `limit`; prints nothing otherwise,
which leaves the read allowed. Any unexpected input allows the read: a guard that cannot parse
its input must not block work.

Agent-neutral by design (JSON in, JSON out), so Codex's .codex/hooks.json can call the same file.

Call this with a trailing `|| true`. python3 exits 2 when it cannot open its script file, and 2 is
the one exit code that blocks a PreToolUse call — so a checkout without this script would refuse
every Read rather than simply not guarding. `|| true` cannot mask a real deny: that is emitted as
JSON on stdout with exit 0.
"""

import json
import sys

MAX_LIMIT = 200
REASON = (
    "CHANGELOG.md is ~600 KB of released history, and a whole-file read spends context on it. "
    "Read the first lines instead (limit: 12) if you need the most recent release. A notable "
    "change adds a file under changelog/unreleased/ — never an edit to this file. Format: "
    "changelog/README.md."
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
