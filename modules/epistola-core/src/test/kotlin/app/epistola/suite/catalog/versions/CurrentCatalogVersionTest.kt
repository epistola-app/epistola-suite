// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.catalog.versionNamed
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

private const val FIXTURE_CATALOG_URL = "classpath:epistola/catalogs/fixture/catalog.json"

/**
 * `catalog_current_versions` and the helpers over it name the version a caller means: the current one
 * when it names none (`versionAt`), the numbered one when it does (`versionNamed`).
 */
class CurrentCatalogVersionTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private val letters = CatalogKey.of("letters")
    private val installed = CatalogKey.of("epistola-demo")

    private fun versionRow(tenantKey: TenantKey, catalogKey: CatalogKey): Pair<UUID, String> = jdbi.withHandle<Pair<UUID, String>, Exception> { handle ->
        handle.createQuery(
            "SELECT v.version_id, v.version FROM catalog_versions v JOIN catalogs c ON c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id " +
                "WHERE c.tenant_key = :t AND c.catalog_key = :c",
        )
            .bind("t", tenantKey).bind("c", catalogKey)
            .map { rs, _ -> rs.getObject("version_id", UUID::class.java) to rs.getString("version") }
            .one()
    }

    private fun currentInView(tenantKey: TenantKey, catalogKey: CatalogKey): UUID? = jdbi.withHandle<UUID?, Exception> { handle ->
        handle.createQuery(
            "SELECT cv.version_id FROM catalog_current_versions cv JOIN catalogs c ON c.tenant_key = cv.tenant_key AND c.catalog_id = cv.catalog_id " +
                "WHERE c.tenant_key = :t AND c.catalog_key = :c",
        )
            .bind("t", tenantKey).bind("c", catalogKey)
            .mapTo(UUID::class.java)
            .findOne().orElse(null)
    }

    private fun versionAtOf(tenantKey: TenantKey, catalogKey: CatalogKey): UUID? = jdbi.withHandle<UUID?, Exception> { handle ->
        handle.createQuery("SELECT ${versionAt("t", "c")}")
            .bind("t", tenantKey).bind("c", catalogKey)
            .mapTo(UUID::class.java).findOne().orElse(null)
    }

    private fun versionNamedOf(tenantKey: TenantKey, catalogKey: CatalogKey, version: String): UUID? = jdbi.withHandle<UUID?, Exception> { handle ->
        handle.createQuery("SELECT ${versionNamed("t", "c", "v")}")
            .bind("t", tenantKey).bind("c", catalogKey).bind("v", version)
            .mapTo(UUID::class.java).findOne().orElse(null)
    }

    @Test
    fun `an authored catalog's current version is its open draft`() {
        val tenantKey = createTenant("Current authored").id
        withMediator { CreateCatalog(tenantKey, letters, "Letters").execute() }
        val (draft, number) = versionRow(tenantKey, letters)

        assertThat(currentInView(tenantKey, letters)).isEqualTo(draft)
        assertThat(versionAtOf(tenantKey, letters)).isEqualTo(draft)
        assertThat(versionNamedOf(tenantKey, letters, number)).isEqualTo(draft)
    }

    @Test
    fun `a subscribed catalog's current version is its installed release`() {
        val tenantKey = createTenant("Current subscribed").id
        withMediator {
            RegisterCatalog(tenantKey = tenantKey, sourceUrl = FIXTURE_CATALOG_URL, authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = tenantKey, catalogKey = installed).execute()
        }
        val (installedRow, number) = jdbi.withHandle<Pair<UUID, String>, Exception> { handle ->
            handle.createQuery(
                "SELECT v.version_id, v.version FROM catalog_versions v JOIN catalogs c ON c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id " +
                    "WHERE c.tenant_key = :t AND c.catalog_key = :c AND v.kind = 'installed'",
            )
                .bind("t", tenantKey).bind("c", installed)
                .map { rs, _ -> rs.getObject("version_id", UUID::class.java) to rs.getString("version") }
                .one()
        }

        assertThat(currentInView(tenantKey, installed)).isEqualTo(installedRow)
        assertThat(versionAtOf(tenantKey, installed)).isEqualTo(installedRow)
        assertThat(versionNamedOf(tenantKey, installed, number)).isEqualTo(installedRow)
    }

    @Test
    fun `a version that is not there is null`() {
        val tenantKey = createTenant("Current missing").id
        withMediator { CreateCatalog(tenantKey, letters, "Letters").execute() }

        assertThat(versionAtOf(tenantKey, CatalogKey.of("nowhere"))).isNull()
        assertThat(versionNamedOf(tenantKey, letters, "9.9.9")).isNull()
    }
}
