#!/usr/bin/env python3
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

"""Check the agent instruction layer for drift.

The instruction files only ever grew, and nothing tested them: paths went stale, guards were
claimed that CI never ran, and the root file drifted past the size Codex reads. This checks the
things that can be checked mechanically, so a wrong instruction fails a build instead of quietly
misleading whoever reads it next.

Run with `pnpm agents:check`. Every problem is reported, not just the first.
"""

from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Budgets are published in docs/agent-effectiveness-review.md §4.3. Bytes are measured raw,
# because that is what Codex's project_doc_max_bytes counts — markdown table padding included.
# Measuring a whitespace-collapsed ideal would let the file grow past what Codex actually reads.
ROOT_AGENTS_LINES = 150
ROOT_AGENTS_BYTES = 12 * 1024
ROOT_CLAUDE_LINES = 40
AREA_AGENTS_LINES = 80
RULE_CARD_LINES = 60
# Codex reads the root file plus the guide for the directory it is working in.
CODEX_CHAIN_BYTES = 24 * 1024

SKILL_LINES = 150
SKILL_DESCRIPTION_CHARS = 300
SKILL_CODE_BLOCK_LINES = 15

STUB = "@AGENTS.md"

# Directory names that are never part of the instruction layer. Matched against paths *relative to
# the repository root*: an absolute match would prune everything when the repository is checked out
# under a directory that happens to share one of these names, such as a git worktree.
PRUNED = {".git", "node_modules", "build", "dist", ".gradle", ".idea", "worktrees"}

# `pnpm <word>` that is not a package script.
PNPM_BUILTINS = {"install", "exec", "run", "dlx", "add", "remove", "store", "why", "list"}

# The docs index vocabulary. ADRs use their own (Accepted / Draft / Superseded) and are checked
# only for indexing and number uniqueness.
DOC_STATUSES = {"Current", "Alpha", "Beta", "Proposed", "Record"}

VERIFY_HEADING = re.compile(
    r"^#{2,4}\s+(?:\d+\.\s*)?Verif(?:y|ication)\b", re.MULTILINE | re.IGNORECASE
)
MARKDOWN_LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
FRONTMATTER = re.compile(r"^---\n(.*?)\n---\n", re.DOTALL)
FENCED_BLOCK = re.compile(r"^```.*?^```", re.DOTALL | re.MULTILINE)
INLINE_CODE = re.compile(r"`([^`\n]+)`")
# A quoted string containing a `*` is a test filter or a glob, not a symbol reference.
QUOTED_GLOB = re.compile(r"\"[^\"]*\*[^\"]*\"")
TEST_SYMBOL = re.compile(r"\b([A-Z][A-Za-z0-9]*Test)\b")
GRADLE_TASK = re.compile(r"\b(check[A-Z][A-Za-z0-9]*)\b")
PNPM_SCRIPT = re.compile(r"\bpnpm (?:run )?([a-z][a-z0-9:-]*)")
# An index row links the page and may carry trailing prose before the Status column, as the ADR
# index does: `| [0001](0001-thing.md) Thing | Accepted | …`.
INDEX_ROW = re.compile(r"^\|\s*\[([^\]]+)\]\(([^)\s]+)\)[^|]*\|\s*([^|]+?)\s*\|")
STATUS_BANNER = re.compile(r"^>\s*\*\*Status:\*\*\s*(.*)$", re.MULTILINE)
ADR_FILE = re.compile(r"^(\d{4})-[a-z0-9-]+\.md$")

INSTRUCTION_NAMES = {"AGENTS.md", "CLAUDE.md"}


@dataclass(frozen=True)
class Problem:
    path: str
    message: str

    def render(self) -> str:
        return f"{self.path}: {self.message}"


def walk(base: Path):
    """Yield files under `base`, skipping build output and vendored trees."""
    for path in base.rglob("*"):
        if any(part in PRUNED for part in path.relative_to(base).parts):
            continue
        if path.is_file():
            yield path


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def code_text(text: str) -> str:
    """Only the code spans and fenced blocks, minus anything holding a `*`.

    Symbols, tasks and scripts are always written as code. Reading prose too would match ordinary
    English — "a pnpm package" is not an invocation of a script called `package`. A fragment
    containing a `*` is a glob or a `--tests` filter, so `*HandlerHtmxTest` names a naming
    convention rather than a class that has to exist.
    """
    spans = [span for span in INLINE_CODE.findall(text) if "*" not in span]
    lines = [
        line
        for block in FENCED_BLOCK.findall(text)
        for line in block.splitlines()
        if "*" not in line
    ]
    return "\n".join(spans + lines)


