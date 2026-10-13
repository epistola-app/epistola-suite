# Discourse MCP

Lets a coding agent read and post on the [community forum](https://community.epistola.app), for
example to announce a release. It uses the official `@discourse/mcp`, pinned in `package.json`, and
acts as you: it can do exactly what your forum account can.

## Setup

```bash
pnpm run setup:discourse-mcp
```

This prints an activation URL and a code. Open the URL, sign in, enter the code and approve the
request. The generated User API Key (scopes `read,write`) is stored in your OS credential manager.
Restart the agent afterwards. Revoke the key under your forum profile → Preferences → Apps.

`wrapper.mjs` reads the key from the credential manager and hands it to the server through a pipe
(`--profile /dev/fd/3`), so it is never written to disk or visible in the process list.
