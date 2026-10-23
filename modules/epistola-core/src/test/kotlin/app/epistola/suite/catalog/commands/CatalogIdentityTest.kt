// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.commands.ChangeCatalogNamespace
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.exportReleased
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** A catalog is named by registry, namespace and key (contract ADR 0003). */
class CatalogIdentityTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `an authored catalog is named in the namespace its tenant holds, the system catalog by Epistola`() {
        val tenant = createTenant("Identity Defaults")

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = CatalogKey.of("letters"), name = "Letters").execute()

            val letters = GetCatalog(tenant.id, CatalogKey.of("letters")).query()!!
            val default = GetCatalog(tenant.id, CatalogKey.DEFAULT).query()!!
            val system = GetCatalog(tenant.id, SYSTEM_CATALOG_KEY).query()!!

            assertThat(listOf(letters, default).map { it.registry to it.namespace })
                .containsOnly("exchange.epistola.app" to tenant.id.value)
            assertThat(system.registry to system.namespace).isEqualTo("exchange.epistola.app" to "epistola")
        }
    }

    @Test
    fun `a catalog can be created in a namespace of its own`() {
        val tenant = createTenant("Identity Explicit")

        withMediator {
            TestNamespaces.grant(tenant.id, "gemeente-x").execute()
            val catalog = CreateCatalog(tenantKey = tenant.id, id = CatalogKey.of("letters"), name = "Letters", namespace = "gemeente-x").execute()

            assertThat(catalog.namespace).isEqualTo("gemeente-x")
        }
    }

    @Test
    fun `changing the namespace is one command, and a release names the catalog by it`() {
        val tenant = createTenant("Identity Change")
        val key = CatalogKey.of("letters")

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Letters").execute()
            TestNamespaces.grant(tenant.id, "gemeente-x").execute()

            val changed = ChangeCatalogNamespace(tenant.id, key, "gemeente-x").execute()

            assertThat(changed.namespace).isEqualTo("gemeente-x")
            assertThat(GetCatalog(tenant.id, key).query()!!.namespace).isEqualTo("gemeente-x")
            val manifest = objectMapper.readTree(catalogJson(mediator.exportReleased(app.epistola.suite.common.ids.CatalogId(key, app.epistola.suite.common.ids.TenantId(tenant.id))).zipBytes))
            assertThat(manifest["catalog"]["namespace"].asString()).isEqualTo("gemeente-x")
            assertThat(manifest["catalog"]["registry"].asString()).isEqualTo("exchange.epistola.app")
        }
    }

    @Test
    fun `a namespace that is not a lowercase slug is refused`() {
        val tenant = createTenant("Identity Invalid")

        assertThatThrownBy { ChangeCatalogNamespace(tenant.id, CatalogKey.DEFAULT, "Gemeente X") }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { ChangeCatalogNamespace(tenant.id, CatalogKey.DEFAULT, "gx") }
            .isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `a subscribed catalog is named by its publisher and cannot be renamed here`() {
        val tenant = createTenant("Identity Subscribed")

        withMediator {
            assertThat(GetCatalog(tenant.id, SYSTEM_CATALOG_KEY).query()!!.suppliedByEpistola).isTrue()
            assertThatThrownBy { ChangeCatalogNamespace(tenant.id, SYSTEM_CATALOG_KEY, "mine").execute() }
                .isInstanceOf(CatalogReadOnlyException::class.java)
        }
    }

    @Test
    fun `once published, a catalog's namespace is fixed`() {
        val tenant = createTenant("Identity Published")
        val key = CatalogKey.of("letters")

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = key, name = "Letters").execute()
            // Only the Exchange publication worker marks a binding published, after Exchange accepted
            // a release; no command produces that state, so it is seeded directly.
            jdbi.useHandle<Exception> { handle ->
                handle.createUpdate(
                    """
                    INSERT INTO catalog_exchange_bindings (tenant_key, catalog_key, namespace, published_at)
                    VALUES (:t, :c, :ns, NOW())
                    """,
                ).bind("t", tenant.id).bind("c", key).bind("ns", tenant.id.value).execute()
            }

            assertThatThrownBy { ChangeCatalogNamespace(tenant.id, key, "gemeente-x").execute() }
                .isInstanceOf(CatalogNamespaceFixedException::class.java)
                .hasMessageContaining("exchange.epistola.app/${tenant.id.value}/letters")
            assertThat(GetCatalog(tenant.id, key).query()!!.namespace).isEqualTo(tenant.id.value)
        }
    }

    private fun catalogJson(zip: ByteArray): String = ZipInputStream(ByteArrayInputStream(zip)).use { zipStream ->
        generateSequence { zipStream.nextEntry }.first { it.name == "catalog.json" }
        zipStream.readAllBytes().decodeToString()
    }
}
