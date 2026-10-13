# Discourse MCP

Lets a coding agent read and post on the [community forum](https://community.epistola.app), for
example to announce a release. It uses the official `@discourse/mcp`, pinned in `package.json`, and
acts as you: it can do exactly what your forum account can.

## Signing in

Nothing to set up. The first time the agent starts the server, your browser opens on the forum's
approval page; approve it and the server starts. On macOS a dialog shows the code, should the
page ask for it. The account is whichever one your browser is signed in with, and the server logs
it (`Signed in to … as <username>`).

If approving takes longer than the agent's MCP start-up timeout, reconnect the server once (in
Claude Code: `/mcp`); the key is stored by then.

The User API Key (scopes `read,write`) is kept in your OS credential manager and reaches the server
through a pipe (`--profile /dev/fd/3`), so it is never written to disk or visible in the process
list.

## Switching accounts

Revoke the key under your forum profile → Preferences → Apps; the next start signs in again. Or sign
in with another account directly:

```bash
pnpm run setup:discourse-mcp
```
