// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.htmx

import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A catalog in a UI route, optionally one of its drafts: `letters` or `letters@2.0.0`. */
class CatalogSegmentTest {

    private val tenant = TenantKey.of("acme")

    @Test
    fun `a plain key names the catalog's current version`() {
        val segment = CatalogSegment.parse("letters")

        assertThat(segment.key).isEqualTo(CatalogKey.of("letters"))
        assertThat(segment.version).isNull()
        assertThat(segment.ref(tenant)).isNull()
        assertThat(segment.value).isEqualTo("letters")
    }

    @Test
    fun `key at version names that draft`() {
        val segment = CatalogSegment.parse("letters@2.0.0")

        assertThat(segment.key).isEqualTo(CatalogKey.of("letters"))
        assertThat(segment.ref(tenant)).isEqualTo(CatalogVersionRef(tenant, CatalogKey.of("letters"), "2.0.0"))
        assertThat(segment.value).isEqualTo("letters@2.0.0")
        assertThat(segment.at(null).value).isEqualTo("letters")
    }

    @Test
    fun `a version that is not SemVer, or a bad key, is refused`() {
        assertThatThrownBy { CatalogSegment.parse("letters@latest") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CatalogSegment.parse("Bad Key@1.0.0") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(CatalogSegment.parseOrNull("letters@")).isNull()
        assertThat(CatalogSegment.parseOrNull("  ")).isNull()
    }

    @Test
    fun `a resource of the same catalog links into the draft, another catalog's plainly`() {
        val segment = CatalogSegment.parse("letters@2.0.0")

        assertThat(segment.linkFor(CatalogKey.of("letters"))).isEqualTo("letters@2.0.0")
        assertThat(segment.linkFor("system")).isEqualTo("system")
    }
}
