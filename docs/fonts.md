# Fonts

How Epistola models, resolves and renders fonts.

> **TL;DR** — A font is a **catalog resource**: a thin family grouping over
> per-face binaries keyed by CSS numeric `weight` (1–1000) + `italic`. A
> face's bytes are either a bundled classpath file (system fonts) or an
> uploaded `Asset` (customer fonts). The PDF renderer honours the selected
> `fontFamily` everywhere — body text, bold/italic marks and headings —
> resolving the nearest available weight. Published versions pin a per-family
> content hash and **fail loudly** if the font bytes change.

## Model

A **font family** (`fonts` table, catalog-scoped: `(tenant, catalog, slug)`)
groups **faces** (`font_variants`, keyed by `(weight, italic)`, plus a
reserved `is_variable` flag). Each face points at one of:

- **`CLASSPATH`** — a bundled file shipped once in the JAR (`source=CLASSPATH`,
  `classpath_location`). System fonts only; no `Asset` row, no per-tenant
  bytes.
- **`ASSET`** — an ordinary `assets` row (`source=ASSET`, `asset_key`).
  Customer-uploaded faces; reuses all asset machinery (upload, content store,
  catalog export/import).

A face also stores `content_hash` (lowercase hex SHA-256 of the bytes,
recomputed by `ImportFont`).

Font binaries are **TTF/OTF only** — iText embeds sfnt-flavoured fonts; WOFF2
is rejected at upload. Allowed media types live in the seeded `asset_types`
lookup table (not an enum/CHECK); `AssetMediaType` is an open value class with
an `AssetMediaCategory` (IMAGE/FONT) split.

## The `fontFamily` style value

`fontFamily` in document styles / block-style presets / inline node styles is
a structured reference, mirroring `codeListBinding`:

```json
{ "slug": "inter", "catalogKey": "system" }
```

`catalogKey` omitted/null = the owning theme/template's catalog. The single
canonical parser is `FontRefs.parse` (generation); `StyleApplicator` wraps it
with a warn-on-malformed, and `DependencyScanner` reuses it for cross-catalog
dependency detection. The contract type is `app.epistola.catalog.protocol.FontRef`.

## Resolution (render path)

The face used for a run of text is **not** picked directly — it's derived:

- family ← the `fontFamily` ref;
- `weight` ← CSS `font-weight` (keywords + numeric, clamped 1–1000); bold
  marks raise it to ≥700; headings default to bold;
- `italic` ← `font-style` / italic marks.

`StyleApplicator` and `ProseMirrorConverter` call
`FontCache.font(ref, weight, italic)` → the generation `FontFamilyResolver`
SPI → the core `ResolveFontFace` query, which does **nearest-weight matching**:
prefer the requested italic set (else the other), then exact weight, else
minimal absolute distance, ties → heavier. Misses fall back to the built-in
Liberation/Helvetica and log a `WARN` (never silent). Resolved bytes are
process-cached (`FontByteCache`, Caffeine, 64 MB / 10 min); `PdfFont` objects
are additionally memoised per document by `FontCache`.

## System fonts

Eight OFL families (Inter, Source Sans 3, Roboto, Lato, Source Serif 4,
Merriweather, Lora, JetBrains Mono) ship under
`modules/epistola-core/src/main/resources/epistola/fonts/<slug>/`. They are
seeded per tenant into the `system` catalog by `EnsureSystemFonts` (idempotent,
runs from `InstallSystemCatalog` on tenant create and every boot) as
`CLASSPATH` faces at `(400,upright)`, `(700,upright)`, `(400,italic)`,
`(700,italic)`. The model supports the full weight range; the bundled set just
ships those four faces. A test guards that every declared classpath path
resolves.

The default tenant theme and the demo catalog reference
`{ slug: "inter", catalogKey: "system" }`.

## Determinism

A published template version snapshots a per-family **fingerprint** (digest
over the family's sorted face `content_hash`es) in `ResolvedThemeSnapshot`.
Before rendering a published version, `FontSnapshotVerifier` recomputes the
live fingerprint; on any drift (a face added/removed/re-uploaded with
different bytes) it throws `FontIntegrityException` rather than silently
re-rendering different glyphs. Draft/live renders pin nothing and skip the
check. Operationally: replacing a font under the same slug breaks affected
published versions until they are republished — by design.

## Surfaces

| Surface                           | What                                                                                                                         |
| --------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| Web UI (`apps/epistola`)          | Font picker (backend-driven, `@font-face` injected); upload/list/delete (`FontHandler`/`FontRoutes`, AUTHORED catalogs only) |
| REST (`modules/rest-api`)         | **Read-only** list/get (`EpistolaFontApi`) — deliberately no CRUD, mirroring assets                                          |
| MCP (`modules/epistola-mcp`)      | Read-only `list_fonts`                                                                                                       |
| Catalog exchange                  | Font families + (asset-backed) faces export/import; cross-catalog refs emit `DependencyRef.Font`                             |
| Generation (`modules/generation`) | `FontFamilyResolver` SPI, `FontCache`, `FontBytesValidator` — JDBI/tenant-free                                               |

## Not yet built

- **Variable-font rendering.** The schema carries `is_variable`, but a
  single-binary weight-axis font is not yet instanced by iText — only static
  per-weight faces render. Out of scope until iText axis-instancing is proven.