def instruction_files() -> tuple[list[Path], list[Path], list[Path]]:
    """Return (area guides, area stubs, rule cards). Root files are handled separately."""
    guides, stubs = [], []
    for path in walk(ROOT):
        if path.name == "AGENTS.md" and path != ROOT / "AGENTS.md":
            guides.append(path)
        elif path.name == "CLAUDE.md" and path != ROOT / "CLAUDE.md":
            stubs.append(path)
    cards = sorted((ROOT / ".agents" / "rules").glob("*.md"))
    return sorted(guides), sorted(stubs), cards


def check_budgets(guides: list[Path], cards: list[Path]) -> list[Problem]:
    problems: list[Problem] = []

    text = read(ROOT / "AGENTS.md")
    lines, size = len(text.splitlines()), len(text.encode())
    if lines > ROOT_AGENTS_LINES:
        problems.append(Problem("AGENTS.md", f"{lines} lines; the budget is {ROOT_AGENTS_LINES}"))
    if size > ROOT_AGENTS_BYTES:
        problems.append(Problem("AGENTS.md", f"{size} bytes; the budget is {ROOT_AGENTS_BYTES}"))

    claude = read(ROOT / "CLAUDE.md")
    if len(claude.splitlines()) > ROOT_CLAUDE_LINES:
        problems.append(
            Problem("CLAUDE.md", f"{len(claude.splitlines())} lines; the budget is {ROOT_CLAUDE_LINES}")
        )
    if STUB not in claude:
        problems.append(Problem("CLAUDE.md", f"does not import the canonical file with `{STUB}`"))

    largest_guide = 0
    for guide in guides:
        guide_text = read(guide)
        largest_guide = max(largest_guide, len(guide_text.encode()))
        guide_lines = len(guide_text.splitlines())
        if guide_lines > AREA_AGENTS_LINES:
            problems.append(
                Problem(rel(guide), f"{guide_lines} lines; an area guide's budget is {AREA_AGENTS_LINES}")
            )

    chain = size + largest_guide
    if chain > CODEX_CHAIN_BYTES:
        problems.append(
            Problem(
                "AGENTS.md",
                f"the Codex chain (root plus the largest area guide) is {chain} bytes; "
                f"the budget is {CODEX_CHAIN_BYTES}",
            )
        )

    for card in cards:
        card_lines = len(read(card).splitlines())
        if card_lines > RULE_CARD_LINES:
            problems.append(
                Problem(rel(card), f"{card_lines} lines; a rule card's budget is {RULE_CARD_LINES}")
            )

    return problems


def check_structure(guides: list[Path], stubs: list[Path], cards: list[Path]) -> list[Problem]:
    """Every guide has a stub beside it, and every canonical file has its Claude symlink."""
    problems: list[Problem] = []

    for guide in guides:
        stub = guide.parent / "CLAUDE.md"
        if not stub.exists():
            problems.append(Problem(rel(guide), "has no CLAUDE.md stub beside it, so Claude never loads it"))
        elif read(stub).strip() != STUB:
            problems.append(Problem(rel(stub), f"should contain exactly `{STUB}`"))

    for stub in stubs:
        if not (stub.parent / "AGENTS.md").exists():
            problems.append(Problem(rel(stub), "is a stub with no AGENTS.md beside it"))

    for card in cards:
        problems.extend(check_symlink(ROOT / ".claude" / "rules" / card.name, card))
        frontmatter = FRONTMATTER.match(read(card))
        if not frontmatter or "paths:" not in frontmatter.group(1):
            problems.append(Problem(rel(card), "has no `paths:` frontmatter, so it never loads on a match"))

    skills_dir = ROOT / ".agents" / "skills"
    for skill in sorted(p for p in skills_dir.iterdir() if p.is_dir()):
        problems.extend(check_symlink(ROOT / ".claude" / "skills" / skill.name, skill))

    return problems


