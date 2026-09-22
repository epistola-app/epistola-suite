// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * One test per blocker the planner can raise, each proving the same two things: the preview names
 * the problem on the right member, and executing that preview anyway changes nothing.
 *
 * `address-swap-cycle` and `catalog-dependency-cycle` are covered in
 * [CatalogResourceRelocationIntegrationTest]; `unsupported-resource-type` is unreachable while
 * every type is movable, which that class also asserts.
 */
class CatalogResourceMoveBlockersTest : RelocationTestSupport() {

    @Test
    fun `an empty batch is refused`() {
        val tenant = tenantWith("Empty batch")

        assertRefused(tenant, emptyList(), "empty-batch")
    }

    @Test
    fun `listing the same resource twice is refused`() {
        val tenant = tenantWith("Duplicate source")
        val header = create(tenant, MovableResource.STENCIL, letters, "header")

        assertRefused(tenant, listOf(header.movedTo(shared), header.movedTo(archive, "header")), "duplicate-source", on = header)
    }

    @Test
    fun `two members landing on one address is refused`() {
        val tenant = tenantWith("Colliding targets")
        val header = create(tenant, MovableResource.STENCIL, letters, "header")
        val banner = create(tenant, MovableResource.STENCIL, letters, "banner")

        assertRefused(tenant, listOf(header.movedTo(shared, "masthead"), banner.movedTo(shared, "masthead")), "colliding-targets")
    }

    @Test
    fun `a relocation onto its own address is refused`() {
        val tenant = tenantWith("Unchanged address")
        val header = create(tenant, MovableResource.STENCIL, letters, "header")

        assertRefused(tenant, listOf(header.movedTo(letters)), "unchanged-address", on = header)
    }

    @Test
    fun `a resource in a subscribed catalog cannot be moved`() {
        val tenant = tenantWith("Subscribed source")
        // Every tenant has the bundled system catalog installed, subscribed, with a default theme.
        val systemTheme = ResourceAddress(CatalogResourceType.THEME, "system", "default")
        assertThat(identityAt(tenant, systemTheme)).describedAs("the system catalog's default theme").isNotNull()

        assertRefused(tenant, listOf(systemTheme.movedTo(shared)), "source-read-only", on = systemTheme)
    }

    @Test
    fun `a resource in a catalog that does not exist is refused, not guessed at`() {
        val tenant = tenantWith("Missing source catalog")
        val nowhere = ResourceAddress(CatalogResourceType.STENCIL, "nowhere", "header")

        val plan = preview(tenant, nowhere.movedTo(shared))

        assertThat(plan.blockers.map { it.code }).contains("source-read-only", "resource-not-found")
        assertThat(plan.executable).isFalse()
    }

    @Test
    fun `a subscribed catalog is never a destination`() {
        val tenant = tenantWith("Subscribed target")
        val header = create(tenant, MovableResource.STENCIL, letters, "header")

        assertRefused(tenant, listOf(header.movedTo(CatalogKey.of("system"))), "target-read-only", on = header)
    }

    @Test
    fun `a catalog that does not exist is never a destination`() {
        val tenant = tenantWith("Missing target catalog")
        val header = create(tenant, MovableResource.STENCIL, letters, "header")

        assertRefused(tenant, listOf(header.movedTo(CatalogKey.of("nowhere"))), "target-read-only", on = header)
    }

    @Test
    fun `a live resource at the destination blocks the move`() {
        val tenant = tenantWith("Occupied by resource")
        val header = create(tenant, MovableResource.STENCIL, letters, "header")
        create(tenant, MovableResource.STENCIL, shared, "header")

        assertRefused(tenant, listOf(header.movedTo(shared)), "target-occupied", on = header)
    }

    @Test
    fun `an address another resource left behind is free to take`() {
        val tenant = tenantWith("Vacated address", listOf(letters, shared, archive))
        val first = create(tenant, MovableResource.STENCIL, letters, "header")
        val second = create(tenant, MovableResource.STENCIL, archive, "header")
        move(tenant, first.movedTo(shared))
        val secondIdentity = identityAt(tenant, second)

        // A move leaves nothing behind: whatever still names letters/header now means the second.
        move(tenant, second.movedTo(letters))

        assertThat(identityAt(tenant, first)).isEqualTo(secondIdentity)
    }

    @Test
    fun `the same key in another type does not occupy the address`() {
        val tenant = tenantWith("Occupancy is typed")
        val header = create(tenant, MovableResource.STENCIL, letters, "header")
        create(tenant, MovableResource.THEME, shared, "header")

        assertThat(preview(tenant, header.movedTo(shared)).blockers).isEmpty()
    }

    @Test
    fun `an image keeps its key wherever it moves`() {
        val tenant = tenantWith("Image rename refused")
        val logo = create(tenant, MovableResource.ASSET, letters, "logo")

        assertRefused(tenant, listOf(logo.renamedTo("brand-mark")), "rename-unsupported", on = logo)
        assertRefused(tenant, listOf(logo.movedTo(shared, "brand-mark")), "rename-unsupported", on = logo)
        // Moving it without a new key is allowed.
        assertThat(preview(tenant, logo.movedTo(shared)).blockers).isEmpty()
    }

    /**
     * Nothing checked the destination key's shape, so a key the type cannot hold passed the preview
     * and failed in the database at execute, after the plan had been approved. Every type validates
     * its key the same way on creation; the planner has to apply the same rule.
     */
    @Test
    fun `a key the destination type cannot hold is refused in the preview`() {
        val tenant = tenantWith("Invalid target key")
        val invalid = listOf("Not A Slug", "x".repeat(200), "trailing-")

        for (movable in MovableResource.entries.filter { it.renameable }) {
            val source = create(tenant, movable, letters, "valid-${movable.name.lowercase().replace('_', '-')}")
            for (key in invalid) {
                assertRefused(tenant, listOf(source.renamedTo(key)), "invalid-target-key", on = source)
            }
        }
    }

    /**
     * Previews [relocations], requires a blocker with [code] (attributed to [on] when given), then
     * executes that very preview and requires it refused with nothing changed: every source still
     * living where it was.
     */
    private fun assertRefused(tenant: TenantKey, relocations: List<ResourceRelocation>, code: String, on: ResourceAddress? = null) {
        val sources = relocations.map { it.source }.distinct()
        val identitiesBefore = sources.associateWith { identityAt(tenant, it) }
        val plan = preview(tenant, relocations)

        assertThat(plan.executable).describedAs("%s must not be executable", relocations.map { it.source.id to it.target.id }).isFalse()
        assertThat(plan.blockers)
            .describedAs("blockers for %s", relocations.map { it.source.id to it.target.id })
            .anySatisfy { blocker ->
                assertThat(blocker.code).isEqualTo(code)
                on?.let { assertThat(blocker.source).isEqualTo(it) }
            }

        assertThatThrownBy { withMediator { MoveCatalogResources(tenant, relocations, plan.planFingerprint).execute() } }
            .isInstanceOf(CatalogResourceMoveBlockedException::class.java)

        for (source in sources) {
            assertThat(identityAt(tenant, source)).describedAs("%s must not have moved", source.id).isEqualTo(identitiesBefore[source])
        }
    }
}
