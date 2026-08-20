// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.migrations

import app.epistola.catalog.migration.CatalogWireSchema
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CatalogSchemaMigratorGateTest {
    private val migrator = CatalogSchemaMigrator()

    @Test
    fun `current manifest binds through portable migrator`() {
        val migrated = migrator.migrateAndBindManifest(manifest(CatalogWireSchema.CURRENT_VERSION))

        assertThat(migrated.manifest.catalog.slug).isEqualTo("demo")
        assertThat(migrated.catalog.sourceVersion).isEqualTo(CatalogWireSchema.CURRENT_VERSION)
    }

    @Test
    fun `newer manifest retains Suite exception presentation`() {
        assertThatThrownBy { migrator.migrateAndBindManifest(manifest(CatalogWireSchema.CURRENT_VERSION + 1)) }
            .isInstanceOf(CatalogSchemaTooNewException::class.java)
            .hasMessageContaining("newer than this instance supports")
    }

    @Test
    fun `older manifest is rejected by the single portable wire model`() {
        assertThatThrownBy { migrator.migrateAndBindManifest(manifest(3)) }
            .isInstanceOf(CatalogSchemaTooOldException::class.java)
            .hasMessageContaining("predates the oldest supported")
    }

    @Test
    fun `malformed manifest retains Suite bad-input presentation`() {
        assertThatThrownBy { migrator.migrateAndBindManifest("not-json".toByteArray()) }
            .isInstanceOf(CatalogSchemaUnknownException::class.java)
            .hasMessageContaining("Unrecognised catalog wire payload")
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
          "catalog": {"slug": "demo", "name": "Demo"},
          "publisher": {"name": "Test"},
          "release": {"version": "1.0.0"},
          "resources": []
        }
        """.trimIndent().toByteArray()
}
