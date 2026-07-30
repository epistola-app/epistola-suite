# Epistola MCP debugging reference

The Epistola MCP connection supplies tenant scope and authentication. Tool
arguments use catalog, template, variant, version, stencil, image, font, and
code-list identifiers; they never take an API key or tenant ID.

## Tool map

Use the smallest calls that can establish the render graph:

| Concern | Tools |
| --- | --- |
| Catalog and template identity | `list_catalogs`, `list_templates`, `get_template` |
| Variant and exact content | `list_variants`, `get_variant`, `list_versions`, `get_version` |
| Current editor context | `get_template_content` |
| Themes | `list_themes`, `get_theme` |
| Stencils | `list_stencils`, `get_stencil`, `list_stencil_versions`, `get_stencil_version` |
| Images | `list_images`, `get_image` |
| Fonts | `list_fonts` |
| Data | `get_data_contract` |
| Attributes and code lists | `list_attributes`, `get_attribute`, `list_code_lists`, `get_code_list`, `list_code_list_entries` |
| Component semantics | `list_component_types`, `get_component_type` |
| Controlled rendering | `preview_document` |

`get_version` is authoritative for one persisted draft or published template
version. It also exposes a published version's frozen `resolvedTheme` and
`renderingDefaultsVersion` when available. `get_template_content` describes the
current editor context and may therefore represent a different version.

## Rendering cascade

Resolve each property independently. A useful general order is:

1. renderer defaults;
2. resolved theme document and page settings;
3. template document and page overrides;
4. theme block preset;
5. node inline styles.

Published versions normally use the resolved theme snapshot frozen when they
were published. Drafts use the live theme cascade. A partial override replaces
only the fields it contains.

Two templates can reference the same stencil version and image while rendering
them differently. The stencil instance, its containing page band, and the
template/theme cascade remain separate inputs.

## Page headers and footers

Root slot order matters. When a document contains two page headers, the first
is for page one and the second for subsequent pages.

For a page header, trace:

```text
effective page top margin
→ header band position and height
→ containing-node margin/padding
→ stencil-instance margin/padding
→ stencil content and image sizing
```

The renderer default page margin is 20 mm when no theme or template override
supplies that side. Consequently, a template inheriting 20 mm and another
overriding the top margin to 11 mm will place identical header content at
different vertical positions. Instance padding can add a second offset.

## Resource checks

For each stencil reference, preserve the tuple `(catalog, stencil, version)`.
Inspect that exact version rather than the latest version, and repeat for nested
references.

For each image, preserve `(catalog, UUID)` and compare:

- media type;
- pixel dimensions, where applicable;
- byte size;
- catalog provenance and read-only status.

Equal metadata is strong evidence that layout, not image identity, causes a
positioning difference. The MCP tools intentionally do not return binary image
content.

For each font reference, preserve `(catalog, slug)`. Verify the requested
weight and italic combination. A missing or different face can change
measurement, wrapping, band height, and pagination.

Inspect code-list entries only when labels, conditions, locale, or variant
selection can affect the output. Avoid reproducing contract examples or
document values.

## Client portability

This directory follows the open Agent Skills `SKILL.md` format and contains no
runtime script dependency. A client still needs an Epistola MCP connection.
Configure its endpoint and credentials in the client's connection settings,
outside the skill and conversation.

Some hosted clients do not support Epistola's static `Authorization: ApiKey`
header directly. In that case, the skill remains importable, but live tool use
requires an administrator-provided OAuth-compatible Epistola connector or
gateway. Never work around that limitation by pasting a key into the prompt.
