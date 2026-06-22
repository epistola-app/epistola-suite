package app.epistola.suite.catalog.migrations

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * The kind of stored content blob an at-rest migration step operates on,
 * identifying the **carrier column** it came from. [TEMPLATE_MODEL] and
 * [STENCIL_CONTENT] are both `TemplateDocument` trees (same shape) but kept
 * distinct so a step can target one — e.g. a template-only transform applies to
 * `template_versions.template_model` but not stencil content, matching the wire
 * path's per-resource-type behaviour. The blob is the bare domain value (the same
 * JSON the wire format nests under `resource`).
 *
 * See `docs/adr/0007-at-rest-resource-migration.md`.
 */
object ContentBlobType {
    /** `template_versions.template_model` (a `TemplateDocument`). */
    const val TEMPLATE_MODEL = "templateModel"

    /** `stencil_versions.content` (a `TemplateDocument`). */
    const val STENCIL_CONTENT = "stencilContent"

    /** `themes.document_styles`. */
    const val DOCUMENT_STYLES = "documentStyles"

    /** `themes.page_settings`. */
    const val PAGE_SETTINGS = "pageSettings"

    /** `themes.block_style_presets`. */
    const val BLOCK_STYLE_PRESETS = "blockStylePresets"

    /** `contract_versions.schema`. */
    const val CONTRACT_SCHEMA = "contractSchema"

    /** `contract_versions.data_model`. */
    const val DATA_MODEL = "dataModel"

    /** `contract_versions.data_examples` (a JSON array). */
    const val DATA_EXAMPLES = "dataExamples"
}

/**
 * One step in the catalog wire-format migration chain — upgrades a catalog
 * payload from wire schema version [from] to [to] (always `from + 1`), modelled
 * on an EF Core migration.
 *
 * Migrations run on the **untyped JSON tree before binding**: the typed protocol
 * model (`CatalogManifest`, `ResourceDetail`, `CatalogResource` …) lives in the
 * external `epistola-model` jar and only ever describes the *current* shape, so
 * an old-shaped payload cannot deserialize into it. A migration rewrites the
 * tree into the next version's shape; [CatalogSchemaMigrator] runs every step
 * from the payload's version up to the current one, then binds the result.
 *
 * A migration is a **pure function**: `JsonNode` in, `JsonNode` out — no DB, IO,
 * clock, or randomness. Both methods default to identity, so a step overrides
 * only the document(s) its version actually changed. To register a step, declare
 * it as a Spring `@Component`; the migrator collects all of them and validates
 * the chain is contiguous at startup.
 *
 * `release.fingerprint` / `release.version` must never be touched — they are the
 * source's content identity (and the fingerprint excludes `schemaVersion` by
 * construction). The migrator stamps `schemaVersion` to current after the chain
 * runs; migrations only reshape content.
 *
 * See `docs/adr/0006-catalog-wire-format-migrations.md`.
 */
interface CatalogSchemaMigration {
    /** The wire version this step upgrades *from*. */
    val from: Int

    /** The wire version this step produces. Always one greater than [from]. */
    val to: Int get() = from + 1

    /**
     * Upgrade the manifest (`catalog.json`) tree by exactly one version.
     * Default: identity (this step did not change the manifest shape).
     */
    fun migrateManifest(manifest: ObjectNode, ctx: MigrationContext): ObjectNode = manifest

    /**
     * Upgrade one resource-detail tree by exactly one version — the **wire/import**
     * path. [type] is the resource type (`"template"`, `"theme"`, `"stencil"`, …)
     * so the step can branch; [detail] is the whole `{schemaVersion, resource}`
     * tree. Default: identity (this step did not change this detail shape).
     *
     * A step that also wants to transform the same content **at rest** overrides
     * [migrateContentBlob] too (sharing the leaf transform), since the stored blobs
     * are the bare domain values, not a `ResourceDetail` envelope.
     */
    fun migrateResourceDetail(type: String, detail: ObjectNode, ctx: MigrationContext): ObjectNode = detail

    /**
     * Upgrade one **stored content blob** by exactly one version. [blobType] is a
     * [ContentBlobType] constant identifying the content shape (so a step can
     * branch — e.g. only touch [ContentBlobType.TEMPLATE_MODEL]). The node is
     * the bare domain blob (a `TemplateDocument`, theme-styles object, data-model
     * object, or — for [ContentBlobType.DATA_EXAMPLES] — a JSON array), **not** a
     * wire `ResourceDetail` envelope, so this returns [JsonNode] to allow arrays.
     * Default: identity.
     *
     * Driven by the at-rest [AtRestContentMigrator]; the per-row content version
     * lives in a `schema_version` column, never inside the blob, so a step must
     * not stamp a version. See `docs/adr/0007-at-rest-resource-migration.md`.
     */
    fun migrateContentBlob(blobType: String, blob: JsonNode, ctx: MigrationContext): JsonNode = blob
}
