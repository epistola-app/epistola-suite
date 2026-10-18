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
import java.util.UUID

/**
 * Being inside a catalog (docs/workspace-shell.md): what the context bar calls the version a page
 * works in, what the version menu offers, and the catalog's side nav.
 */
class WorkspaceScopeTest {

    private val letters = CatalogKey.of("letters")

    private val redesignId = UUID.fromString("0193a6b2-7f4e-7c1d-9a2b-3c4d5e6f7a8b")

    private fun next(base: String?) = CatalogVersion(null, CatalogVersionStatus.DRAFT, base = base, draftId = UUID.fromString("0193a6b2-7f4e-7c1d-9a2b-000000000001"), isNext = true)

    private fun longTerm(name: String, base: String?) = CatalogVersion(null, CatalogVersionStatus.DRAFT, base = base, draftId = redesignId, name = name)

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
        chosen = segment.draft?.let { id -> drafts.first { it.draftId == id } } ?: drafts.firstOrNull { it.isNext },
        mayOpenDrafts = true,
        mayDiscardDrafts = true,
        installed = installed,
        pageAt = { "/tenants/acme/templates?catalog=${it.value}" },
    )

    private val everything: (Permission) -> Boolean = { true }

    private val work = listOf(next("1.2.0"), longTerm("Redesign", "1.1.0"))
    private val onRedesign = CatalogSegment(letters, redesignId)
    private val published = listOf(release("1.2.0", liveIn = listOf("production")), release("1.1.0", liveIn = listOf("test")))

    @Test
    fun `the bar names the next version plainly, long-term work by its name`() {
        assertThat(WorkspaceBuilder.scope(bar(work, published), null, "/tenants/acme/templates", everything).versionLabel)
            .isEqualTo("Next version")

        val longTerm = WorkspaceBuilder.scope(bar(work, published, onRedesign), null, "/tenants/acme/templates", everything)
        assertThat(longTerm.versionLabel).isEqualTo("Redesign")
        assertThat(longTerm.sideWork).isTrue()
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
    fun `the version menu lists the next version first, then long-term work by name and what it started from, each to its overview`() {
        val versions = WorkspaceBuilder.scope(bar(work, published), null, "/tenants/acme/templates", everything).versions

        assertThat(versions.map { it.label to it.detail }).containsExactly(
            "Next version" to "Based on v1.2.0",
            "Redesign" to "Long-term work, started from v1.1.0",
        )
        // The same from every page, so the bar does not change while moving around in a version.
        assertThat(versions.map { it.href }).containsExactly(
            "/tenants/acme/catalogs/letters/browse",
            "/tenants/acme/catalogs/letters@$redesignId/draft",
        )
        assertThat(versions.first().current).isTrue()
    }

    @Test
    fun `the catalog nav carries the page's segment to content, the plain key to versions and where it is live`() {
        val nav = CatalogNav.sections(bar(work, published, onRedesign), "/tenants/acme/stencils", everything).flatten()

        assertThat(nav.associate { it.key to it.href }).containsEntry("templates", "/tenants/acme/templates?catalog=letters@$redesignId")
            .containsEntry("stencils", "/tenants/acme/stencils?catalog=letters@$redesignId")
            .containsEntry("dependencies", "/tenants/acme/catalogs/letters@$redesignId/dependencies")
            .containsEntry("settings", "/tenants/acme/catalogs/letters@$redesignId/settings")
            .containsEntry("versions", "/tenants/acme/catalogs/letters/versions")
            .containsEntry("deployments", "/tenants/acme/catalogs/letters/deployments")
            .`as`("long-term work's overview is its draft page").containsEntry("overview", "/tenants/acme/catalogs/letters@$redesignId/draft")
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
        assertThat(CatalogNav.activeKey("/tenants/acme/catalogs/letters@$redesignId/draft", "letters")).isEqualTo("overview")
        assertThat(CatalogNav.activeKey("/tenants/acme/catalogs/letters/releases/1.2.0", "letters")).isEqualTo("versions")
        assertThat(CatalogNav.activeKey("/tenants/acme/catalogs/letters/deployments", "letters")).isEqualTo("deployments")
        assertThat(CatalogNav.activeKey("/tenants/acme/templates/letters/invoice", "letters")).isEqualTo("templates")
        assertThat(CatalogNav.activeKey("/tenants/acme/code-lists", "letters")).isEqualTo("code-lists")
        assertThat(CatalogNav.activeKey("/tenants/acme/catalogs/shared/browse", "letters")).`as`("another catalog").isNull()
    }

    @Test
    fun `the way back names the catalog, and the long-term work when it was that`() {
        assertThat(WorkspaceBuilder.backLabel(WorkspaceBuilder.scope(bar(work, published), null, "/", everything))).isEqualTo("Back to Letters")
        assertThat(WorkspaceBuilder.backLabel(WorkspaceBuilder.scope(bar(work, published, onRedesign), null, "/", everything)))
            .`as`("a name as people wrote it").isEqualTo("Back to Letters, Redesign")
    }

    @Test
    fun `templates lead the group of what the catalog holds, and quality sits with the overview when it is on`() {
        val sections = CatalogNav.sections(bar(work, published), "/tenants/acme/quality", everything, qualityAvailable = true)

        assertThat(sections[0].map { it.key }).containsExactly("overview", "quality")
        assertThat(sections[1].map { it.key }).containsExactly("templates", "stencils", "themes", "images", "fonts", "attributes", "code-lists")
        assertThat(sections[0].first { it.key == "quality" }.href).isEqualTo("/tenants/acme/quality?catalog=letters")
        assertThat(sections.flatten().filter { it.active }.map { it.key }).containsExactly("quality")
        assertThat(CatalogNav.sections(bar(work, published), "/", everything).flatten().map { it.key }).doesNotContain("quality")
    }
}
