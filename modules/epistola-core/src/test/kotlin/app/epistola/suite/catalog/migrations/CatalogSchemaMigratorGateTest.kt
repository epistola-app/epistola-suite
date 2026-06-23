package app.epistola.suite.catalog.migrations

import app.epistola.catalog.protocol.AttributeResource
import app.epistola.suite.catalog.migrations.CatalogSchemaMigrator.Companion.migrate
import app.epistola.suite.catalog.migrations.steps.CatalogV3ToV4ExampleMigration
import app.epistola.suite.catalog.migrations.steps.CatalogV4ToV5Migration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * The catalog-wide wire-version gate in [CatalogSchemaMigrator]: which versions
 * pass, which are rejected, and that an in-window chain actually transforms +
 * re-stamps the tree. Pure unit test; exercises the companion gate directly so
 * the too-old / chain-execution branches are covered against an arbitrary
 * window, and the public bind entry points against the live chain ([3, 5]).
 */
class CatalogSchemaMigratorGateTest {

    private val mapper = jsonMapper { addModule(kotlinModule()) }

    /** The migrator wired with the live chain — catalog window is [3, 5], so both example steps are required. */
    private fun migrator() = CatalogSchemaMigrator(mapper, listOf(CatalogV3ToV4ExampleMigration(), CatalogV4ToV5Migration()))

