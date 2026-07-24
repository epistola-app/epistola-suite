<!--
  SPDX-FileCopyrightText: Epistola Nederland B.V.

  SPDX-License-Identifier: AGPL-3.0-only
-->

# MCP server (AI assistant integration)

Epistola exposes a [Model Context Protocol](https://modelcontextprotocol.io) server so AI assistants (Claude Desktop, Cursor, MCP Inspector, etc.) can talk to a tenant's templates, themes, stencils, and data contracts directly. The intended workflow is **AI-assisted template design**: the assistant discovers existing templates, inspects their structure, and renders previews to verify what they produce.

## Status

- **Read-only for 1.0 (deliberate GA scope).** Tools cover discovery, inspection, and document preview; the server never mutates tenant data. This is a settled decision for the 1.0 release, not a temporary limitation — authoring over MCP (drafts, themes, stencils, contract edits, publishing) is a possible post-1.0 addition, and adding write tools later is additive (no breaking change to the read surface).
- **Transport: Streamable HTTP**, mounted at `/api/mcp` on the same Spring Boot service that serves the UI and REST API.
- **Auth: `Authorization: ApiKey`.** Reuses the existing per-tenant API-key mechanism — no new credential type. Legacy `X-API-Key` remains accepted for existing clients.
- **Server-wide toggle: `epistola.mcp.enabled`** (default `true`). Setting it to `false` disables the entire MCP endpoint — no `/api/mcp` route, no Spring AI MCP server, no eager loading of the component registry JSON. Wired through to Spring AI's own `spring.ai.mcp.server.enabled`.

## Connecting an MCP client

Point your client at:

```
http://<your-epistola-host>:<port>/api/mcp
```

with the header

```
Authorization: ApiKey epk_<your-key>
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
        "Authorization": "ApiKey epk_...",
      },
    },
  },
}
```

### Example: MCP Inspector

```bash
npx @modelcontextprotocol/inspector
```

Then enter the URL and `Authorization: ApiKey <key>` header in the Inspector UI.

## Available tools

| Tool                      | Purpose                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| ------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `list_catalogs`           | Discover catalogs in the current tenant.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `preview_catalog_upgrade` | Preview what upgrading a SUBSCRIBED catalog to its source's latest release would do (source-vs-source): `added`/`removed`/`changed`/`unchanged` (each `type/slug`) + cross-catalog `conflicts`. Read-only — the upgrade action is UI-only.                                                                                                                                                                                                                                                                                   |
| `list_templates`          | List templates, optionally filtered by catalog or name.                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `get_template`            | Fetch a single template's metadata (no content).                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `get_template_content`    | Fetch the full editor context for a variant: template node/slot graph, variant attributes, data contract dataModel, named data examples. This is what the AI needs to "understand" a template.                                                                                                                                                                                                                                                                                                                               |
| `list_variants`           | List variants for a template.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `get_variant`             | Fetch a single variant's metadata.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `list_versions`           | List versions for a variant (drafts + published).                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `list_themes`             | Discover themes.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `get_theme`               | Full theme details: document styles, page settings, block style presets.                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `list_stencils`           | Discover stencils (reusable content blocks templates can embed).                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `get_stencil`             | Fetch a single stencil's metadata.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `list_stencil_versions`   | List all versions of a stencil with their parameter schemas. Use this to discover which versions declare parameters before fetching the full schema.                                                                                                                                                                                                                                                                                                                                                                         |
| `get_stencil_version`     | Fetch a single stencil version including its full parameter schema (JSON Schema) and content (template document fragment). Use this after `list_stencil_versions` to inspect a version before embedding it.                                                                                                                                                                                                                                                                                                                  |
| `get_data_contract`       | Fetch a template's data contract (JSON Schema + examples). Choose `status='draft'` or `status='published'`, or omit for the latest of either.                                                                                                                                                                                                                                                                                                                                                                                |
| `list_component_types`    | Describe every editor component type — slots, `allowedChildren`, applicable style keys, inspector fields, default props/styles, `parameters` (parameter schema when present), and **hand-curated `examples`** showing realistic usage patterns (template-document fragments the AI can copy and adapt). The TypeScript registry is the single source of truth; a JSON snapshot is generated at editor build time and read at runtime. See [`docs/component-registry.md`](component-registry.md) for the end-to-end pipeline. |
| `get_component_type`      | Fetch a single component type's descriptor by `type` — same shape as one entry of `list_component_types`, including its `examples`.                                                                                                                                                                                                                                                                                                                                                                                          |
| `preview_document`        | Render a preview PDF and return it base64-encoded. Variant, version, environment, and sample data are all optional.                                                                                                                                                                                                                                                                                                                                                                                                          |
| `list_code_lists`         | Discover code lists in the current tenant, optionally filtered by catalog. SUBSCRIBED-catalog lists (e.g. `system/bcp-47`, `system/iso-639-1`, `system/iso-3166-1-alpha2`) come back with `readOnly: true`.                                                                                                                                                                                                                                                                                                                  |
| `get_code_list`           | Fetch one code list by catalog + slug. Includes source metadata (INLINE/URL), `readOnly` flag, and the last-refresh status for URL-sourced lists.                                                                                                                                                                                                                                                                                                                                                                            |
| `list_code_list_entries`  | List the `{code, label}` entries of a code list. Hidden entries are filtered out unless `includeHidden=true`.                                                                                                                                                                                                                                                                                                                                                                                                                |
| `list_attributes`         | List variant-attribute definitions in the current tenant, optionally filtered by catalog. Carries constraint kind (`allowedValues` / `codeListBinding` / free-format), catalog origin, and the `readOnly` flag for SUBSCRIBED-catalog attributes (e.g. `system.locale`).                                                                                                                                                                                                                                                     |
| `get_attribute`           | Fetch one attribute definition by catalog + key. Same shape as one entry of `list_attributes`.                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `list_fonts`              | Discover font families in the current tenant, optionally filtered by catalog. Each entry carries the present variant faces (`regular`/`bold`/`italic`/`bold_italic`) and a `readOnly` flag for SUBSCRIBED-catalog families (the bundled `system` fonts: inter, roboto, lato, source-sans-3, source-serif-4, merriweather, lora, jetbrains-mono). Use this to pick a `fontFamily` ref (`{ slug, catalogKey }`) for a theme or template.                                                                                       |

## Limitations and notes

- **PDF preview is binary.** `preview_document` returns the rendered PDF as base64 in the `data` field along with `mediaType: "application/pdf"`. AI assistants generally cannot read PDF text directly; the field is most useful for users who want to view the rendered output. A future iteration may add a textual or HTML-rendered preview path.
- **Rich-text parameter values.** A data contract property may be declared as one of two rich-text shapes via `$ref`. Both accept a ProseMirror JSON document as the value, passed through `preview_document`'s `data` argument (or the REST API) exactly like any other JSON.
  - **Inline** (`$ref: "https://epistola.app/schemas/richtext-inline-v1.json"`, visual editor field type `richTextInline`) — a `doc` containing **exactly one** `paragraph` with `text` and `hard_break` nodes. Use for values that fit in a single line of formatted text (a name with bold marks, a greeting with a link). Bind through an inline expression chip.

    ```json
    {
      "customer": {
        "greeting": {
          "type": "doc",
          "content": [
            {
              "type": "paragraph",
              "content": [
                { "type": "text", "text": "Dear " },
                { "type": "text", "text": "John", "marks": [{ "type": "strong" }] }
              ]
            }
          ]
        }
      }
    }
    ```

  - **Block** (`$ref: "https://epistola.app/schemas/richtext-block-v1.json"`, visual editor field type `richTextBlock`) — a `doc` with multiple `paragraph` blocks, `bullet_list`, and `ordered_list` content. Use for free-form authored content (a customer bio with paragraphs and lists). Bind through the **Rich Text Variable** block component.

    ```json
    {
      "customer": {
        "bio": {
          "type": "doc",
          "content": [
            { "type": "paragraph", "content": [{ "type": "text", "text": "Founded in 1999." }] },
            {
              "type": "bullet_list",
              "content": [
                {
                  "type": "list_item",
                  "content": [
                    { "type": "paragraph", "content": [{ "type": "text", "text": "Quality" }] }
                  ]
                }
              ]
            }
          ]
        }
      }
    }
    ```

  Allowed marks (both shapes): `strong`, `em`, `underline`, `strikethrough`, `subscript`, `superscript`, `link` (with `attrs.href`), `textStyle` (with `attrs.color` as `#RRGGBB`). Headings and inline `expression` nodes are reserved for v2.

  The validator rejects mismatches: sending a list to a `richTextInline` field, or any block content to an inline expression chip's binding, fails preview with a JSON Schema error.

- **Data contract auto-creation.** Creating a template auto-creates an empty draft contract (v1). `get_data_contract` returns it even before the contract has been authored.
- **No write tools (by design for 1.0).** The MCP server cannot create or modify templates, drafts, themes, stencils, or contracts. Switch to the UI for authoring; the AI can still inspect what it has access to.
- **Rate limiting.** The MCP endpoint shares the existing `/api/**` security chain; no MCP-specific rate limiting is in place.

## Troubleshooting

- **`401 Unauthorized`**: The `Authorization: ApiKey` header is missing, the key is malformed (must start with `epk_`), or the key has been revoked/disabled/expired. Legacy `X-API-Key` is still accepted.
- **Tool errors mentioning permission**: The API key is missing `TEMPLATE_VIEW` (for read tools) or `DOCUMENT_GENERATE` (for preview).
- **`MCP request has no tenant scope`**: The principal has no `currentTenantId`. With API-key auth, this should not happen — the key is always tenant-scoped. If you see it, the key may have been provisioned without a tenant binding.

## Related

- [`modules/epistola-mcp/`](../modules/epistola-mcp) — module source
- [`apps/epistola/src/main/resources/application.yaml`](../apps/epistola/src/main/resources/application.yaml) — `spring.ai.mcp.server.*` settings
- [`docs/component-registry.md`](component-registry.md) — TS registry → JSON snapshot → backend pipeline that powers `list_component_types`
- [`scripts/mcp-smoke.sh`](../scripts/mcp-smoke.sh) — end-to-end smoke test against a running instance
- [`docs/api-keys.md`](api-keys.md) — API key provisioning (if present)
