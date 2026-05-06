# MCP server (AI assistant integration)

Epistola exposes a [Model Context Protocol](https://modelcontextprotocol.io) server so AI assistants (Claude Desktop, Cursor, MCP Inspector, etc.) can talk to a tenant's templates, themes, stencils, and data contracts directly. The intended workflow is **AI-assisted template design**: the assistant discovers existing templates, inspects their structure, and renders previews to verify what they produce.

## Status

- **MVP is read-only.** Tools cover discovery, inspection, and document preview. Authoring (drafts, themes, stencils, contract edits, publishing) is a planned follow-up.
- **Transport: Streamable HTTP**, mounted at `/api/mcp` on the same Spring Boot service that serves the UI and REST API.
- **Auth: X-API-Key.** Reuses the existing per-tenant API-key mechanism — no new credential type.

## Connecting an MCP client

Point your client at:

```
http://<your-epistola-host>:<port>/api/mcp
```

with the header

```
X-API-Key: epk_<your-key>
```

The API key must:

1. Belong to the tenant whose templates you want to manage. All tools resolve the tenant from the key — they take no `tenantId` argument.
2. Have the **`TEMPLATE_VIEW`** permission (read tools) and the **`DOCUMENT_GENERATE`** permission (for `preview_document`).

Provision an MCP-purpose key from the Epistola UI under **Operations → API Keys**.

### Example: Claude Desktop config

Claude Desktop's MCP configuration uses an HTTP transport entry. In `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS):

```jsonc
{
  "mcpServers": {
    "epistola": {
      "url": "https://your-epistola.example.com/api/mcp",
      "headers": {
        "X-API-Key": "epk_...",
      },
    },
  },
}
```

### Example: MCP Inspector

```bash
npx @modelcontextprotocol/inspector
```

Then enter the URL and `X-API-Key` header in the Inspector UI.

## Available tools

| Tool                   | Purpose                                                                                                                                                                                                                                                                                                                                                                                 |
| ---------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `list_catalogs`        | Discover catalogs in the current tenant.                                                                                                                                                                                                                                                                                                                                                |
| `list_templates`       | List templates, optionally filtered by catalog or name.                                                                                                                                                                                                                                                                                                                                 |
| `get_template`         | Fetch a single template's metadata (no content).                                                                                                                                                                                                                                                                                                                                        |
| `get_template_content` | Fetch the full editor context for a variant: template node/slot graph, variant attributes, data contract dataModel, named data examples. This is what the AI needs to "understand" a template.                                                                                                                                                                                          |
| `list_variants`        | List variants for a template.                                                                                                                                                                                                                                                                                                                                                           |
| `get_variant`          | Fetch a single variant's metadata.                                                                                                                                                                                                                                                                                                                                                      |
| `list_versions`        | List versions for a variant (drafts + published).                                                                                                                                                                                                                                                                                                                                       |
| `list_themes`          | Discover themes.                                                                                                                                                                                                                                                                                                                                                                        |
| `get_theme`            | Full theme details: document styles, page settings, block style presets.                                                                                                                                                                                                                                                                                                                |
| `list_stencils`        | Discover stencils (reusable content blocks templates can embed).                                                                                                                                                                                                                                                                                                                        |
| `get_stencil`          | Fetch a single stencil's metadata.                                                                                                                                                                                                                                                                                                                                                      |
| `get_data_contract`    | Fetch a template's data contract (JSON Schema + examples). Choose `status='draft'` or `status='published'`, or omit for the latest of either.                                                                                                                                                                                                                                           |
| `list_component_types` | Describe every editor component type — slots, `allowedChildren`, applicable style keys, inspector fields, default props/styles, and **hand-curated `examples`** showing realistic usage patterns (template-document fragments the AI can copy and adapt). The TypeScript registry is the single source of truth; a JSON snapshot is generated at editor build time and read at runtime. |
| `get_component_type`   | Fetch a single component type's descriptor by `type` — same shape as one entry of `list_component_types`, including its `examples`.                                                                                                                                                                                                                                                     |
| `preview_document`     | Render a preview PDF and return it base64-encoded. Variant, version, environment, and sample data are all optional.                                                                                                                                                                                                                                                                     |

## Limitations and notes

- **PDF preview is binary.** `preview_document` returns the rendered PDF as base64 in the `data` field along with `mediaType: "application/pdf"`. AI assistants generally cannot read PDF text directly; the field is most useful for users who want to view the rendered output. A future iteration may add a textual or HTML-rendered preview path.
- **Data contract auto-creation.** Creating a template auto-creates an empty draft contract (v1). `get_data_contract` returns it even before the contract has been authored.
- **No write tools yet.** The MCP server cannot create or modify templates, drafts, themes, stencils, or contracts in this MVP. Switch to the UI for authoring; the AI can still inspect what it has access to.
- **Rate limiting.** The MCP endpoint shares the existing `/api/**` security chain; no MCP-specific rate limiting is in place.

## Troubleshooting

- **`401 Unauthorized`**: The `X-API-Key` header is missing, the key is malformed (must start with `epk_`), or the key has been revoked/disabled/expired.
- **Tool errors mentioning permission**: The API key is missing `TEMPLATE_VIEW` (for read tools) or `DOCUMENT_GENERATE` (for preview).
- **`MCP request has no tenant scope`**: The principal has no `currentTenantId`. With API-key auth, this should not happen — the key is always tenant-scoped. If you see it, the key may have been provisioned without a tenant binding.

## Related

- [`modules/epistola-mcp/`](../modules/epistola-mcp) — module source
- [`apps/epistola/src/main/resources/application.yaml`](../apps/epistola/src/main/resources/application.yaml) — `spring.ai.mcp.server.*` settings
- [`docs/api-keys.md`](api-keys.md) — API key provisioning (if present)
