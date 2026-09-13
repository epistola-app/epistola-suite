# Claude Code Instructions for Epistola Suite

The conventions live in `AGENTS.md`, which every agent reads. It is imported here, so you already
have it:

@AGENTS.md

## Claude-specific notes

Everything below is about how this repository is wired for Claude Code. The rules themselves are in
the file above and in the guides it routes to.

- **Area guides load themselves.** Each `<area>/AGENTS.md` has a one-line `CLAUDE.md` beside it, so
  reading a file in that directory pulls in its guide. You do not need to open them up front.
- **Rule cards are path-scoped.** `.claude/rules/*.md` are symlinks to `.agents/rules/*.md` and
  carry `paths:` frontmatter, so a card loads when you touch a file it matches — templates, tests,
  migrations, bundled catalogs, configuration properties.
- **Skills live in `.agents/skills/`**, symlinked into `.claude/skills/` so both Claude Code and
  Codex find the same files. Write new ones there, not in `.claude/`.
- **A hook guards the changelog.** A `PreToolUse` hook refuses a whole-file read of `CHANGELOG.md`
  and tells you to read the first lines instead; the script is `scripts/agent-hooks/`.
- **Use the `gh` CLI** for issues, pull requests and workflow runs. The GitHub MCP server in
  `.mcp.json` is optional and nothing depends on it.
- **Worktrees**: `.claude/worktrees/` is excluded from git and from `.aiignore`.
