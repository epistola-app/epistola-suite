package app.epistola.suite.catalog.migrations

import app.epistola.suite.catalog.CatalogPart
import tools.jackson.databind.node.ObjectNode

/**
 * One step in **one part's** catalog wire-format migration chain — upgrades that
 * part's JSON tree from wire schema version [from] to [to] (always `from + 1`),
 * modelled on an EF Core migration.
 *
 * Each part (the manifest, or a resource type) is versioned independently and
 * has its own chain (per-part versioning — see
 * `docs/adr/0007-catalog-wire-format-migrations.md`). A step declares which
 * [part] it migrates; [CatalogSchemaMigrator] groups steps by part and runs each
 * part's chain from the payload's version up to that part's current.
 *
 * Migrations run on the **untyped JSON tree before binding**: the typed protocol
 * model (`CatalogManifest`, `ResourceDetail`, `CatalogResource` …) lives in the
 * external `epistola-model` jar and only ever describes the *current* shape, so
 * an old-shaped payload cannot deserialize into it. A migration rewrites the
 * tree into the next version's shape; the migrator binds the result.
 *
 * A migration is a **pure function**: `JsonNode` in, `JsonNode` out — no DB, IO,
 * clock, or randomness. To register a step, declare it as a Spring `@Component`;
 * the migrator collects all of them and validates each part's chain is
 * contiguous at startup.
 *
 * For a cross-part change (e.g. lift a field out of every detail into the
 * manifest), ship a step in each affected part's chain.
 *
 * `release.fingerprint` / `release.version` must never be touched — they are the
 * source's content identity (and the fingerprint excludes `schemaVersion` by
 * construction). The migrator stamps `schemaVersion` to the part's current after
 * the chain runs; migrations only reshape content.
 */
interface CatalogSchemaMigration {
    /** The wire-format part this step migrates (the manifest, or one resource type). */
    val part: CatalogPart

    /** The version this step upgrades *from* (within [part]'s own version line). */
    val from: Int

    /** The version this step produces. Always one greater than [from]. */
    val to: Int get() = from + 1

    /**
     * Upgrade this part's tree by exactly one version — the manifest tree for
     * [CatalogPart.MANIFEST], or one resource-detail tree otherwise.
     */
    fun migrate(node: ObjectNode, ctx: MigrationContext): ObjectNode
}