def check_symlink(link: Path, target: Path) -> list[Problem]:
    if not link.is_symlink():
        if link.exists():
            return [Problem(rel(link), f"is a copy; it should be a symlink to {rel(target)}")]
        return [Problem(rel(link), f"is missing; it should be a symlink to {rel(target)}")]
    if link.resolve() != target.resolve():
        return [Problem(rel(link), f"points at {link.resolve()}, not {rel(target)}")]
    return []


def check_references(files: list[Path]) -> list[Problem]:
    """Linked paths, named tests, Gradle tasks and pnpm scripts must all exist."""
    problems: list[Problem] = []

    symbols = {p.stem for p in walk(ROOT) if p.suffix in (".kt", ".ts")}
    gradle = "\n".join(read(p) for p in walk(ROOT) if p.name.endswith(".gradle.kts"))
    scripts = set(json.loads(read(ROOT / "package.json")).get("scripts", {}))

    for path in files:
        text = read(path)
        code = code_text(text)

        for target in MARKDOWN_LINK.findall(text):
            if target.startswith(("http://", "https://", "mailto:", "#")):
                continue
            if not (path.parent / target.split("#", 1)[0]).resolve().exists():
                problems.append(Problem(rel(path), f"links to `{target}`, which does not exist"))

        for symbol in sorted(set(TEST_SYMBOL.findall(code))):
            if symbol not in symbols:
                problems.append(
                    Problem(rel(path), f"names `{symbol}`, which is not a test in the repository")
                )

        for task in sorted(set(GRADLE_TASK.findall(code))):
            if task not in gradle:
                problems.append(
                    Problem(rel(path), f"names Gradle task `{task}`, which is registered nowhere")
                )

        for script in sorted(set(PNPM_SCRIPT.findall(code))):
            if script not in scripts and script not in PNPM_BUILTINS:
                problems.append(
                    Problem(rel(path), f"names `pnpm {script}`, which is not a package script")
                )

    return problems


def check_guard_coverage(files: list[Path]) -> list[Problem]:
    """Every architecture guard must be named in the instruction layer.

    A guard nobody is told about fails the build on a rule the reader never saw.
    """
    named = "\n".join(read(path) for path in files)
    problems: list[Problem] = []
    for path in sorted(walk(ROOT)):
        parts = path.relative_to(ROOT).parts
        if "architecture" not in parts or "test" not in parts:
            continue
        if path.suffix != ".kt" or not path.stem.endswith("Test"):
            continue
        if path.stem not in named:
            problems.append(
                Problem(rel(path), f"`{path.stem}` fails the build but is named in no AGENTS.md or rule card")
            )
    return problems


def check_skills() -> list[Problem]:
    problems: list[Problem] = []
    for skill in sorted(p for p in (ROOT / ".agents" / "skills").iterdir() if p.is_dir()):
        manifest = skill / "SKILL.md"
        if not manifest.exists():
            problems.append(Problem(rel(skill), "has no SKILL.md"))
            continue

        text = read(manifest)
        frontmatter = FRONTMATTER.match(text)
        if not frontmatter:
            problems.append(Problem(rel(manifest), "has no frontmatter"))
            continue

        fields = dict(re.findall(r"^([a-zA-Z-]+):\s*(.*)$", frontmatter.group(1), re.MULTILINE))
        if fields.get("name") != skill.name:
            problems.append(
                Problem(rel(manifest), f"declares name `{fields.get('name')}`, not `{skill.name}`")
            )

        description = fields.get("description", "")
        if not description:
            problems.append(Problem(rel(manifest), "has no description"))
        elif len(description) > SKILL_DESCRIPTION_CHARS:
            problems.append(
                Problem(
                    rel(manifest),
                    f"description is {len(description)} characters; the budget is {SKILL_DESCRIPTION_CHARS}",
                )
            )

        lines = len(text.splitlines())
        if lines > SKILL_LINES:
            problems.append(Problem(rel(manifest), f"{lines} lines; the budget is {SKILL_LINES}"))

        if not VERIFY_HEADING.search(text):
            problems.append(
                Problem(rel(manifest), "has no Verify section, so it never says how to know it worked")
            )

        for block in FENCED_BLOCK.findall(text):
            block_lines = block.count("\n") - 1
            if block_lines > SKILL_CODE_BLOCK_LINES:
                problems.append(
                    Problem(
                        rel(manifest),
                        f"has a {block_lines}-line code block; the budget is {SKILL_CODE_BLOCK_LINES}",
                    )
                )

    return problems


