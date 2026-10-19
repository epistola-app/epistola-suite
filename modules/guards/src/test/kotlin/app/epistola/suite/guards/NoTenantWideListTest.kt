// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Resources live in catalogs: there is no list of every catalog's templates, themes, stencils,
 * images, fonts, code lists or attributes. A list is at `/tenants/{t}/catalogs/{catalog}/themes`, and
 * `/tenants/{t}/themes` (with or without `?catalog=`) is no page. This finds links and redirects to
 * it, in templates (`@{/tenants/{tenantId}/themes(tenantId=${tenantId})}`) and in handlers
 * (`"/tenants/${tenantId.key}/themes"`, `…/themes?catalog=…`). A form posting there is fine: that
 * creates, it does not list; so are `/new` and `/search`, which are partial responses.
 */
class NoTenantWideListTest {

    private val types = "templates|stencils|themes|images|fonts|code-lists|attributes"

    /** A template link to `/tenants/{tenantId}/<type>(…)`: the old list, unless it is a form's action. */
    private val templateLink = Regex("""@\{/tenants/\{tenantId}/($types)\(""")

    /** A Kotlin string that is a tenant-level list path, with or without a query. */
    private val kotlinPath = Regex(""""(/tenants/\$\{[A-Za-z.]+}|\${'$'}tenant)/($types)(\?[^"]*)?"""")

    @Test
    fun `nothing links or redirects to a list across catalogs`() {
        val violations = mutableListOf<String>()
        RepoSources.uiAssetFiles().filter { it.toString().endsWith(".html") }.forEach { path ->
            val text = RepoSources.stripHtmlComments(Files.readString(path))
            text.lineSequence().forEachIndexed { i, line ->
                if (templateLink.containsMatchIn(line) && "th:action=" !in line && "hx-post=" !in line) {
                    violations += "${RepoSources.relativize(path)}:${i + 1}: ${line.trim()}"
                }
            }
        }
        RepoSources.mainKotlinFiles().forEach { path ->
            Files.readAllLines(path).forEachIndexed { i, line ->
                if (kotlinPath.containsMatchIn(line)) violations += "${RepoSources.relativize(path)}:${i + 1}: ${line.trim()}"
            }
        }
        assertTrue(violations.isEmpty(), "Links or redirects to a list without a catalog (use CatalogPages.list or ?catalog=):\n" + violations.joinToString("\n"))
    }
}
