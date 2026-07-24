// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.htmx.nav

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.htmx.UiRequestContext
import app.epistola.suite.security.Permission
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NavMenuAggregatorTest {

    private val tenant = TenantKey.of("acme")

    private fun ctx(hasPermission: (Permission) -> Boolean = { true }) = UiRequestContext(tenant, hasPermission)

    private fun contributor(
        groups: List<NavGroup> = emptyList(),
        items: (UiRequestContext) -> List<NavItem> = { emptyList() },
    ): NavContributor = object : NavContributor {
        override fun groups(context: UiRequestContext) = groups
        override fun items(context: UiRequestContext) = items(context)
    }

    @Test
    fun `orders groups by order then key, items by order then sectionKey`() {
        val a = contributor(
            groups = listOf(NavGroup("b", "B", order = 20), NavGroup("a", "A", order = 10)),
            items = {
                listOf(
                    NavItem("a", "a2", "A2", "a2", order = 20),
                    NavItem("a", "a1", "A1", "a1", order = 10),
                    NavItem("b", "b1", "B1", "b1", order = 10),
                )
            },
        )
        val model = NavMenuAggregator(listOf(a)).build(ctx(), "/tenants/acme")

        assertThat(model.groups.map { it.key }).containsExactly("a", "b")
        assertThat(model.groups.first().items.map { it.sectionKey }).containsExactly("a1", "a2")
    }

    @Test
    fun `drops groups with no visible items`() {
        val support = contributor(
            groups = listOf(NavGroup("support", "Support", order = 80)),
            items = { emptyList() }, // all toggles off → no items
        )
        val core = contributor(
            groups = listOf(NavGroup("authoring", "Authoring", order = 10)),
            items = { listOf(NavItem("authoring", "templates", "Templates", "templates", order = 10)) },
        )
        val model = NavMenuAggregator(listOf(support, core)).build(ctx(), "/tenants/acme")

        assertThat(model.groups.map { it.key }).containsExactly("authoring")
    }

    @Test
    fun `drops items whose permission is denied, hiding the now-empty group`() {
        val ops = contributor(
            groups = listOf(NavGroup("operations", "Operations", order = 30)),
            items = { c ->
                buildList {
                    if (c.hasPermission(Permission.DOCUMENT_GENERATE)) add(NavItem("operations", "load-tests", "Load Tests", "load-tests", 20))
                }
            },
        )
        val model = NavMenuAggregator(listOf(ops)).build(ctx(hasPermission = { it == Permission.DOCUMENT_VIEW }), "/tenants/acme")

        assertThat(model.groups).isEmpty()
    }

    @Test
    fun `merges items from multiple contributors into one declared group`() {
        val owner = contributor(groups = listOf(NavGroup("support", "Support", order = 80, testId = "nav-dropdown-support"))) {
            listOf(NavItem("support", "overview", "Overview", "support", order = 0))
        }
        val feedback = contributor { listOf(NavItem("support", "feedback", "Feedback", "feedback", order = 10)) }
        val backups = contributor { listOf(NavItem("support", "backups", "Backups", "backups", order = 20)) }

        val model = NavMenuAggregator(listOf(feedback, owner, backups)).build(ctx(), "/tenants/acme")

        assertThat(model.groups).hasSize(1)
        val support = model.groups.single()
        assertThat(support.testId).isEqualTo("nav-dropdown-support")
        assertThat(support.items.map { it.sectionKey }).containsExactly("overview", "feedback", "backups")
    }

    @Test
    fun `propagates feature stage from item to view, defaulting to STABLE`() {
        val support = contributor(groups = listOf(NavGroup("support", "Support", order = 80))) {
            listOf(
                NavItem("support", "overview", "Overview", "support", order = 0),
                NavItem("support", "backups", "Backups", "backups", order = 20, stage = KnownFeatures.FeatureStage.BETA),
            )
        }
        val model = NavMenuAggregator(listOf(support)).build(ctx(), "/tenants/acme")

        val items = model.groups.single().items
        assertThat(items.single { it.sectionKey == "overview" }.stage).isEqualTo(KnownFeatures.FeatureStage.STABLE)
        assertThat(items.single { it.sectionKey == "backups" }.stage).isEqualTo(KnownFeatures.FeatureStage.BETA)
    }

    @Test
    fun `skips items referencing an undeclared group`() {
        val orphan = contributor { listOf(NavItem("ghost", "x", "X", "x", order = 0)) }
        val model = NavMenuAggregator(listOf(orphan)).build(ctx(), "/tenants/acme")
        assertThat(model.groups).isEmpty()
    }

    private val sectionItems = listOf(
        NavItem("authoring", "templates", "Templates", "templates", 10),
        NavItem("resources", "code-lists", "Code lists", "code-lists", 10),
        NavItem("support", "overview", "Overview", "support", 0),
    )

    private fun activeFor(path: String): String {
        val c = contributor(
            groups = listOf(
                NavGroup("authoring", "Authoring", 10),
                NavGroup("resources", "Resources", 20),
                NavGroup("support", "Support", 80),
            ),
        ) { sectionItems }
        return NavMenuAggregator(listOf(c)).build(ctx(), path).activeNavSection
    }

    @Test
    fun `active section uses longest segment-boundary match`() {
        assertThat(activeFor("/tenants/acme/templates")).isEqualTo("templates")
        assertThat(activeFor("/tenants/acme/templates/cat/123/settings")).isEqualTo("templates")
        assertThat(activeFor("/tenants/acme/code-lists")).isEqualTo("code-lists")
        // sectionKey may differ from the pathSuffix
        assertThat(activeFor("/tenants/acme/support")).isEqualTo("overview")
    }

    @Test
    fun `active section strips query and falls back to home`() {
        assertThat(activeFor("/tenants/acme/templates?q=x")).isEqualTo("templates")
        assertThat(activeFor("/tenants/acme/unknown")).isEqualTo("home")
    }

    @Test
    fun `group is active when a child item is active`() {
        val c = contributor(groups = listOf(NavGroup("authoring", "Authoring", 10))) { sectionItems }
        val model = NavMenuAggregator(listOf(c)).build(ctx(), "/tenants/acme/templates")
        val authoring = model.groups.single { it.key == "authoring" }
        assertThat(authoring.active).isTrue()
        assertThat(authoring.items.single { it.sectionKey == "templates" }.active).isTrue()
    }
}
