# GitHub MCP

Lets a coding agent work with this repository's GitHub issues, pull requests and projects, through
`@modelcontextprotocol/server-github`.

## Setup

```bash
pnpm run setup:github-mcp
```

This opens GitHub to create a fine-grained personal access token (resource owner `epistola-app`,
repository `epistola-suite`, read and write on issues, pull requests and projects), validates it and
stores it in your OS credential manager. Restart the agent afterwards.

`wrapper.mjs` reads the token from the credential manager and starts the server; `.mcp.json` at the
repository root points to it.
