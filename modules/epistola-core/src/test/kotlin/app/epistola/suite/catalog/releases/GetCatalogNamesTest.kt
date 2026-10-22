// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogName
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogKey
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

/** A response that refers to a catalog by key names it in full, for any member of its tenant. */
class GetCatalogNamesTest : IntegrationTestBase() {

    @Autowired
    private lateinit var identity: CatalogIdentityProperties

    private val letters = CatalogKey.of("letters")
    private val forms = CatalogKey.of("forms")

    @Test
    fun `names the catalogs asked for, leaving out a key with no catalog, for a member who may only generate`() {
        val tenant = withMediator { createTenant("catalog-names").id }
        val other = withMediator { createTenant("catalog-names-other").id }
        withMediator {
            CreateCatalog(tenant, letters, "Letters", namespace = "acme-letters").execute()
            CreateCatalog(tenant, forms, "Forms", namespace = "acme-forms").execute()
        }
        val generator = EpistolaPrincipal(
            userId = UserKey.of("00000000-0000-0000-0000-0000000d0c02"),
            externalId = "catalog-names-generator",
            email = "catalog-names-generator@example.com",
            displayName = "Generator",
            tenantMemberships = mapOf(tenant to setOf(TenantRole.DOCUMENT_GENERATOR)),
            globalRoles = emptySet(),
            platformRoles = emptySet(),
            currentTenantId = tenant,
        )

        val names = runAs(generator) { GetCatalogNames(tenant, listOf(letters, letters, CatalogKey.of("missing"))).query() }
        assertThat(names).containsExactlyEntriesOf(mapOf(letters to CatalogName(identity.defaultRegistry, "acme-letters", letters)))
        assertThat(runAs(generator) { GetCatalogNames(tenant, emptyList()).query() }).isEmpty()
        assertThatThrownBy { runAs(generator) { GetCatalogNames(other, listOf(letters)).query() } }
            .isInstanceOf(TenantAccessDeniedException::class.java)
    }
}