    /** Runs a step's manifest transform — the gate behaves the same for details. */
    private val applyManifest: (CatalogSchemaMigration, ObjectNode, MigrationContext) -> ObjectNode =
        { step, node, ctx -> step.migrateManifest(node, ctx) }

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
        override fun migrateManifest(node: ObjectNode, ctx: MigrationContext): ObjectNode {
            val prior = node.get("trace")?.asString() ?: ""
            node.put("trace", "$prior$from-$to;")
            return node
        }
    }

    private fun chain(vararg froms: Int): Map<Int, CatalogSchemaMigration> = froms.associateWith { TraceMigration(it) }

    @Test
    fun `current version passes through unchanged`() {
        val tree = manifest("4")
        val result = migrate(tree, byFrom = emptyMap(), baseline = 4, current = 4, apply = applyManifest)
        assertThat(result.get("schemaVersion").asInt()).isEqualTo(4)
        assertThat(result.get("trace")).isNull()
    }

    @Test
    fun `newer than current is rejected as too new`() {
        assertThatThrownBy {
            migrate(manifest("5"), byFrom = emptyMap(), baseline = 4, current = 4, apply = applyManifest)
        }
            .isInstanceOf(CatalogSchemaTooNewException::class.java)
            .hasMessageContaining("newer")
    }

    @Test
    fun `older than current with no chain passes through (transitional)`() {
        // Phase-0 leniency: no migrations defined, so a sub-current payload is
        // assumed already current-shape and binds as-is.
        val result = migrate(manifest("2"), byFrom = emptyMap(), baseline = 4, current = 4, apply = applyManifest)
        assertThat(result.get("schemaVersion").asInt()).isEqualTo(2)
    }

    @Test
    fun `older than baseline with a chain present is rejected as too old`() {
        // baseline 4, current 6, but the payload is v3 — below the floor.
        assertThatThrownBy {
            migrate(manifest("3"), byFrom = chain(4, 5), baseline = 4, current = 6, apply = applyManifest)
        }
            .isInstanceOf(CatalogSchemaTooOldException::class.java)
            .hasMessageContaining("predates")
    }

    @Test
    fun `an in-window payload runs every step in order and re-stamps to current`() {
        val result = migrate(manifest("1"), byFrom = chain(1, 2, 3), baseline = 1, current = 4, apply = applyManifest)
        assertThat(result.get("schemaVersion").asInt()).isEqualTo(4)
        assertThat(result.get("trace").asString()).isEqualTo("1-2;2-3;3-4;")
    }

    @Test
    fun `a payload entering mid-window runs only the remaining steps`() {
        val result = migrate(manifest("3"), byFrom = chain(1, 2, 3), baseline = 1, current = 4, apply = applyManifest)
        assertThat(result.get("schemaVersion").asInt()).isEqualTo(4)
        assertThat(result.get("trace").asString()).isEqualTo("3-4;")
    }

    @Test
    fun `missing schemaVersion is rejected as unknown`() {
        val noVersion = mapper.readTree("""{ "catalog": { "slug": "x", "name": "X" } }""") as ObjectNode
        assertThatThrownBy { migrate(noVersion, emptyMap(), 4, 4, apply = applyManifest) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
            .hasMessageContaining("missing")
    }

    @Test
    fun `non-integer schemaVersion is rejected as unknown`() {
        assertThatThrownBy { migrate(manifest("\"four\""), emptyMap(), 4, 4, apply = applyManifest) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
        assertThatThrownBy { migrate(manifest("4.5"), emptyMap(), 4, 4, apply = applyManifest) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
    }

    @Test
    fun `migrateAndBindManifest binds a current-version manifest end to end`() {
        val bound = migrator().migrateAndBindManifest(mapper.writeValueAsBytes(manifest("5")))
        assertThat(bound.manifest.catalog.slug).isEqualTo("x")
        assertThat(bound.manifest.release.version).isEqualTo("1.0.0")
        assertThat(bound.catalog.sourceVersion).isEqualTo(5)
    }

    @Test
    fun `migrateAndBindManifest rejects a too-new payload before binding`() {
        assertThatThrownBy { migrator().migrateAndBindManifest(mapper.writeValueAsBytes(manifest("9"))) }
            .isInstanceOf(CatalogSchemaTooNewException::class.java)
    }

    private fun attributeDetail(schemaVersion: String, type: String = "attribute"): ByteArray = mapper.writeValueAsBytes(
        mapper.readTree(
            """
            {
              "schemaVersion": $schemaVersion,
              "resource": { "type": "$type", "slug": "country", "name": "Country" }
            }
            """.trimIndent(),
        ),
    )

    /** A catalog context whose manifest is at [version] (any tree suffices for the gate). */
    private fun ctx(version: Int) = CatalogMigrationContext(sourceVersion = version, migratedManifest = mapper.createObjectNode())

    @Test
    fun `migrateAndBindResourceDetail binds a current-version detail whose type matches`() {
        val bound = migrator().migrateAndBindResourceDetail("attribute", attributeDetail("5"), ctx(5))
        assertThat(bound.resource).isInstanceOf(AttributeResource::class.java)
    }

    @Test
    fun `migrateAndBindResourceDetail rejects a detail whose own type contradicts the manifest entry`() {
        // Manifest entry says "theme", but the detail's own discriminator is "attribute".
        assertThatThrownBy { migrator().migrateAndBindResourceDetail("theme", attributeDetail("5"), ctx(5)) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
            .hasMessageContaining("declares type 'attribute'")
    }

    @Test
    fun `migrateAndBindResourceDetail rejects a detail whose version differs from the catalog manifest`() {
        // Catalog (manifest) is at version 5, but the detail is stamped 3 — a
        // catalog is one bundle at one wire version, so this must be rejected.
        assertThatThrownBy { migrator().migrateAndBindResourceDetail("attribute", attributeDetail("3"), ctx(5)) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
            .hasMessageContaining("every part of a catalog must carry the same wire version")
    }

    @Test
    fun `a resource-detail step receives the migrated manifest via the context`() {
        // A cross-part detail step can read catalog-level data through
        // MigrationContext.manifest. Exercised on the pure companion gate with a
        // one-step chain so the manifest is threaded into the context.
        var seen: ObjectNode? = null
        val step = object : CatalogSchemaMigration {
            override val from = 3
            override fun migrateResourceDetail(type: String, node: ObjectNode, ctx: MigrationContext): ObjectNode {
                seen = ctx.manifest
                return node
            }
        }
        val theManifest = manifest("4")
        migrate(manifest("3"), byFrom = mapOf(3 to step), baseline = 3, current = 4, manifest = theManifest) { s, node, c ->
            s.migrateResourceDetail("template", node, c)
        }
        assertThat(seen).isSameAs(theManifest)
    }

    @Test
    fun `invalid JSON is rejected as schema-unknown, not a raw Jackson error`() {
        val migrator = migrator()
        val garbage = "{ not json".toByteArray()
        assertThatThrownBy { migrator.migrateAndBindManifest(garbage) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
        assertThatThrownBy { migrator.migrateAndBindResourceDetail("attribute", garbage, ctx(5)) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
    }

    @Test
    fun `a non-object JSON payload is rejected as schema-unknown`() {
        assertThatThrownBy { migrator().migrateAndBindManifest("[]".toByteArray()) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
            .hasMessageContaining("not a JSON object")
    }

    @Test
    fun `migrateAndBindManifest maps a valid-version but unbindable manifest to schema-unknown`() {
        // Passes the gate (schemaVersion is current) but the body is missing every
        // required field, so binding fails — must surface as schema-unknown (400),
        // not escape as a raw Jackson bind error (500).
        assertThatThrownBy { migrator().migrateAndBindManifest("""{ "schemaVersion": 5 }""".toByteArray()) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
            .hasMessageContaining("does not bind")
    }

    @Test
    fun `migrateAndBindResourceDetail maps a valid-version but unbindable detail to schema-unknown`() {
        // Valid, catalog-matching schemaVersion but no `resource` to bind.
        assertThatThrownBy {
            migrator().migrateAndBindResourceDetail("attribute", """{ "schemaVersion": 5 }""".toByteArray(), ctx(5))
        }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
            .hasMessageContaining("does not bind")
    }

    @Test
    fun `a v3 manifest migrates through the chain to current and binds`() {
        val v3 = mapper.writeValueAsBytes(
            mapper.readTree(
                """
                {
                  "schemaVersion": 3,
                  "catalog": { "slug": "acme", "name": "Acme Templates" },
                  "publisher": { "name": "P" },
                  "release": { "version": "1.0.0" },
                  "resources": []
                }
                """.trimIndent(),
            ),
        )
        val bound = migrator().migrateAndBindManifest(v3)
        assertThat(bound.manifest.catalog.slug).isEqualTo("acme")
        assertThat(bound.manifest.catalog.name).isEqualTo("Acme Templates")
        assertThat(bound.catalog.sourceVersion).isEqualTo(3)
    }

    @Test
    fun `a v3 non-template detail migrates through the chain to current and binds`() {
        // The example migrations only transform templates, so a non-template
        // detail just passes through the chain and is re-stamped + bound.
        val bound = migrator().migrateAndBindResourceDetail("attribute", attributeDetail("3"), ctx(3))
        assertThat(bound.resource).isInstanceOf(AttributeResource::class.java)
        assertThat((bound.resource as AttributeResource).name).isEqualTo("Country")
    }
}