def check_routing_table(cards: list[Path]) -> list[Problem]:
    """Every rule card must be reachable from the routing table."""
    text = read(ROOT / "AGENTS.md")
    return [
        Problem("AGENTS.md", f"never routes to `{rel(card)}`, so nothing sends a reader to it")
        for card in cards
        if f"({card.as_posix().split('/')[-3]}/rules/{card.name})" not in text
        and f".agents/rules/{card.name})" not in text
    ]


def check_docs() -> list[Problem]:
    problems: list[Problem] = []
    docs = ROOT / "docs"
    adr_dir = docs / "adr"

    indexed = index_entries(docs / "README.md")
    for page in sorted(docs.glob("*.md")):
        if page.name == "README.md" or page.name in INSTRUCTION_NAMES:
            continue
        if page.name not in indexed:
            problems.append(
                Problem(rel(page), "is in no index, so nobody routing by docs/README.md finds it")
            )

    adr_indexed = index_entries(adr_dir / "README.md")
    numbers: dict[str, str] = {}
    for page in sorted(adr_dir.glob("*.md")):
        if page.name == "README.md" or page.name in INSTRUCTION_NAMES:
            continue
        if page.name not in adr_indexed:
            problems.append(Problem(rel(page), "is in no ADR index"))
        match = ADR_FILE.match(page.name)
        if not match:
            continue
        number = match.group(1)
        if number in numbers:
            problems.append(
                Problem(rel(page), f"reuses ADR number {number}, already taken by {numbers[number]}")
            )
        else:
            numbers[number] = page.name

    # The banner rule applies to documentation pages only. ADRs carry their own status vocabulary
    # in the ADR index, and none of them uses a banner.
    for name, status in indexed.items():
        page = docs / name
        if not page.exists():
            problems.append(Problem(f"docs/{name}", "is indexed but does not exist"))
            continue
        banner = STATUS_BANNER.search(read(page))
        if status not in DOC_STATUSES:
            problems.append(
                Problem(rel(page), f"is indexed as `{status}`, which is not one of {sorted(DOC_STATUSES)}")
            )
            continue
        if status != "Current" and not banner:
            problems.append(Problem(rel(page), f"is indexed as {status} but carries no `> **Status:**` banner"))
        if banner:
            declared = banner.group(1).strip().lstrip("*").split()[0].strip("*—-:,.") if banner.group(1).strip() else ""
            if declared in DOC_STATUSES and declared != status:
                problems.append(Problem(rel(page), f"banner says {declared} but the index says {status}"))

    return problems


def index_entries(index: Path) -> dict[str, str]:
    """Map the filename of each indexed page to its Status column."""
    if not index.exists():
        return {}
    entries: dict[str, str] = {}
    for line in read(index).splitlines():
        match = INDEX_ROW.match(line)
        if not match:
            continue
        target, status = match.group(2), match.group(3).strip()
        if target.startswith(("http", "#", "..")):
            continue
        entries[Path(target.split("#", 1)[0]).name] = status
    return entries


def rel(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(ROOT))
    except ValueError:
        return str(path)


def main() -> int:
    guides, stubs, cards = instruction_files()
    instruction_layer = [ROOT / "AGENTS.md", ROOT / "CLAUDE.md", *guides, *cards]

    problems = [
        *check_budgets(guides, cards),
        *check_structure(guides, stubs, cards),
        *check_references(instruction_layer),
        *check_guard_coverage(instruction_layer),
        *check_routing_table(cards),
        *check_skills(),
        *check_docs(),
    ]

    for problem in sorted(problems, key=lambda p: (p.path, p.message)):
        print(problem.render())

    if problems:
        print(f"\n{len(problems)} problem(s) in the agent instructions.", file=sys.stderr)
        return 1

    print(f"agents: checked {len(instruction_layer) + len(stubs)} instruction files, their skills and the docs index.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
