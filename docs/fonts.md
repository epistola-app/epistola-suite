# Fonts

How Epistola models, resolves and renders fonts.

> **TL;DR** — A font is a **catalog resource**: a thin family grouping over
> per-face binaries keyed by CSS numeric `weight` (1–1000) + `italic`. A
> face's bytes are either a bundled classpath file (system fonts) or an
> uploaded `Asset` (customer fonts). The PDF renderer honours the selected
> `fontFamily` everywhere — body text, bold/italic marks and headings —
> resolving the nearest available weight. A catalog release holds its fonts'
> faces by content hash, so re-uploading a font never changes what a release
> renders.

## Model

A **font family** (`fonts` table, keyed by `(tenant, resource_id)` and addressed
as `(tenant, catalog, slug)`) groups **faces** (`font_variants`, keyed by the
family's identity plus `(weight, italic)`). A face names its family and its
binary by identity, so either can be relocated without disturbing the other.
Each face points at one of:

- **`CLASSPATH`** — a bundled file shipped once in the JAR (`source=CLASSPATH`,
  `classpath_location`). System fonts only; no `Asset` row, no per-tenant
  bytes.
- **`ASSET`** — an ordinary `assets` row (`source=ASSET`, `asset_resource_id`).
  Customer-uploaded faces; reuses all asset machinery (upload, content store,
  catalog export/import).

A face also stores `content_hash` (lowercase hex SHA-256 of the bytes,
recomputed by `ImportFont`).

Font binaries are **TTF/OTF only** — iText embeds sfnt-flavoured fonts; WOFF2
is rejected at upload. Allowed media types live in the seeded `asset_types`
lookup table (not an enum/CHECK); `AssetMediaType` is an open value class with
an `AssetMediaCategory` (IMAGE/FONT) split.

The multipart media type reported by a browser is advisory. Some browsers
derive it from OS-managed extension mappings, which can be missing or changed
on corporate devices. The web UI therefore canonicalizes `.ttf` and `.otf`
selections to `font/ttf` and `font/otf` before submission, and the server derives
the stored type from the accepted extension. Actual acceptance still depends on
`FontBytesValidator`: changing a filename or request header cannot make invalid,
corrupt, WOFF/WOFF2, or embedding-restricted bytes pass validation.

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
Liberation/Helvetica and log a `WARN` (never silent).

Three caches sit on this path:

| Cache                           | Holds                                           | Bounds                                                                 |
| ------------------------------- | ----------------------------------------------- | ---------------------------------------------------------------------- |
| `FontByteCache` (core)          | resolved font-file bytes and their content hash | Caffeine, 64 MB, 10 minutes after write                                |
| `FontProgramCache` (generation) | parsed fonts, shared by renders                 | segmented LRU, `epistola.generation.font-cache.max-size` / `max-faces` |
| `FontCache` (generation)        | `PdfFont`s for one document                     | dropped with the document                                              |

A parsed face takes far more heap than its file, and how much more depends on the font: iText
expands the kerning tables, so a 400 KB Inter face holds about 25 MB while a 270 KB JetBrains Mono
face holds about 1 MB. `FontProgramCache` therefore weighs each face by what parsing it allocated,
within about 1.3× of what it keeps, and keeps the total under `max-size` (default `384MB`, room for every bundled face) and the
count under `max-faces` (default `64`; `0` turns it off). The JVM reports allocation only for platform
threads, so a parse called from a render's virtual thread runs on one of two pooled platform threads;
the weight is remembered per content, so each font is measured once per JVM, even if it is evicted
and parsed again.

Eviction is a segmented LRU (`SegmentedLruCache`). A newly parsed face enters a _probation_ segment and is always kept, so
the render that parsed it finds it again in its next pass. A face a _second render_ uses moves to a
_protected_ segment, which may take 80% of each limit; past that its least recently used face drops
back to probation. Eviction takes probation's least recently used face first, so a burst of one-off
fonts does not push out the fonts many renders use. A render looks a face up several times (band
measuring, each layout pass), so promotion counts renders, not lookups: each render takes its own
scope of the cache. Caffeine's W-TinyLFU was tried first and dropped: it admits a new entry only if
it is used more often than what it would replace, so a freshly parsed face was refused and parsed
again within the same render.

The Helm chart sets both bounds through `generation.fontCache.*`, and the
`epistola.generation.font_cache.*` meters show occupancy, the protected share, hit rate, promotions
and which limit evicts ([metrics](metrics.md#font-cache)).

Parsed fonts are keyed by the SHA-256 of the face binary. `ResolveFontFace` returns the hash stored
in `font_variants.content_hash` with the bytes, so a render does not hash them again; the bundled
Liberation Sans faces are hashed once per JVM. Only bytes that arrive without a hash are hashed. A
`PdfFont` cannot be shared the same way: iText ties it to the PDF it is first used in, so each
document wraps the shared parsed font in its own, which costs about nothing.

Nothing parses through iText's own font cache (`PdfFontFactory.createFont(bytes, …)` with caching
on). That cache is static and never evicts, so until `FontProgramCache` replaced it every face a node ever rendered — or
validated on upload, through `FontBytesValidator` — stayed in its heap until restart. Parsing on
every render is no alternative: an Inter letter renders about three times slower and allocates ten
times more.

## System fonts

Nine OFL families ship with the suite. Eight (Inter, Source Sans 3, Roboto,
Lato, Source Serif 4, Merriweather, Lora, JetBrains Mono) are under
`modules/epistola-core/src/main/resources/epistola/fonts/<slug>/`. The ninth,
Liberation Sans (`liberation-sans`), is metric-compatible with Helvetica and
Arial, so a letter designed in either keeps its widths and line breaks; its
faces are the renderer's own fallback files in
`modules/generation/src/main/resources/fonts/`, registered from there. They are
seeded per tenant into the `system` catalog by `EnsureSystemFonts` (idempotent,
runs from `InstallSystemCatalog` on tenant create and every boot) as
`CLASSPATH` faces at `(400,upright)`, `(700,upright)`, `(400,italic)`,
`(700,italic)`. The model supports the full weight range; the bundled set just
ships those four faces. A test guards that every declared classpath path
resolves.

The default tenant theme and the demo catalog reference
`{ slug: "inter", catalogKey: "system" }`.

## Determinism

Generation renders catalog releases. A release holds each font family as it
was when the release was cut, its faces named by `content_hash`, and the bytes
are read from the content-addressed store by that hash
(`generation/release/ReleaseRenderSource.kt`). A font of another catalog comes
from the release of that catalog the environment deploys, else its latest
release that kept its content. Replacing a font under the same slug changes the
working copy and previews only; it reaches documents with the next release.
(Before 2.0 a published template version pinned per-family fingerprints in a
theme snapshot instead; those snapshots are gone.)

## Surfaces

| Surface                           | What                                                                                                                                                                               |
| --------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Web UI (`apps/epistola`)          | Font picker (backend-driven, `@font-face` injected, grouped into `<optgroup>` by catalog); upload/list/delete (`FontHandler`/`FontRoutes`, catalogs that can be changed here only) |
| REST (`modules/rest-api`)         | **Read-only** list/get (`EpistolaFontApi`) — deliberately no CRUD, mirroring assets                                                                                                |
| MCP (`modules/epistola-mcp`)      | `list_fonts`; `upload_font` (beta) creates or replaces a family                                                                                                                    |
| Catalog exchange                  | Font families + (asset-backed) faces export/import; cross-catalog refs emit `DependencyRef.Font`                                                                                   |
| Generation (`modules/generation`) | `FontFamilyResolver` SPI, `FontCache`, `FontBytesValidator` — JDBI/tenant-free                                                                                                     |

## Not yet built

### Variable fonts — instance at ingest, not at render (planned direction)

A variable font is a single binary with a continuous weight axis
(`fvar`/`gvar`/`STAT`) — `Inter[wght].ttf` covers 100–900 in one file.
**We will not render variable fonts directly.** PDF has no variable-font
concept and iText embeds only a static instance, so honouring an arbitrary
weight at render time would need (a) a render-time instancing pipeline and
(b) a redesign of the determinism guarantee — the per-family fingerprint /
`content_hash` would have to account for a versioned instancer or the same
"silently changed published output" bug class returns. Disproportionate.

Instead, when this is built it will be an **ingest-time** feature:

- On upload, detect a variable font (`fvar` present). Rather than storing it
  as a face, **instance its declared `fvar` named instances** (the
  weights/styles the designer actually shipped — Light/Regular/Medium/
  SemiBold/Bold…) into ordinary **static** TTF/OTF faces.
- Each instanced face is stored exactly like any other uploaded static face:
  an `Asset` + a `font_variants` row with its own `content_hash`.
- Everything downstream is **unchanged**: `ResolveFontFace` nearest-weight
  matching, `FontCache`, the byte cache, catalog export/import, and release
  rendering by content hash all operate on the static outputs — none of the hard
  render-time/determinism problems arise.

Why this cut: the only material gain of variable fonts for a document
product is **upload friction** — foundries and Google Fonts ship variable
by default, so a customer would otherwise have to source and upload each
static weight file separately. Instancing on upload removes that friction
while keeping the entire render / determinism / contract surface static.
The losses (truly-continuous arbitrary weights; one-file storage) are
negligible for business documents.

Open considerations:

- **Instancing library.** Producing a static snapshot at an axis position
  needs a font-instancing capability (Python's `fonttools instancer` is the
  reference; JVM options — HarfBuzz via JNI, or an OpenType lib — are
  thinner). The one real implementation risk, but bounded to the upload
  path: it can fail loudly with a clear error and never affects render
  correctness or determinism.
- **Italic axis.** Families ship italics either as a separate variable file
  or via an `ital`/`slnt` axis; ingest must handle both.
- **No `is_variable` in the model.** A variable font is a _transient input
  format_, never a stored face type — there is deliberately no
  `variable`/`is_variable` field on `FontVariantEntry` / `font_variants`. (A
  reserved flag existed briefly as render-time future-proofing and was
  removed once this ingest-instancing direction was decided.)
