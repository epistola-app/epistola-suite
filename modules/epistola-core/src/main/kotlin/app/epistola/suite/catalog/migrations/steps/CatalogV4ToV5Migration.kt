package app.epistola.suite.catalog.migrations.steps

import app.epistola.suite.catalog.migrations.CatalogSchemaMigration
import org.springframework.stereotype.Component

/**
 * Catalog wire-format migration: `v4 → v5`.
 *
 * The simplest possible migration — a **pure version bump** with no content
 * change. It overrides neither [migrateManifest] nor [migrateResourceDetail], so
 * both keep their identity defaults; the migrator just re-stamps `schemaVersion`
 * from `4` to `5` after the (empty) transform runs. Use this shape when a wire
 * version increments without any shape change (e.g. to draw a line in the sand
 * after a round-trip-compatible tweak).
 */
@Component
class CatalogV4ToV5Migration : CatalogSchemaMigration {
    override val from: Int = 4
}
