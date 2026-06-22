package app.epistola.suite.catalog.migrations

import org.springframework.stereotype.Component

/**
 * Catalog wire schema **v2 → v3**.
 *
 * This boundary was **purely additive** (epistola-model 0.4.0): v3 introduced
 * `CodeListResource`, `DependencyRef.CodeList`, and the optional
 * `AttributeResource.codeListBinding`. Nothing was renamed, restructured, or made
 * required — a v2 attribute keeps its inline `allowedValues` (still a valid v3
 * shape) and simply lacks `codeListBinding`, which binds as `null`. So every
 * method here is **identity**: the step exists only to extend the supported
 * baseline down to 2, letting a v2 catalog import (it then flows on through the
 * `3 → 4` step). Converting inline `allowedValues` into a `codeListBinding` would
 * be data loss, not a migration.
 *
 * See `docs/adr/0006-catalog-wire-format-migrations.md`.
 */
@Component
class CatalogSchemaMigrationV2ToV3 : CatalogSchemaMigration {
    override val from = 2
}
