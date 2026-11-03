// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import com.microsoft.playwright.assertions.PlaywrightAssertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Layout on the catalog pages that only a browser can judge: text staying inside the dependency
 * graph's boxes, and the divider between the releases an environment card lists. The markup itself is
 * covered by `CatalogGraphPageTest` and `CatalogDeploymentsPageTest`.
 */
class CatalogPagesLayoutUiTest : BasePlaywrightTest() {

    @Test
    fun `a graph node's name and address stay inside its box, also with a larger browser font`() {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant("Graph Layout")
                withMediator {
                    CreateCatalog(tenantKey = tenant.id, id = CatalogKey.of("shared"), name = "Shared").execute()
                    CreateCatalog(
                        tenantKey = tenant.id,
                        id = CatalogKey.of("letters"),
                        name = "Letters for every municipal department and all of their outgoing correspondence",
                    ).execute()
                    val shared = testCatalog(TenantId(tenant.id), CatalogKey.of("shared"))
                    CreateTheme(ThemeId(ThemeKey.of("brand"), shared), "Brand").execute()
                    mediator.releaseNext(shared)
                    val target = GetCatalog(testCatalog(tenant.id, shared.key)).query()!!
                    UpdateCatalogDependencies(
                        testCatalog(tenant.id, CatalogKey.of("letters")),
                        listOf(DeclaredCatalogDependency(target.registry, target.namespace, "shared", "1.0.0")),
                    ).execute()
                }
            }
        }

        gotoAndReady("/tenants/${tenant.id.value}/catalogs/graph")
        PlaywrightAssertions.assertThat(page.locator("[data-testid='catalog-graph']")).isVisible()
        // A reader who set the browser's font to "Very large" (24px): the graph is laid out in SVG pixels.
        page.evaluate("document.documentElement.style.fontSize = '24px'")

        @Suppress("UNCHECKED_CAST")
        val escaping = page.evaluate(
            """
            () => [...document.querySelectorAll('[data-testid=catalog-graph] foreignObject')].flatMap(box => {
              const bounds = box.getBoundingClientRect();
              return [...box.querySelectorAll('.catalog-graph-box-name, .catalog-graph-box-detail')]
                .filter(line => {
                  const r = line.getBoundingClientRect();
                  // Outside the box, or squeezed inside it so the line's own text is clipped.
                  return r.top < bounds.top - 0.5 || r.bottom > bounds.bottom + 0.5 || r.right > bounds.right + 0.5 ||
                    line.scrollHeight > line.clientHeight + 1;
                })
                .map(line => line.textContent.trim());
            })
            """.trimIndent(),
        ) as List<String>
        assertThat(escaping).`as`("lines drawn outside their box, or cut off inside it").isEmpty()
    }

    @Test
    fun `an environment card divides the releases it serves, and nothing after the last one`() {
        lateinit var tenant: Tenant
        val letters = CatalogKey.of("letters")
        fixture { given { tenant = tenant("Deployment Layout") } }
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()
            val brand = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant.id), letters))
            CreateTheme(brand, "Brand").execute()
            MarkCatalogReady(testCatalog(tenant.id, letters)).execute()
            ReleaseCatalogDraft(testCatalog(tenant.id, letters), version = "1.0.0").execute()
            UpdateTheme(brand, name = "Brand, corrected").execute()
            MarkCatalogReady(testCatalog(tenant.id, letters)).execute()
            ReleaseCatalogDraft(testCatalog(tenant.id, letters), version = "1.0.1").execute()
            val test = EnvironmentId(EnvironmentKey.of("test"), TenantId(tenant.id))
            CreateEnvironment(test, "Test", production = false).execute()
            DeployCatalogRelease(test, testCatalog(test.tenantKey, letters), "1.0.0").execute()
            DeployCatalogRelease(test, testCatalog(test.tenantKey, letters), "1.0.1").execute()
            // Following a draft adds a line after the releases: the case where the last one kept its divider.
            val draft = CreateCatalogDraft(testCatalog(tenant.id, letters), "Redesign").execute().versionId
            DeployCatalogRelease(test, testCatalog(test.tenantKey, letters), draft).execute()
        }

        gotoAndReady("/tenants/${tenant.id.value}/catalogs/${tenant.id.value}/letters/deployments")
        val card = page.locator("[data-environment='test']")
        PlaywrightAssertions.assertThat(card.locator("[data-testid='catalog-deployment-follows']")).isVisible()

        @Suppress("UNCHECKED_CAST")
        val dividers = card.evaluate(
            """
            card => [...card.querySelectorAll('.catalog-deployment-release')].map(release => {
              const style = getComputedStyle(release);
              return release.dataset.version + ' top=' + style.borderTopWidth + ' bottom=' + style.borderBottomWidth;
            })
            """.trimIndent(),
        ) as List<String>
        // Newest first; the divider sits between the two, none under the last.
        assertThat(dividers).containsExactly("1.0.1 top=0px bottom=0px", "1.0.0 top=1px bottom=0px")
    }
}
