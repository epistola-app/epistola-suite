// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.migrations

import tools.jackson.databind.node.ObjectNode

/**
 * One step in the catalog wire-format migration chain — upgrades a whole catalog
 * from wire schema version [from] to [to] (always `from + 1`), modelled on an EF
 * Core migration.
 *
 * The catalog has a **single, catalog-wide** version (see
 * `docs/adr/0007-catalog-wire-format-migrations.md`): one chain upgrades the
 * manifest and every resource detail together. A step overrides [migrateManifest]
 * to reshape the `catalog.json` tree and/or [migrateResourceDetail] to reshape a
 * resource-detail tree; both default to identity, so a step only touches what its
 * version actually changed.
 *
 * Migrations run on the **untyped JSON tree before binding**: the typed protocol
 * model (`CatalogManifest`, `ResourceDetail`, `CatalogResource` …) lives in the
 * external `epistola-model` jar and only ever describes the *current* shape, so
 * an old-shaped payload cannot deserialize into it. A migration rewrites the
 * tree into the next version's shape; the migrator binds the result.
 *
 * A migration is a **pure function**: `JsonNode` in, `JsonNode` out — no DB, IO,
 * clock, or randomness. To register a step, declare it as a Spring `@Component`;
 * the migrator collects all of them and validates the chain is contiguous at
 * startup.
 *
 * `release.fingerprint` / `release.version` must never be touched — they are the
 * source's content identity (and the fingerprint excludes `schemaVersion` by
 * construction). The migrator stamps `schemaVersion` to the current version after
 * the chain runs; migrations only reshape content.
 */
interface CatalogSchemaMigration {
    /** The version this step upgrades *from*. */
    val from: Int

    /** The version this step produces. Always one greater than [from]. */
    val to: Int get() = from + 1

    /** Upgrade the manifest (`catalog.json`) tree by exactly one version. */
    fun migrateManifest(node: ObjectNode, ctx: MigrationContext): ObjectNode = node

    /**
     * Upgrade one resource-detail tree by exactly one version. [type] is the
     * resource `type` discriminator ("template", "theme", …) so a step can branch
     * on the kind of resource it is reshaping.
     */
    fun migrateResourceDetail(type: String, node: ObjectNode, ctx: MigrationContext): ObjectNode = node
}
