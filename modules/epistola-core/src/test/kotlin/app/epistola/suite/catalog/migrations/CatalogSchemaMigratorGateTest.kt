package app.epistola.suite.catalog.migrations

import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator.Companion.migrateManifestTree
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * The wire-version gate in [CatalogSchemaMigrator]: which versions pass, which
 * are rejected, and that an in-window chain actually transforms + re-stamps the
 * tree. Pure unit test; exercises the companion gate directly so the too-old /
 * chain-execution branches (not reachable with the live empty chain) are still
 * covered ahead of Phase 1.
 */
class CatalogSchemaMigratorGateTest {

    private val mapper = jsonMapper { addModule(kotlinModule()) }

    private fun manifest(schemaVersion: String): ObjectNode = mapper.readTree(
        """
        {
          "schemaVersion": $schemaVersion,
          "catalog": { "slug": "x", "name": "X" },
          "publisher": { "name": "P" },
          "release": { "version": "1.0.0" },
          "resources": []
        }
        """.trimIndent(),
    ) as ObjectNode

    /** Marks the tree as it passes through, so we can assert which steps ran. */
    private class TraceMigration(override val from: Int) : CatalogSchemaMigration {
        override fun migrateManifest(manifest: ObjectNode, ctx: MigrationContext): ObjectNode {
            val prior = manifest.get("trace")?.asString() ?: ""
            manifest.put("trace", "$prior$from-$to;")
            return manifest
        }
    }

    private fun chain(vararg froms: Int): Map<Int, CatalogSchemaMigration> = froms.associateWith { TraceMigration(it) }

    @Test
    fun `current version passes through unchanged`() {
        val tree = manifest("4")
        val result = migrateManifestTree(tree, byFrom = emptyMap(), baseline = 4, current = 4)
        assertThat(result.get("schemaVersion").asInt()).isEqualTo(4)
        assertThat(result.get("trace")).isNull()
    }

    @Test
    fun `newer than current is rejected as too new`() {
        assertThatThrownBy {
            migrateManifestTree(manifest("5"), byFrom = emptyMap(), baseline = 4, current = 4)
        }
            .isInstanceOf(CatalogSchemaTooNewException::class.java)
            .hasMessageContaining("newer")
    }

    @Test
    fun `older than current with no chain passes through (transitional)`() {
        // Phase-0 leniency: no migrations defined, so a sub-current payload is
        // assumed already current-shape and binds as-is.
        val result = migrateManifestTree(manifest("2"), byFrom = emptyMap(), baseline = 4, current = 4)
        assertThat(result.get("schemaVersion").asInt()).isEqualTo(2)
    }

    @Test
    fun `older than baseline with a chain present is rejected as too old`() {
        // baseline 4, current 6, but the payload is v3 — below the floor.
        assertThatThrownBy {
            migrateManifestTree(manifest("3"), byFrom = chain(4, 5), baseline = 4, current = 6)
        }
            .isInstanceOf(CatalogSchemaTooOldException::class.java)
            .hasMessageContaining("predates")
    }

    @Test
    fun `an in-window payload runs every step in order and re-stamps to current`() {
        val result = migrateManifestTree(manifest("1"), byFrom = chain(1, 2, 3), baseline = 1, current = 4)
        assertThat(result.get("schemaVersion").asInt()).isEqualTo(4)
        assertThat(result.get("trace").asString()).isEqualTo("1-2;2-3;3-4;")
    }

    @Test
    fun `a payload entering mid-window runs only the remaining steps`() {
        val result = migrateManifestTree(manifest("3"), byFrom = chain(1, 2, 3), baseline = 1, current = 4)
        assertThat(result.get("schemaVersion").asInt()).isEqualTo(4)
        assertThat(result.get("trace").asString()).isEqualTo("3-4;")
    }

    @Test
    fun `missing schemaVersion is rejected as unknown`() {
        val noVersion = mapper.readTree("""{ "catalog": { "slug": "x", "name": "X" } }""") as ObjectNode
        assertThatThrownBy { migrateManifestTree(noVersion, emptyMap(), 4, 4) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
            .hasMessageContaining("missing")
    }

    @Test
    fun `non-integer schemaVersion is rejected as unknown`() {
        assertThatThrownBy { migrateManifestTree(manifest("\"four\""), emptyMap(), 4, 4) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
        assertThatThrownBy { migrateManifestTree(manifest("4.5"), emptyMap(), 4, 4) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
    }

    @Test
    fun `migrateAndBindManifest binds a current-version manifest end to end`() {
        val migrator = CatalogSchemaMigrator(mapper, emptyList(), current = 4, baseline = 4)
        val bound = migrator.migrateAndBindManifest(
            mapper.writeValueAsBytes(manifest("4")),
        )
        assertThat(bound.sourceVersion).isEqualTo(4)
        assertThat(bound.manifest.catalog.slug).isEqualTo("x")
        assertThat(bound.manifest.release.version).isEqualTo("1.0.0")
    }

    @Test
    fun `migrateAndBindManifest rejects a too-new payload before binding`() {
        val migrator = CatalogSchemaMigrator(mapper, emptyList(), current = 4, baseline = 4)
        assertThatThrownBy { migrator.migrateAndBindManifest(mapper.writeValueAsBytes(manifest("9"))) }
            .isInstanceOf(CatalogSchemaTooNewException::class.java)
    }
}
