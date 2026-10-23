// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.htmx

import app.epistola.suite.catalog.releases.CatalogReleaseNotFoundException
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/** A catalog in a UI route, optionally one of its drafts: `letters` or `letters@2.0.0`. */
class CatalogSegmentTest {

    private val tenant = TenantKey.of("acme")
    private val work = "k7m2x9qa"

    @Test
    fun `a plain key names the catalog's current version, its next version`() {
        val segment = CatalogSegment.parse("letters")

        assertThat(segment.key).isEqualTo(CatalogKey.of("letters"))
        assertThat(segment.draft).isNull()
        assertThat(segment.ref(tenant)).isNull()
        assertThat(segment.value).isEqualTo("letters")
    }

    @Test
    fun `key at a draft id names that draft`() {
        val segment = CatalogSegment.parse("letters@$work")

        assertThat(segment.key).isEqualTo(CatalogKey.of("letters"))
        assertThat(segment.ref(tenant)).isEqualTo(CatalogReleaseRef(tenant, CatalogKey.of("letters"), DraftRef.Id(work)))
        assertThat(segment.value).isEqualTo("letters@$work")
        assertThat(segment.at(null).value).isEqualTo("letters")
    }

    @Test
    fun `a version number, anything else that is not a draft id, or a bad key, is refused`() {
        assertThatThrownBy { CatalogSegment.parse("letters@2.0.0") }.`as`("a draft has no number: not there, as an old link").isInstanceOf(CatalogReleaseNotFoundException::class.java)
        assertThatThrownBy { CatalogSegment.parse("letters@Not_An_Id") }.isInstanceOf(CatalogReleaseNotFoundException::class.java)
        assertThatThrownBy { CatalogSegment.parse("letters@abc") }.`as`("a version id has at least four characters").isInstanceOf(CatalogReleaseNotFoundException::class.java)
        assertThatThrownBy { CatalogSegment.parse("letters@next") }.`as`("the next version is the plain key").isInstanceOf(CatalogReleaseNotFoundException::class.java)
        assertThatThrownBy { CatalogSegment.parse("Bad Key@$work") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(CatalogSegment.parseOrNull("letters@")).isNull()
        assertThat(CatalogSegment.parseOrNull("  ")).isNull()
    }

    @Test
    fun `a resource of the same catalog links into the draft, another catalog's plainly`() {
        val segment = CatalogSegment.parse("letters@$work")

        assertThat(segment.linkFor(CatalogKey.of("letters"))).isEqualTo("letters@$work")
        assertThat(segment.linkFor("system")).isEqualTo("system")
    }
}
