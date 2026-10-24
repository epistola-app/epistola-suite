// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists

import app.epistola.suite.attributes.codelists.model.CodeList
import app.epistola.suite.attributes.codelists.queries.GetCodeList
import app.epistola.suite.attributes.codelists.queries.ListCodeListEntries
import app.epistola.suite.attributes.codelists.queries.ListCodeLists
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.catalog.system.systemCatalog
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A code list reads the same from a draft and from the release that holds the same content (ADR
 * 0033, step 2): one read interface, `current_code_lists`, whatever version it is of. Every code list
 * of the bundled system catalog is read both ways: from its next version and from that release.
 */
class CodeListReadParityTest : IntegrationTestBase() {

    /** What a code list says, without the bookkeeping a draft row and a release row keep apart. */
    private fun CodeList.content() = listOf(slug, catalogKey, displayName, description, sourceType, sourceUrl, authType)

    @Test
    fun `every bundled code list reads the same from the next version and from the release it holds`() {
        val tenant = createTenant("Code list read parity").id
        val catalog = systemCatalog(tenant)
        val release = withMediator { ListCatalogReleases(catalog).query() }.first { it.status == CatalogReleaseStatus.RELEASED }
        val released = CatalogReleaseRef(catalog, DraftRef.Released(requireNotNull(release.version)))

        val drafted = withMediator { ListCodeLists(catalog.tenantId, catalog = catalog).query() }
        val fromRelease = withMediator { ListCodeLists(catalog.tenantId, catalog = catalog, catalogVersion = released).query() }

        assertThat(drafted).isNotEmpty()
        assertThat(fromRelease.map { it.content() }).isEqualTo(drafted.map { it.content() })
        for (list in drafted) {
            val id = CodeListId(list.slug, catalog)
            assertThat(withMediator { GetCodeList(id, catalogVersion = released).query() }?.content()).`as`(list.slug.value).isEqualTo(list.content())
            assertThat(withMediator { ListCodeListEntries(id, includeHidden = true, catalogVersion = released).query() })
                .`as`("entries of ${list.slug.value}")
                .isEqualTo(withMediator { ListCodeListEntries(id, includeHidden = true).query() })
        }
    }
}
