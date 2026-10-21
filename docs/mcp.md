# MCP server (AI assistant integration)

Epistola exposes a [Model Context Protocol](https://modelcontextprotocol.io) server so AI assistants (Claude Desktop, Cursor, MCP Inspector, etc.) can talk to a tenant's templates, themes, stencils, and data contracts directly. The intended workflows are **AI-assisted template design** — the assistant discovers existing templates, inspects their structure, and renders previews to verify what they produce — and **document conversion**: the assistant turns an existing document, such as a Word template, into an Epistola template with its stencils, theme, images and fonts.

## Status

- **Read tools are GA; write tools are beta.** The read tools cover discovery, inspection and document preview. The [write tools](#write-tools-beta) author a draft — templates, variants, data contracts, stencils, themes, images and fonts — mark it ready, and manage a catalog's [drafts](#catalog-versions-beta), including releasing one; they may still change their arguments in a minor release. Nothing over MCP deploys a release or deletes a resource: whether a release is used to generate documents is still decided in the UI.
- **Transport: Streamable HTTP**, mounted at `/api/mcp` on the same Spring Boot service that serves the UI and REST API.
- **Auth: `Authorization: ApiKey`.** Reuses the existing per-tenant API-key mechanism — no new credential type.
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
2. Have the **`TEMPLATE_VIEW`** permission (read tools) and the **`DOCUMENT_GENERATE`** permission (for `preview_document`). `analyze_template_data` needs only `TEMPLATE_VIEW`: it renders nothing. Write tools need the edit permission of what they write — see [Write tools](#write-tools-beta). A key scoped to viewing roles gets every write refused, so choose the key's roles to match what the assistant may change.

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

| Tool                              | Purpose                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| --------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `list_catalogs`                   | Discover catalogs in the current tenant, including qualified attributes, exact-case keywords, presentation asset slugs, and license metadata. Each says whether it can be changed here (`editable`; `readOnlyReason` when not), where its releases come from (`source`) and its highest release (`releasedVersion`), released here or received.                                                                                                                                                                               |
| `list_available_catalog_releases` | The releases a catalog's source offers that the tenant lacks, lowest first, older lines included, each against the closest installed release below it (`comparedTo`) with what it added, modified or removed and whether the releases it pins are here. Read-only: installing is not available over MCP.                                                                                                                                                                                                                      |
| `list_catalog_dependencies`       | The catalogs a catalog declares it depends on: each with the exact `release` it pins, which its next release is cut with and renders with, or the draft of it it builds on (`draftId`, `draftName`).                                                                                                                                                                                                                                                                                                                          |
| `list_templates`                  | List templates, optionally filtered by catalog or name. A `catalogId` that does not exist is an error, not an empty list, here and in `list_themes`, `list_stencils` and `list_images`.                                                                                                                                                                                                                                                                                                                                       |
| `get_template`                    | Fetch a single template's metadata (no content).                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `get_template_content`            | Fetch the full editor context for a variant: template node/slot graph, variant attributes, data contract dataModel, named data examples. This is what the AI needs to "understand" a template.                                                                                                                                                                                                                                                                                                                                |
| `list_variants`                   | List variants for a template.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `get_variant`                     | Fetch a single variant's metadata.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `list_themes`                     | Discover themes.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `get_theme`                       | Full theme details: document styles, page settings, block style presets.                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `get_catalog_theme_settings`      | A catalog version's default theme (what its templates that name none render with) and the presets it declares, plus the presets its content may use: its own and its dependencies' (ADR 0007).                                                                                                                                                                                                                                                                                                                                |
| `list_stencils`                   | Discover stencils (reusable content blocks templates can embed).                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `get_stencil`                     | Fetch a stencil with its content (a template document) and its parameter schema (JSON Schema).                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `list_stencil_usages`             | List the template versions that use a stencil and whether each still fits it as it is now; run after changing a stencil.                                                                                                                                                                                                                                                                                                                                                                                                      |
| `get_data_contract`               | Fetch a template's data contract (JSON Schema + examples) as it is now.                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `get_catalog_changes`             | What the next release of a catalog that can be changed here would contain that its last one did not, resource by resource, each with whether it is marked ready; `releasable` is false while a changed resource is not.                                                                                                                                                                                                                                                                                                       |
| `list_component_types`            | Describe every editor component type — slots, `allowedChildren`, applicable style keys, inspector fields, default props/styles, `parameters` (parameter schema when present), and **hand-curated `examples`** showing realistic usage patterns (template-document fragments the AI can copy and adapt). The TypeScript registry is the single source of truth; a JSON snapshot is generated at editor build time and read at runtime. See [`docs/component-registry.md`](component-registry.md) for the end-to-end pipeline.  |
| `get_component_type`              | Fetch a single component type's descriptor by `type` — same shape as one entry of `list_component_types`, including its `examples`.                                                                                                                                                                                                                                                                                                                                                                                           |
| `preview_document`                | Render a preview PDF and return it base64-encoded: the catalog's latest release by default, the highest release satisfying a version selector with `releaseVersion` (`1.4.0`, `^1.4.0`), the working copy with `workingCopy`, or what an environment uses with `environmentId`: the release or draft it was pointed at, else the highest release it serves (combinable with `releaseVersion`). Variant and sample data are optional.                                                                                          |
| `analyze_template_data`           | Check data against the contract of the release `preview_document` would render (`releaseVersion` as a version selector, `environmentId`, both, or the latest), without rendering. Returns the missing fields (required ones always, optional ones only when the template reads them) and the invalid fields, each with a JSON Pointer into `data` and the field's JSON Schema, so an agent can ask for exactly the data the template still needs. See [Missing and invalid data](data-contracts.md#missing-and-invalid-data). |
| `list_code_lists`                 | Discover code lists in the current tenant, optionally filtered by catalog. Lists of a catalog that cannot be changed here (e.g. `system/bcp-47`, `system/iso-639-1`, `system/iso-3166-1-alpha2`) come back with `editable: false`, `readOnly: true`.                                                                                                                                                                                                                                                                          |
| `get_code_list`                   | Fetch one code list by catalog + slug. Includes source metadata (INLINE/URL), `editable` / `readOnly`, and the last-refresh status for URL-sourced lists.                                                                                                                                                                                                                                                                                                                                                                     |
| `list_code_list_entries`          | List the `{code, label}` entries of a code list. Hidden entries are filtered out unless `includeHidden=true`.                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `list_attributes`                 | List variant-attribute definitions in the current tenant, optionally filtered by catalog. Carries constraint kind (`allowedValues` / `codeListBinding` / free-format), catalog origin, and `editable` / `readOnly`: whether its catalog can be changed here (never for `system.locale`).                                                                                                                                                                                                                                      |
| `get_attribute`                   | Fetch one attribute definition by catalog + key. Same shape as one entry of `list_attributes`.                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `list_fonts`                      | Discover font families in the current tenant, optionally filtered by catalog. Each entry carries the present variant faces (`regular`/`bold`/`italic`/`bold_italic`) and `editable` / `readOnly`: whether its catalog can be changed here (never for the bundled `system` fonts: inter, roboto, lato, source-sans-3, source-serif-4, merriweather, lora, jetbrains-mono). Use this to pick a `fontFamily` ref (`{ slug, catalogKey }`) for a theme or template.                                                               |
| `list_images`                     | Discover images in the current tenant, optionally filtered by catalog or name. Returns image IDs, media types, dimensions, sizes, and catalog provenance without binary content.                                                                                                                                                                                                                                                                                                                                              |
| `get_image`                       | Fetch metadata for one exact image reference by catalog and UUID. Use this to verify image identity and dimensions while tracing template and stencil dependencies.                                                                                                                                                                                                                                                                                                                                                           |
| `list_catalog_versions`           | A catalog's drafts and releases: its next release, then its other drafts (each with its `versionId`, its base and the releases it is `behind`, with their changes), then its releases, highest first. See [Catalog versions](#catalog-versions-beta).                                                                                                                                                                                                                                                                         |
| `get_authoring_schemas`           | The JSON Schemas the write tools accept — `templateDocument`, `theme`, the `shared` definitions both refer to — and the style registry (every style key, type and unit). Ships in the `epistola-catalog` artifact.                                                                                                                                                                                                                                                                                                            |

## Write tools (beta)

Enough to convert an existing document into Epistola. Every write dispatches the same command the UI
and REST API use, so validation, permissions and the rule that only a catalog that can be changed here
is written to (never `system`, and none in an installation that only runs releases) apply unchanged. Omitted optional arguments leave a field as it is.

| Tool                           | Purpose                                                                                                                                                                                                                                                    | Permission                        |
| ------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------- |
| `create_catalog`               | Create an empty catalog to build templates, themes and stencils in.                                                                                                                                                                                        | `CATALOG_MANAGE`                  |
| `update_catalog_dependencies`  | Replace the catalogs a catalog depends on, each `{catalogId, release?, draftId?}`: an exact release to pin (its highest when neither is given), or a draft of it to build on. Writes that use another catalog declare it already, naming its next release. | `TEMPLATE_EDIT`                   |
| `upgrade_catalog_dependencies` | Move every dependency pin to the highest release of it; a declaration naming a draft stays.                                                                                                                                                                | `TEMPLATE_EDIT`                   |
| `create_template`              | Create a template, optionally with a theme and default-variant attributes (e.g. `system.locale`). It starts with a default variant holding an empty document, and an empty data contract.                                                                  | `TEMPLATE_EDIT`                   |
| `update_template`              | Change a template's name, theme (or clear it) and PDF/A flag.                                                                                                                                                                                              | `TEMPLATE_EDIT`                   |
| `create_variant`               | Add a variant with attributes, e.g. `{"system.locale": "nl-NL"}`. Values are validated; identical attribute sets are accepted but make selection ambiguous.                                                                                                | `TEMPLATE_EDIT`                   |
| `update_variant`               | Change a variant's title or replace its attributes.                                                                                                                                                                                                        | `TEMPLATE_EDIT`                   |
| `update_variant_content`       | Replace a variant's working model with a template document; an edit makes its ready mark stale. With `returnContent`, returns the stored (normalised) document for diffing.                                                                                | `TEMPLATE_EDIT`                   |
| `update_data_contract`         | Set a template's JSON Schema (`dataModel`) and examples; an edit makes its variants' ready marks stale.                                                                                                                                                    | `TEMPLATE_EDIT`                   |
| `create_stencil`               | Create a stencil (content, parameter schema and whether it reads the template's data optional).                                                                                                                                                            | `STENCIL_EDIT`                    |
| `update_stencil`               | Change a stencil's name, description, tags, content, parameter schema or whether it reads the template's data. A content change applies to every template using it.                                                                                        | `STENCIL_EDIT`                    |
| `create_theme`                 | Create a theme with document styles, page settings, block style presets and spacing unit.                                                                                                                                                                  | `THEME_EDIT`                      |
| `update_theme`                 | Change a theme. A given styling field replaces that whole field, so read it with `get_theme` and send back the merged value; `mergeBlockStylePresets` / `removeBlockStylePresets` add, replace or drop single presets instead.                             | `THEME_EDIT`                      |
| `set_catalog_theme_settings`   | Set a catalog version's default theme (its own or a dependency's) and/or replace its declared presets. Once presets are declared, a block's `stylePreset` must be one of the available ones.                                                               | `TEMPLATE_EDIT`                   |
| `apply_house_theme`            | Make the tenant's house theme the default theme of every catalog's next release that can be changed here; each takes it on its next publish.                                                                                                               | `TENANT_SETTINGS`                 |
| `upload_image`                 | Upload an image as base64 or a `data:` URL, optionally under a readable key such as `company-logo`. Images are immutable; upload a new one.                                                                                                                | `TEMPLATE_EDIT`                   |
| `upload_font`                  | Create or replace a font family from base64 TTF/OTF faces. Every face is checked for embeddability before anything is stored.                                                                                                                              | `TEMPLATE_EDIT`, `REFERENCE_EDIT` |
| `mark_variant_ready`           | Record that a variant's content, with its data contract, is reviewed and may be released; refused while a stencil instance does not fit.                                                                                                                   | `TEMPLATE_EDIT`                   |
| `mark_template_ready`          | Mark every variant of a template ready.                                                                                                                                                                                                                    | `TEMPLATE_EDIT`                   |
| `mark_stencil_ready`           | Record that a stencil's content is reviewed and may be released.                                                                                                                                                                                           | `STENCIL_EDIT`                    |
| `mark_catalog_ready`           | Mark every new or modified resource of a catalog ready, and return what remains.                                                                                                                                                                           | `TEMPLATE_EDIT`                   |

### Catalog versions (beta)

Every catalog has a **next release**, where everyday work goes, and may hold other
**drafts** next to it: named, each released on its own, such as a redesign or a fix to an older line
(contract ADR 0002). A draft has no number until it is released; every version has a
`versionId`, which a release keeps from its draft. Every read and write tool that addresses catalog
content takes an optional **`catalogVersion`**: `next`, or a draft's `versionId` from
`list_catalog_versions`. Omitted, a tool works on the catalog's next release, which for a received
catalog starts on the release it installed. It needs `catalogId`: a list tool given `catalogVersion` without one is refused,
and so is a version number, which names no draft. `preview_document` with `catalogVersion` renders
that draft's working copy, and cannot be combined with `releaseVersion` or `environmentId`.
`analyze_template_data` checks against a release and takes no `catalogVersion`.

| Tool                     | Purpose                                                                                                                                                                                                                                                                                                                                                                                          | Permission        |
| ------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------- |
| `start_draft`            | Start a draft, named: a copy of a release (`from` its number; the highest by default), its base, or of a draft (`next` or its `versionId`), unreleased changes included.                                                                                                                                                                                                                         | `TEMPLATE_EDIT`   |
| `rename_draft`           | Give a draft another name; its `versionId` stays. The next release has no name of its own.                                                                                                                                                                                                                                                                                                       | `TEMPLATE_EDIT`   |
| `preview_release_number` | The number releasing a draft would give it: the kind of change against its base, the number that needs (`needed`), the one it gets (`suggested`, the next free one of that kind), and the `reasons` that make it more than a fix.                                                                                                                                                                | `CATALOG_VIEW`    |
| `release_draft`          | Release a draft (the next release by default), numbered as its changes need, or as `version` when that is higher and free. Refused while a changed resource is not ready, while it is behind a release `acknowledgedBehind` does not name, and while it builds on drafts of other catalogs unless `releaseDependencies` releases those first. Returns `draftsBehind` and `releasedDependencies`. | `CATALOG_RELEASE` |
| `discard_draft`          | Discard a draft: the next release (`next`) starts again from the latest release, another draft is removed. Refused while another catalog's draft builds on it, and while it alone holds something used outside it (the tenant's default theme, a code list another catalog's attribute binds), naming it.                                                                                        | `CATALOG_RELEASE` |
| `preview_merge`          | Alpha. What merging a release into a draft (the next release by default) would do, per resource a side changed: `THEIRS` (taken), `OURS` (stays) or `CONFLICT` (the user chooses).                                                                                                                                                                                                               | `CATALOG_VIEW`    |
| `merge_release`          | Alpha. Merge a release into a draft: takes what only the release changed, keeps what only the draft changed, and resolves each conflict as `resolutions` says (`OURS` or `THEIRS`). Refused with a conflict left open, when the release removes what the draft still uses, and when the draft already contains it.                                                                               | `TEMPLATE_EDIT`   |

Releasing and discarding change what can be generated and cannot be undone, so an assistant does
them only when the user asks.

### Catalog registries (alpha)

Releases move between installations through a container registry configured for the installation
([catalog-registry.md](catalog-registry.md)). Without one configured they refuse every call; an
unknown registry name is refused with the names that are configured. REST has no counterpart yet.

| Tool                            | Purpose                                                                                                                                                                                     | Permission        |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------- |
| `list_registry_releases`        | Alpha. The releases of `namespace`/`catalogKey` a registry holds, highest first, with fingerprint label, digest, size and whether the tenant has each (`present`), and a `conflict` if any. | `CATALOG_VIEW`    |
| `publish_release_to_registry`   | Alpha. Publish a release as `<namespace>/<catalogKey>:<version>`. The same number with the same fingerprint pushes nothing (`alreadyPublished`); other content is refused.                  | `CATALOG_PUBLISH` |
| `install_release_from_registry` | Alpha. Install a release from a registry, as importing its archive does: digest verified, fingerprint recomputed. A new catalog is created receiving its releases from that registry.       | `CATALOG_MANAGE`  |

Structured arguments — template documents, schemas, styles, attributes, examples, font faces — are
passed as JSON strings, like `preview_document`'s `data`. A malformed one fails with a message naming
the argument. A template document is the node/slot graph `get_template_content` returns under
`templateModel`; build it from `list_component_types` (its `examples` are fragments to copy) and
check it against `get_authoring_schemas`. Write expressions in `jsonata`: the shared schema still
lists `javascript`, but JavaScript expressions are no longer supported and a document containing
one is rejected.

A typical conversion: `create_catalog` when the work needs its own catalog, `upload_font` and `upload_image` for the document's typeface and pictures,
`create_theme` for its page setup and styles, `create_stencil` for repeated blocks such as a
letterhead, `create_template` with the theme and `system.locale`, `update_data_contract` for the
merge fields, then `update_variant_content` and `preview_document` until the result matches. Preview
the working copy by passing `workingCopy: true`: without it, `preview_document` renders the catalog's latest
release, which a new template is not in until the catalog is released.

Using a stencil means writing a `stencil` node that names it: `stencilKey`, and `catalogKey` when
the stencil is in another catalog, plus `parameterBindings` (JSONata per parameter) when it has
parameters. Its `children` slot holds one `placeholder` node per placeholder you fill, named like
the stencil's placeholder, whose `fill` slot holds your content. Nothing of the stencil itself is
stored: `get_template_content` returns the stencil's content inlined around your fills, and a
template written back that way is stored as the reference again. A template renders the stencil as
it is, so changing a stencil changes every template that uses it; `list_stencil_usages` lists those
that no longer fit. A stencil may itself use stencils.

Finishing a catalog built this way: `mark_template_ready` for each template (or `mark_catalog_ready`
for everything changed), after the user has reviewed it, and `release_draft` when the
user asks for a release. Marking is refused while a stencil instance
does not fit its stencil: a fill for a placeholder the stencil does not have, or a required parameter
left unbound. `get_catalog_changes` shows what is still not ready. A template has no versions: the
catalog release is the only one, and an edit makes a mark stale.

Not available over MCP: deploying releases to environments, and deleting resources or releases.

## Concurrent edits

Read tools (`get_template`, `get_variant`, `get_template_content`, `get_data_contract`, `get_theme`,
`get_stencil`) return the content's `revision`. Pass it back as `expectedRevision` to the matching
update tool (`update_template`, `update_variant`, `update_variant_content`, `update_data_contract`,
`update_theme`, `update_stencil`): when someone changed the content since, the update is refused
with "changed by someone else" and nothing is written; read it again and reapply the change. An
update without `expectedRevision` overwrites whatever is there, as before. Each successful update
returns the new `revision`, for the next one.

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

- **Data contract on creation.** Creating a template gives it an empty data contract. `get_data_contract` returns it even before the contract has been authored.
- **Deploying stays in the UI.** An assistant can release a draft when asked, but deploying a release to an environment, and deleting resources and releases, stay in the UI (or the REST API), so a person decides when a release is used to generate documents.
- **Rate limiting.** The MCP endpoint shares the existing `/api/**` security chain; no MCP-specific rate limiting is in place.

## Troubleshooting

- **`401 Unauthorized`**: The `Authorization: ApiKey` header is missing, the key is malformed (must start with `epk_`), or the key has been revoked/disabled/expired. An `X-API-Key` header is not read.
- **Tool errors mentioning permission**: The API key is missing `TEMPLATE_VIEW` (for read tools), `DOCUMENT_GENERATE` (for preview), or the edit or publish permission a [write tool](#write-tools-beta) needs (`CATALOG_RELEASE` comes with the content publisher role, the edit permissions with the content author role, `CATALOG_MANAGE` for `create_catalog` with the tenant administrator role).
- **Tool errors mentioning a read-only catalog**: Write tools only write to a catalog that can be changed here (`editable` in `list_catalogs`). The catalogs Epistola supplies, such as `system`, never can, and none can in an installation that only runs releases (`epistola.catalog.editing.enabled: false`); `readOnlyReason` says which.
- **A tool this page lists is missing in the client**: MCP clients cache the tool list from when they connected. After upgrading Epistola, reconnect the server in the client (in Claude Code, `/mcp`) to pick up new tools and changed descriptions.
- **`MCP request has no tenant scope`**: The principal has no `currentTenantId`. With API-key auth, this should not happen — the key is always tenant-scoped. If you see it, the key may have been provisioned without a tenant binding.

## Related

- [`modules/epistola-mcp/`](../modules/epistola-mcp) — module source
- [`apps/epistola/src/main/resources/application.yaml`](../apps/epistola/src/main/resources/application.yaml) — `spring.ai.mcp.server.*` settings
- [`docs/component-registry.md`](component-registry.md) — TS registry → JSON snapshot → backend pipeline that powers `list_component_types`
- [`scripts/mcp-smoke.sh`](../scripts/mcp-smoke.sh) — end-to-end smoke test against a running instance
- [`docs/authorization.md`](authorization.md) — API key scoping and permissions
