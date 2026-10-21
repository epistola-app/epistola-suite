// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogMigrationConfirmationRequiredException
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A catalog ZIP from wire v7 or earlier names no registry or namespace. Importing it migrates it
 * into the importing tenant's namespace on the default registry (contract ADR 0003), after the
 * author confirms the migration of authored content.
 */
class V7ZipImportTest : IntegrationTestBase() {

    @Autowired
    private lateinit var identity: CatalogIdentityProperties

    @Test
    fun `a wire-v7 ZIP asks to confirm the migration, then lands in the tenant's namespace`() {
        val tenant = createTenant("V7 zip import")

        withMediator {
            assertThatThrownBy { ImportCatalogZip(tenant.id, v7Archive()).execute() }
                .isInstanceOf(CatalogMigrationConfirmationRequiredException::class.java)

            val result = ImportCatalogZip(tenant.id, v7Archive(), confirmMigration = true).execute()

            assertThat(result.results.map { it.status }).containsOnly(InstallStatus.INSTALLED)
            val catalog = GetCatalog(tenant.id, CatalogKey.of("letters")).query()!!
            assertThat(catalog.namespace).isEqualTo(CatalogIdentityProperties.defaultNamespace(tenant.id))
            assertThat(catalog.registry).isEqualTo(identity.defaultRegistry)
        }
    }

    /** A v7 catalog holding one theme, addressed by `slug` as v7 did. */
    private fun v7Archive(): ByteArray {
        val manifest = """
            {"schemaVersion":7,
             "catalog":{"slug":"letters","name":"Letters"},
             "publisher":{"name":"Test"},
             "release":{"version":"1.0.0"},
             "resources":[{"type":"theme","slug":"brand","name":"Brand","detailUrl":"./resources/theme/brand.json"}]}
        """.trimIndent()
        val theme = """
            {"schemaVersion":7,"resource":{"type":"theme","slug":"brand","name":"Brand","documentStyles":{}}}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, content) in listOf("catalog.json" to manifest, "resources/theme/brand.json" to theme)) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
