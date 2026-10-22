// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.TenantAccessDeniedException
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** A catalog is addressed by its full name: registry (`~` for the default), namespace and key. */
class ResolveCatalogTest : IntegrationTestBase() {

    @Autowired
    private lateinit var identity: CatalogIdentityProperties

    private val letters = CatalogKey.of("letters")

    @Test
    fun `a catalog resolves by its name, the default registry by tilde, null or in full`() {
        val tenant = withMediator { createTenant("resolve-catalog").id }
        withMediator { CreateCatalog(tenant, letters, "Letters", namespace = "acme-letters").execute() }

        for (registry in listOf("~", null, identity.defaultRegistry)) {
            val catalog = withMediator { ResolveCatalog(tenant, registry, "acme-letters", letters).query() }
            assertThat(catalog.id).isEqualTo(letters)
            assertThat(catalog.registry).isEqualTo(identity.defaultRegistry)
        }
    }

    @Test
    fun `a catalog with the same key under another name is not found`() {
        val tenant = withMediator { createTenant("resolve-catalog-other").id }
        withMediator { CreateCatalog(tenant, letters, "Letters", namespace = "acme-letters").execute() }

        assertThatThrownBy { withMediator { ResolveCatalog(tenant, "~", "elsewhere", letters).query() } }
            .isInstanceOf(CatalogNotFoundException::class.java)
        assertThatThrownBy { withMediator { ResolveCatalog(tenant, "other.example.com", "acme-letters", letters).query() } }
            .isInstanceOf(CatalogNotFoundException::class.java)
        assertThatThrownBy { withMediator { ResolveCatalog(tenant, "~", "acme-letters", CatalogKey.of("missing")).query() } }
            .isInstanceOf(CatalogNotFoundException::class.java)
    }

    @Test
    fun `a member who may only generate documents resolves the catalog it names, and no other tenant's`() {
        val tenant = withMediator { createTenant("resolve-catalog-generator").id }
        val other = withMediator { createTenant("resolve-catalog-stranger").id }
        withMediator { CreateCatalog(tenant, letters, "Letters", namespace = "acme-letters").execute() }
        val generator = EpistolaPrincipal(
            userId = UserKey.of("00000000-0000-0000-0000-0000000d0c01"),
            externalId = "resolve-catalog-generator",
            email = "resolve-catalog-generator@example.com",
            displayName = "Generator",
            tenantMemberships = mapOf(tenant to setOf(TenantRole.DOCUMENT_GENERATOR)),
            globalRoles = emptySet(),
            platformRoles = emptySet(),
            currentTenantId = tenant,
        )

        assertThat(runAs(generator) { ResolveCatalog(tenant, "~", "acme-letters", letters).query() }.id).isEqualTo(letters)
        assertThatThrownBy { runAs(generator) { ResolveCatalog(other, "~", "acme-letters", letters).query() } }
            .isInstanceOf(TenantAccessDeniedException::class.java)
    }
}
