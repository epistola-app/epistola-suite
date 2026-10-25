// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource

/**
 * Theme reads plan well before the planner has statistics on the storage tables (ADR 0033).
 *
 * A fresh installation starts with `release_resources` and `resource_contents` empty. pgjdbc
 * server-prepares a statement after its fifth execution and Postgres may then keep a generic plan
 * made while the tables were empty, until autoanalyze runs. A plan that depends on statistics then
 * degrades as the tables grow: releasing a catalog went from 0.3 s to 8 s over 25 catalogs, since
 * the generic plan of a catalog's themes rescanned the tenant's rows once per stored content.
 *
 * Its own context, so its own freshly migrated database, with autoanalyze off on the storage tables
 * for the test, so no statistics arrive midway: releases must stay flat as the data grows.
 */
@TestPropertySource(properties = ["epistola.test.context=theme-plan-stability"])
class ThemePlanStabilityTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private val storage = listOf("release_resources", "resource_contents", "resource_references", "catalog_resources", "resource_credentials", "code_list_refreshes")

    private fun autoanalyze(on: Boolean) = jdbi.useHandle<Exception> { h ->
        storage.forEach { h.execute("ALTER TABLE $it SET (autovacuum_enabled = $on)") }
    }

    @Test
    fun `releasing stays flat as themes and code lists accumulate on tables without statistics`() {
        autoanalyze(false)
        try {
            val releases = withMediator {
                val tenant = createTenant("Theme plan stability").id
                (0 until CATALOGS).map { c ->
                    val key = CatalogKey.of("cat-$c")
                    CreateCatalog(tenantKey = tenant, id = key, name = "Catalog $c").execute()
                    val catalog = testCatalog(tenant, key)
                    repeat(THEMES) { t -> CreateTheme(ThemeId(ThemeKey.of("theme-$t"), catalog), "Theme $c/$t").execute() }
                    // Code lists read through the same function and views (ADR 0033, step 2).
                    repeat(CODE_LISTS) { l ->
                        CreateCodeList(
                            CodeListId(CodeListKey.of("list-$l"), catalog),
                            "List $c/$l",
                            sourceType = CodeListSource.INLINE,
                            entries = (0 until 20).map { CodeListEntry("c$it", "Entry $it", it) },
                        ).execute()
                    }
                    val start = System.nanoTime()
                    mediator.releaseNext(catalog)
                    (System.nanoTime() - start) / 1_000_000
                }
            }
            val first = releases.take(5).sorted()[2]
            val last = releases.takeLast(5).sorted()[2]
            assertThat(last)
                .`as`("median of the last five releases (ms), against the first five's $first ms: %s", releases)
                .isLessThanOrEqualTo(maxOf(BUDGET_MS, 2 * first))
        } finally {
            autoanalyze(true)
        }
    }

    private companion object {
        const val CATALOGS = 20
        const val THEMES = 25
        const val CODE_LISTS = 5
        const val BUDGET_MS = 300L
    }
}
