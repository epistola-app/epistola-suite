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

/** A catalog in a UI route, optionally one of its versions: `letters`, `letters@<draft id>` or `letters@2.0.0`. */
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
        val segment = CatalogSegment.parse("acme/letters@$work")

        assertThat(segment.key).isEqualTo(CatalogKey.of("letters"))
        assertThat(segment.ref(tenant)).isEqualTo(CatalogReleaseRef(app.epistola.suite.common.ids.CatalogId("acme", CatalogKey.of("letters"), app.epistola.suite.common.ids.TenantId(tenant)), DraftRef.Id(work)))
        assertThat(segment.value).isEqualTo("acme/letters@$work")
        assertThat(segment.at(null).value).isEqualTo("acme/letters")
    }

    @Test
    fun `key at a release number names that release, read-only, and is written back as it was`() {
        val segment = CatalogSegment.parse("acme/letters@1.2.0")

        assertThat(segment.released).isTrue()
        assertThat(segment.ref(tenant)?.draft).isEqualTo(DraftRef.Released("1.2.0"))
        assertThat(segment.value).isEqualTo("acme/letters@1.2.0")
        assertThat(CatalogSegment.parse("acme/letters@1.2.1-acme.2").ref(tenant)?.draft).`as`("a patch number").isEqualTo(DraftRef.Released("1.2.1-acme.2"))
        assertThat(CatalogSegment.parse("acme/letters@$work").released).isFalse()
    }

    @Test
    fun `anything that is neither a draft id nor a release number, or a bad key, is refused`() {
        assertThatThrownBy { CatalogSegment.parse("letters@2.0") }.`as`("not a release number").isInstanceOf(CatalogReleaseNotFoundException::class.java)
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
