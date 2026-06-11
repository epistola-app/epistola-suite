package app.epistola.suite.catalog.migrations

import tools.jackson.databind.node.ObjectNode

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
     * Upgrade one resource-detail tree by exactly one version. [type] is the
     * resource type (`"template"`, `"theme"`, `"stencil"`, …) so the step can
     * branch. Default: identity (this step did not change this detail shape).
     *
     * Not yet invoked at runtime — detail-path wiring lands with the first real
     * migration (see [CatalogSchemaMigrator]).
     */
    fun migrateResourceDetail(type: String, detail: ObjectNode, ctx: MigrationContext): ObjectNode = detail
}
