---
type: feat
scopes: [catalog, exchange, api, mcp]
audience: user
maturity: alpha
title: Release registries over REST, with Epistola Exchange as one kind beside container registries (alpha).
---

The contract's release-registry operations are served: `listReleaseRegistries`,
`listRegistryReleases`, `installRegistryRelease` and `publishReleaseToRegistry` (alpha,
`x-experimental`). A release registry is a configured container registry (kind `container`) or the
Epistola Exchange the tenant is connected to (kind `exchange`, named `exchange`). Publishing to a
container registry answers `published` or `already-published`; to the Exchange `queued`, also when
it was queued already. Refusals use the contract's problem types: `release-registry-not-found`,
`release-number-taken`, `namespace-not-granted`, `conflict` for a draft and
`catalog-source-unavailable`. The web UI's registry dialogs and the MCP tools offer the Exchange
beside container registries; MCP gains `list_release_registries`, and its registry results now
carry `kind` and a publication `status` (`alreadyPublished`, `repository` and the install `digest`
are gone). A container registry may no longer be called `exchange`.
