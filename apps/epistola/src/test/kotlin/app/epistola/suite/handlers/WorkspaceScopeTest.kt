// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.versions.CatalogVersion
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.security.Permission
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Being inside a catalog (docs/workspace-shell.md): what the context bar calls the version a page
 * works in, what the version menu offers, and the catalog's side nav.
 */
class WorkspaceScopeTest {

    private val letters = CatalogKey.of("letters")

    private fun draft(version: String, base: String?) = CatalogVersion(version, CatalogVersionStatus.DRAFT, base = base)

    private fun release(version: String, liveIn: List<String> = emptyList()) = CatalogVersion(
        version,
        CatalogVersionStatus.RELEASED,
        releasedAt = OffsetDateTime.parse("2026-10-01T10:00:00Z"),
        contentRetained = true,
        deployedTo = liveIn,
    )

    private fun bar(
        drafts: List<CatalogVersion>,
        releases: List<CatalogVersion>,
        segment: CatalogSegment = CatalogSegment(letters),
        authored: Boolean = true,
        installed: CatalogVersion? = null,
    ) = CatalogDraftBar(
        tenantKey = TenantKey.of("acme"),
        catalogKey = letters,
        catalogName = "Letters",
        authored = authored,
        segment = segment,
        drafts = drafts,
        releases = releases,
        chosen = segment.version?.let { v -> drafts.first { it.version == v } } ?: drafts.firstOrNull(),
        mayOpenDrafts = true,
        mayDiscardDrafts = true,
        installed = installed,
        pageAt = { "/tenants/acme/templates?catalog=${it.value}" },
    )

    private val everything: (Permission) -> Boolean = { true }

    private val work = listOf(draft("1.3.0", "1.2.0"), draft("1.2.1", "1.2.0"))
    private val published = listOf(release("1.2.0", liveIn = listOf("production")), release("1.1.0", liveIn = listOf("test")))

    @Test
    fun `the bar names the next version plainly, side work by what it fixes and where that is live`() {
        assertThat(WorkspaceBuilder.scope(bar(work, published), null, "/tenants/acme/templates", everything).versionLabel)
            .isEqualTo("Next version")

        val fix = WorkspaceBuilder.scope(bar(work, published, CatalogSegment(letters, "1.2.1")), null, "/tenants/acme/templates", everything)
        assertThat(fix.versionLabel).isEqualTo("Fix of v1.2.0 · production")
        assertThat(fix.sideWork).isTrue()
    }

    @Test
    fun `a published version's page, a subscribed catalog and a catalog with nothing open each say so`() {
        assertThat(WorkspaceBuilder.scope(bar(work, published), "v1.1.0, published", "/tenants/acme/catalogs/letters/releases/1.1.0", everything).versionLabel)
            .isEqualTo("v1.1.0, published")
        assertThat(WorkspaceBuilder.scope(bar(emptyList(), published), null, "/", everything).versionLabel).isEqualTo("Nothing in progress")

        val subscribed = WorkspaceBuilder.scope(bar(emptyList(), published, authored = false, installed = release("1.2.0")), null, "/", everything)
        assertThat(subscribed.versionLabel).isEqualTo("v1.2.0")
        assertThat(subscribed.readOnly).isTrue()
        assertThat(subscribed.statusHref).`as`("nothing to publish in a subscribed catalog").isNull()
    }

    @Test
    fun `the version menu lists the next version first, then side work by what it fixes, at the same page`() {
        val versions = WorkspaceBuilder.scope(bar(work, published), null, "/tenants/acme/templates", everything).versions

        assertThat(versions.map { it.label }).containsExactly("Next version", "Fix of v1.2.0")
        assertThat(versions.map { it.href }).containsExactly(
            "/tenants/acme/templates?catalog=letters",
            "/tenants/acme/templates?catalog=letters@1.2.1",
        )
        assertThat(versions.first().current).isTrue()
    }

    @Test
    fun `fixing what is live continues an open fix, or starts one from a version that can be fixed`() {
        val fixes = WorkspaceBuilder.scope(bar(work, published), null, "/", everything).fixLive

        assertThat(fixes.map { Triple(it.environment, it.version, it.continueHref) }).containsExactly(
            Triple("production", "1.2.0", "/tenants/acme/catalogs/letters@1.2.1/draft"),
            Triple("test", "1.1.0", null),
        )
        assertThat(fixes[1].startHref).isEqualTo("/tenants/acme/catalogs/letters/fixes")
    }

    @Test
    fun `the catalog nav carries the page's segment to content, the plain key to versions and where it is live`() {
        val nav = CatalogNav.sections(bar(work, published, CatalogSegment(letters, "1.2.1")), "/tenants/acme/stencils", everything).flatten()

        assertThat(nav.associate { it.key to it.href }).containsEntry("templates", "/tenants/acme/templates?catalog=letters@1.2.1")
            .containsEntry("stencils", "/tenants/acme/stencils?catalog=letters@1.2.1")
            .containsEntry("uses", "/tenants/acme/catalogs/letters@1.2.1/uses")
            .containsEntry("details", "/tenants/acme/catalogs/letters@1.2.1/details")
            .containsEntry("versions", "/tenants/acme/catalogs/letters/versions")
            .containsEntry("live", "/tenants/acme/catalogs/letters/live")
            .`as`("side work's overview is its draft page").containsEntry("overview", "/tenants/acme/catalogs/letters@1.2.1/draft")
        assertThat(nav.filter { it.active }.map { it.key }).containsExactly("stencils")
    }

    @Test
    fun `parts the viewer may not see are left out of the catalog nav`() {
        val nav = CatalogNav.sections(bar(work, published), "/", { it != Permission.REFERENCE_VIEW }).flatten().map { it.key }

        assertThat(nav).contains("stencils", "themes", "images").doesNotContain("fonts", "attributes", "code-lists")
    }

    @Test
    fun `each page of a catalog lights up its own item`() {
        assertThat(CatalogNav.activeKey("/tenants/acme/catalogs/letters/browse", "letters")).isEqualTo("overview")
        assertThat(CatalogNav.activeKey("/tenants/acme/catalogs/letters@1.2.1/draft", "letters")).isEqualTo("overview")
        assertThat(CatalogNav.activeKey("/tenants/acme/catalogs/letters/releases/1.2.0", "letters")).isEqualTo("versions")
        assertThat(CatalogNav.activeKey("/tenants/acme/catalogs/letters/live", "letters")).isEqualTo("live")
        assertThat(CatalogNav.activeKey("/tenants/acme/templates/letters/invoice", "letters")).isEqualTo("templates")
        assertThat(CatalogNav.activeKey("/tenants/acme/code-lists", "letters")).isEqualTo("code-lists")
        assertThat(CatalogNav.activeKey("/tenants/acme/catalogs/shared/browse", "letters")).`as`("another catalog").isNull()
    }

    @Test
    fun `the way back names the catalog, and the fix when it was one`() {
        assertThat(WorkspaceBuilder.backLabel(WorkspaceBuilder.scope(bar(work, published), null, "/", everything))).isEqualTo("Back to Letters")
        assertThat(WorkspaceBuilder.backLabel(WorkspaceBuilder.scope(bar(work, published, CatalogSegment(letters, "1.2.1")), null, "/", everything)))
            .isEqualTo("Back to Letters, fix of v1.2.0")
    }
}
