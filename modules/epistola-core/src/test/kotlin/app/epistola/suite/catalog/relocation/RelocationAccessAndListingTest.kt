// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.ReleasePublication
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PermissionDeniedException
import app.epistola.suite.security.TenantRole
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Who may look at a move and who may apply one, and what the organise browser is offered to move.
 */
class RelocationAccessAndListingTest : RelocationTestSupport() {

    @Test
    fun `a catalog viewer can preview a move but not apply it`() {
        val tenant = tenantWith("Viewer cannot move")
        val header = create(tenant, MovableResource.STENCIL, letters, "header")
        val viewer = memberWith(tenant, TenantRole.CONTENT_VIEWER)

        val plan = runAs(viewer) { PreviewCatalogResourceMove(tenant, listOf(header.movedTo(shared))).query() }
        assertThat(plan.executable).isTrue()

        assertThatThrownBy { runAs(viewer) { MoveCatalogResources(tenant, listOf(header.movedTo(shared)), plan.planFingerprint).execute() } }
            .isInstanceOf(PermissionDeniedException::class.java)
        assertThat(resolve(tenant, header)!!.canonical).isEqualTo(header)
    }

    @Test
    fun `a member without catalog access cannot even preview`() {
        val tenant = tenantWith("Generator cannot preview")
        val header = create(tenant, MovableResource.STENCIL, letters, "header")
        val generator = memberWith(tenant, TenantRole.DOCUMENT_GENERATOR)

        assertThatThrownBy { runAs(generator) { PreviewCatalogResourceMove(tenant, listOf(header.movedTo(shared))).query() } }
            .isInstanceOf(PermissionDeniedException::class.java)
    }

    @Test
    fun `the browser offers every movable type from authored catalogs only`() {
        val tenant = tenantWith("Listing types")
        val created = MovableResource.entries.map { create(tenant, it, letters, "listed-${it.name.lowercase().replace('_', '-')}") }

        val listed = withMediator { ListRelocatableResources(tenant, limit = 200).query() }.map { it.address }

        assertThat(listed).containsAll(created)
        // The bundled system catalog is subscribed; nothing in it can move.
        assertThat(listed).noneMatch { it.catalogKey == "system" }
    }

    @Test
    fun `the browser finds a resource by name or key`() {
        val tenant = tenantWith("Listing search")
        val header = create(tenant, MovableResource.STENCIL, letters, "header")
        create(tenant, MovableResource.STENCIL, letters, "footer")

        assertThat(withMediator { ListRelocatableResources(tenant, search = "head").query() }.map { it.address }).containsExactly(header)
        // `create` names a stencil "Stencil <key>", so the name matches too.
        assertThat(withMediator { ListRelocatableResources(tenant, search = "Stencil header").query() }.map { it.address }).containsExactly(header)
    }

    @Test
    fun `the page size is clamped rather than trusted`() {
        val tenant = tenantWith("Listing clamp")
        repeat(3) { create(tenant, MovableResource.STENCIL, letters, "clamp-$it") }

        assertThat(withMediator { ListRelocatableResources(tenant, limit = 0).query() }).hasSize(1)
        assertThat(withMediator { ListRelocatableResources(tenant, limit = -5).query() }).hasSize(1)
    }

    @Test
    fun `a resource in a released catalog carries a note that subscribers will not follow`() {
        val tenant = tenantWith("Listing released")
        create(tenant, MovableResource.STENCIL, letters, "header")
        create(tenant, MovableResource.STENCIL, shared, "footer")
        withMediator { ReleaseCatalogVersion(tenant, letters, "1.0.0", publication = ReleasePublication.SKIP).execute() }

        val listed = withMediator { ListRelocatableResources(tenant).query() }.associateBy { it.address.key }

        assertThat(listed.getValue("header").note).contains("Released")
        assertThat(listed.getValue("footer").note).isNull()
    }

    private fun memberWith(tenant: TenantKey, role: TenantRole) = EpistolaPrincipal(
        userId = UserKey.of("00000000-0000-0000-0000-00000000c0${role.ordinal}1"),
        externalId = "relocation-${role.name.lowercase()}",
        email = "relocation-${role.name.lowercase()}@example.com",
        displayName = "Relocation ${role.name.lowercase()}",
        tenantMemberships = mapOf(tenant to setOf(role)),
        globalRoles = emptySet(),
        platformRoles = emptySet(),
        currentTenantId = tenant,
    )
}
