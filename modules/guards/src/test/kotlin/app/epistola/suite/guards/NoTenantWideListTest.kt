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
 * (`"/tenants/${tenantId.key}/themes"`, `…/themes?catalog=…`). Creating is the catalog's too
 * (`…/catalogs/{catalog}/themes/new`, a post to `…/catalogs/{catalog}/themes`); only the editors'
 * pickers stay at tenant level: `/search`, and the image picker's `/catalogs`.
 */
class NoTenantWideListTest {

    private val types = "templates|stencils|themes|images|fonts|code-lists|attributes"

    /** A template link, redirect or form action to `/tenants/{tenantId}/<type>(…)`, or to its `/new`. */
    private val templateLink = Regex("""@\{/tenants/\{tenantId}/($types)(/new)?\(""")

    /** A Kotlin string that is a tenant-level list path, with or without a query. */
    private val kotlinPath = Regex(""""(/tenants/\$\{[A-Za-z.]+}|\${'$'}tenant)/($types)(/new)?(\?[^"]*)?"""")

    @Test
    fun `nothing links or redirects to a list across catalogs`() {
        val violations = mutableListOf<String>()
        RepoSources.uiAssetFiles().filter { it.toString().endsWith(".html") }.forEach { path ->
            val text = RepoSources.stripHtmlComments(Files.readString(path))
            text.lineSequence().forEachIndexed { i, line ->
                if (templateLink.containsMatchIn(line)) {
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

    /**
     * A resource's pages are its catalog's too: `/tenants/{t}/catalogs/{catalog}/themes/{key}`, never
     * `/tenants/{t}/themes/{catalog}/{key}`. Matches the old shape in templates, Kotlin and static JS;
     * the tenant-level `/new`, `/search` and the image picker's `/catalogs` are not resource pages.
     */
    @Test
    fun `nothing addresses a resource outside its catalog`() {
        val oldShape = Regex("""(?<!/api)/tenants/[^/"'\s()]+/($types)/(?!(?:new|search|catalogs)\b)[^/"'?\s]+/""")
        val sources = RepoSources.uiAssetFiles() + RepoSources.mainKotlinFiles()
        val violations = sources.flatMap { path ->
            Files.readAllLines(path).mapIndexedNotNull { i, line -> if (oldShape.containsMatchIn(line)) "${RepoSources.relativize(path)}:${i + 1}: ${line.trim()}" else null }
        }
        assertTrue(violations.isEmpty(), "Resource URLs outside their catalog (use /tenants/{t}/catalogs/{catalog}/{type}/{key}):\n" + violations.joinToString("\n"))
    }
}
