package app.epistola.suite.catalog.migrations.steps

import app.epistola.suite.catalog.migrations.CatalogSchemaMigration
import app.epistola.suite.catalog.migrations.MigrationContext
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * **Example** catalog wire-format migration: `v3 → v4`.
 *
 * This is the reference for writing a real migration. It upgrades a whole catalog
 * one version: imagine `v3` named things with legacy fields (`title` on the
 * catalog, `displayName` on each resource) and `v4` standardised on `name`. The
 * migration renames them so an old `v3` export binds to the current typed model.
 *
 * - manifest: `catalog.title` → `catalog.name`
 * - each resource detail: `resource.displayName` → `resource.name`
 *
 * Both renames are **defensive and idempotent** — they only fire when the legacy
 * field is present and the new field is absent, so running on a current-shape
 * (`v4`) tree, or twice, is a no-op. The migrator stamps `schemaVersion` to the
 * current version after the chain runs; this step only reshapes content and never
 * touches `release.fingerprint`/`release.version`.
 *
 * To add your own migration: copy this shape — declare `from`, override
 * [migrateManifest] and/or [migrateResourceDetail], register as a `@Component`,
 * and bump `CATALOG_SCHEMA_VERSION` (and, if you drop support for the oldest
 * shape, `CATALOG_BASELINE_SCHEMA_VERSION`).
 */
@Component
class CatalogV3ToV4ExampleMigration : CatalogSchemaMigration {
    override val from: Int = 3

    override fun migrateManifest(node: ObjectNode, ctx: MigrationContext): ObjectNode {
        (node.get("catalog") as? ObjectNode)?.let { renameField(it, legacy = "title", current = "name") }
        return node
    }

    override fun migrateResourceDetail(type: String, node: ObjectNode, ctx: MigrationContext): ObjectNode {
        (node.get("resource") as? ObjectNode)?.let { renameField(it, legacy = "displayName", current = "name") }
        return node
    }

    private fun renameField(obj: ObjectNode, legacy: String, current: String) {
        if (obj.has(legacy) && !obj.has(current)) {
            obj.set(current, obj.get(legacy))
            obj.remove(legacy)
        }
    }
}
