package app.epistola.suite.catalog.migrations

import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator.Companion.migrateResourceDetailTree
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper

/**
 * Unit checks for the v3 → v4 step: inject a default `version` into stencil
 * resource details that predate the required field, leave everything else alone.
 * Pure; no Spring, no DB, no binding.
 */
class CatalogSchemaMigrationV3ToV4Test {

    private val mapper = jsonMapper()
    private val step = CatalogSchemaMigrationV3ToV4()
    private val ctx = MigrationContext(sourceVersion = 3, targetVersion = 4, manifest = null)

    private fun detail(json: String): ObjectNode = mapper.readTree(json) as ObjectNode

    @Test
    fun `advances v3 to v4`() {
        assertThat(step.from).isEqualTo(3)
        assertThat(step.to).isEqualTo(4)
    }

    @Test
    fun `injects version 1 into a stencil detail lacking version`() {
        val out = step.migrateResourceDetail("stencil", detail("""{"schemaVersion":3,"resource":{"type":"stencil","slug":"s","name":"S"}}"""), ctx)
        assertThat(out.path("resource").path("version").asInt()).isEqualTo(1)
    }

    @Test
    fun `leaves an existing stencil version untouched`() {
        val out = step.migrateResourceDetail("stencil", detail("""{"schemaVersion":3,"resource":{"type":"stencil","slug":"s","name":"S","version":7}}"""), ctx)
        assertThat(out.path("resource").path("version").asInt()).isEqualTo(7)
    }

    @Test
    fun `leaves non-stencil details untouched`() {
        val out = step.migrateResourceDetail("theme", detail("""{"schemaVersion":3,"resource":{"type":"theme","slug":"t","name":"T"}}"""), ctx)
        assertThat(out.path("resource").has("version")).isFalse()
    }

    @Test
    fun `the chain injects the version and re-stamps schemaVersion to current`() {
        val byFrom = mapOf<Int, CatalogSchemaMigration>(3 to CatalogSchemaMigrationV3ToV4())
        val out = migrateResourceDetailTree(
            detail("""{"schemaVersion":3,"resource":{"type":"stencil","slug":"s","name":"S"}}"""),
            sourceVersion = 3,
            byFrom = byFrom,
            baseline = 3,
            current = 4,
        )
        assertThat(out.path("resource").path("version").asInt()).isEqualTo(1)
        assertThat(out.path("schemaVersion").asInt()).isEqualTo(4)
    }
}
