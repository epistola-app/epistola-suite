// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.migrations

import app.epistola.catalog.migration.CatalogWireSchema
import app.epistola.suite.catalog.CatalogIdentityProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CatalogSchemaMigratorGateTest {
    private val migrator = CatalogSchemaMigrator()

    @Test
    fun `current manifest binds through portable migrator`() {
        val migrated = migrator.migrateAndBindManifest(manifest(CatalogWireSchema.CURRENT_VERSION), namespace = null)

        assertThat(migrated.manifest.catalog.key).isEqualTo("demo")
        assertThat(migrated.catalog.sourceVersion).isEqualTo(CatalogWireSchema.CURRENT_VERSION)
    }

    @Test
    fun `newer manifest retains Suite exception presentation`() {
        assertThatThrownBy { migrator.migrateAndBindManifest(manifest(CatalogWireSchema.CURRENT_VERSION + 1), namespace = null) }
            .isInstanceOf(CatalogSchemaTooNewException::class.java)
            .hasMessageContaining("newer than this instance supports")
    }

    @Test
    fun `older manifest is rejected by the single portable wire model`() {
        assertThatThrownBy { migrator.migrateAndBindManifest(manifest(3), namespace = null) }
            .isInstanceOf(CatalogSchemaTooOldException::class.java)
            .hasMessageContaining("predates the oldest supported")
    }

    @Test
    fun `malformed manifest retains Suite bad-input presentation`() {
        assertThatThrownBy { migrator.migrateAndBindManifest("not-json".toByteArray(), namespace = null) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
            .hasMessageContaining("Unrecognised catalog wire payload")
    }

    @Test
    fun `a manifest from before wire v8 lands in the given namespace on the default registry`() {
        val migrated = migrator.migrateAndBindManifest(manifest(7), namespace = "acme")

        assertThat(migrated.manifest.catalog.namespace).isEqualTo("acme")
        assertThat(migrated.manifest.catalog.registry).isEqualTo("exchange.epistola.app")
    }

    @Test
    fun `the default registry is configurable`() {
        val migrated = CatalogSchemaMigrator(CatalogIdentityProperties(defaultRegistry = "registry.gemeente-x.nl"))
            .migrateAndBindManifest(manifest(7), namespace = "acme")

        assertThat(migrated.manifest.catalog.registry).isEqualTo("registry.gemeente-x.nl")
    }

    @Test
    fun `a manifest from before wire v8 without a namespace is refused`() {
        assertThatThrownBy { migrator.migrateAndBindManifest(manifest(7), namespace = null) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
            .hasMessageContaining("namespace")
    }

    @Test
    fun `catalog migration golden fixture is published to consumers`() {
        assertThat(
            javaClass.getResource("/META-INF/epistola-catalog/fixtures/v1/wire-v4/catalog.json"),
        ).isNotNull()
    }

    private fun manifest(version: Int): ByteArray =
        """
        {
          "schemaVersion": $version,
          "catalog": ${if (version >= 8) "{\"key\": \"demo\", \"name\": \"Demo\", \"registry\": \"exchange.epistola.app\", \"namespace\": \"acme\"}" else "{\"slug\": \"demo\", \"name\": \"Demo\"}"},
          "publisher": {"name": "Test"},
          "release": {"version": "1.0.0"},
          "resources": []
        }
        """.trimIndent().toByteArray()
}
