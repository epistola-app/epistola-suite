---
name: debug-epistola-templates
description: Diagnose Epistola template failures and rendering differences by tracing versions, themes, stencils, images, fonts, code lists, contracts, and previews through its read-only MCP tools.
---

# Debug Epistola Templates

Use the connected Epistola MCP server to compare the persisted inputs that
produce a document. Diagnose and explain; do not modify the Epistola instance.

## Preconditions

- Require a connected Epistola MCP server scoped to the tenant under
  investigation.
- Accept Epistola UI URLs or catalog/template/variant identifiers. Extract the
  identifiers from URLs; the MCP server already derives the tenant from its
  connection.
- If the Epistola tools are unavailable, stop and explain that this skill
  requires an Epistola MCP connection. Do not replace it with guessed REST
  endpoints or shell scripts.

Read [references/mcp.md](references/mcp.md) before investigating.

## Safety

- Use only Epistola tools marked read-only. `preview_document` is a read-only
  render operation and is allowed when a preview is needed.
- Never request, repeat, log, store, or place an API key in a prompt or tool
  argument. Authentication belongs in the MCP client connection.
- Treat contracts, examples, and rendered documents as potentially sensitive.
  Report field names and structural differences, not example values.
- Never dump complete template models, contracts, examples, or base64 preview
  data into the response.

## Investigation

1. Identify both render targets precisely: catalog, template, variant, draft or
   version number, environment if applicable, and input/example selection.
2. Use `get_template`, `list_variants`, `get_variant`, and `list_versions` to
   verify those identities. Compare like with like.
3. Fetch every exact compared version with `get_version`. Use
   `get_template_content` only when current editor context, contract shape, or
   named examples are relevant; it is not a substitute for historical version
   content.
4. Diff the template documents structurally, starting with:

   - `themeRef`, `pageSettingsOverride`, and `documentStylesOverride`;
   - root slot order and page header/footer nodes;
   - page-band height, margins, padding, and styles;
   - stencil instance props, styles, preset, parameters, and containing node;
   - locale-sensitive expressions and conditional structure.

5. Trace referenced resources:

   - For published versions, prefer the `resolvedTheme` snapshot returned by
     `get_version`. For drafts, resolve the template theme with `get_template`
     and `get_theme`; use `list_themes` only for discovery.
   - For every stencil node, call `get_stencil_version` with its exact catalog,
     stencil ID, and pinned version. Follow nested stencil references
     recursively, recording visited references to avoid cycles.
   - For every image reference, call `get_image` with its catalog and UUID.
     Compare identity, media type, dimensions, size, and catalog provenance.
   - Use `list_fonts` for every referenced font family and verify that the
     required weight/italic face exists in the referenced catalog.
   - Resolve referenced attributes and code lists with `get_attribute`,
     `get_code_list`, and `list_code_list_entries` when they affect variant
     choice, labels, conditions, or formatting.
   - Use `get_data_contract` with a status aligned to the render target. Inspect
     shape and referenced paths without reproducing sample values.

6. Call `preview_document` only when a controlled re-render adds evidence.
   Pass an explicit `versionId` or `environmentId` when the observed output
   depends on one. Never compare its base64 payload in prose.
7. Locate the first differing effective render input and connect it to the
   visible symptom using the rendering cascade. Separate observed fields,
   derived effective values, and remaining visibility limits.

## Response

Lead with the root cause. State:

- the exact targets and versions compared;
- the smallest relevant fields and values;
- the cascade or layout rule that makes those fields visible;
- whether the difference is in drafts, published versions, or both;
- important causes ruled out, such as an identical stencil version and image
  UUID;
- the smallest remediation, explicitly marked as a recommendation and not
  performed.

If the MCP surface cannot prove a tenant default, environment activation, or
legacy frozen value, say so instead of guessing.
