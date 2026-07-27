# Catalog Contract Upgrade Compatibility

This note describes the Suite impact of adopting the portable
`epistola-catalog` aggregate and its shared validators. The contract's
authoritative list of format and artifact changes is
[`epistola-contract/docs/catalog-compatibility.md`](https://github.com/epistola-app/epistola-contract/blob/main/docs/catalog-compatibility.md).

## Editor-created content

Templates and catalogs created by the current editor use the canonical shapes
required by the shared validator:

- text content is a ProseMirror document object;
- linked stencil instances carry `stencilId`, an exact positive `version`, and
  an explicit `isDraft` boolean;
- published templates refer to published stencil versions;
- the editor maintains a rooted, owned, reachable node/slot graph.

The stencil identity fields have been emitted by the editor since the stencil
state model landed in April 2026, and the ProseMirror object model predates the
current production period. Therefore content created only through the editor
during the last month is expected to remain compatible.

This is not an unconditional backward-compatibility guarantee. The following
historical or malformed shapes are intentionally rejected when they cross a
strict boundary:

- string or bare-array text content;
- stencil nodes missing `stencilId`, positive `version`, or boolean `isDraft`;
- cycles, unreachable nodes, inconsistent node/slot ownership, unsupported
  components, or invalid property shapes;
- stencil-instance ancestry deeper than five levels;
- draft stencil references in a published catalog.

## What happens when an instance upgrades

The dependency upgrade does not scan, rewrite, or bulk-revalidate every stored
template at startup.

| Operation after upgrade                | Validation behavior                                                                                                                                                                                                 |
| -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Render an existing published template  | Applies graph/type traversal-safety checks so an otherwise valid stored document continues rendering.                                                                                                               |
| Open and save a template draft         | The current editor sends the canonical model; the shared validator checks it before the write.                                                                                                                      |
| Publish a template or stencil draft    | Revalidates the stored draft and rejects a historical malformed shape instead of freezing it.                                                                                                                       |
| Copy or upgrade stored stencil content | Revalidates the source content before reusing it.                                                                                                                                                                   |
| Import or re-import a catalog          | Applies the complete portable archive, wire, resource, reference, schema, hash, and template validation policy before mutation.                                                                                     |
| Export a catalog                       | Builds current `schemaVersion: 4` content from published resources. A legacy invalid stored document can still produce an archive that the portable validator rejects, so validate or round-trip important exports. |

This means an already-published editor-created template is not invalidated just
because the application starts with the new library. The realistic failure
mode is a historical malformed draft being blocked when it is next saved,
copied, published, or exported. The finding identifies the exact document path
that must be corrected.

## Stencil composition boundary

The portable contract can represent and validate one published stencil
referencing another, including recursion and five-level depth checks. Suite
does not yet expose nested-stencil definition authoring: its adapter preserves
the existing create, update, import, and publish capability gate. Templates
may continue to contain ordinary stencil instances and placeholder fills.

## Deployment check

Before upgrading a production instance with older or externally manipulated
content, test a recent database snapshot and exercise:

1. preview and generation for representative published templates;
2. save and publish for representative open drafts;
3. export and validation or test-import of each authored catalog that must
   remain portable.

An installation whose content was created only with the current editor and has
not been modified through older APIs or direct database changes is low risk,
but the snapshot check remains the authoritative verification.
