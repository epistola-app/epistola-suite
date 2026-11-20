// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * An allowed start of a return URL: the same origin, and a path under the entry's at a `/` — never a
 * plain string prefix, which look-alike hosts, sibling paths and dot segments would get past.
 */
class ReturnUrlPrefixTest {

    private fun prefix(entry: String) = ReturnUrlPrefix.parse(entry)!!

    @Test
    fun `an entry is normalised to its origin and a path without a trailing slash`() {
        assertThat(prefix("https://Cases.Example.org:443/zaken/").toString()).isEqualTo("https://cases.example.org/zaken")
        assertThat(prefix("https://cases.example.org/").toString()).isEqualTo("https://cases.example.org")
        assertThat(prefix("http://localhost:4000/a/./b/../c").toString()).isEqualTo("http://localhost:4000/a/c")
    }

    @Test
    fun `an entry with a query, fragment, credentials or another scheme is refused`() {
        assertThat(ReturnUrlPrefix.parse("https://cases.example.org/zaken?x=1")).isNull()
        assertThat(ReturnUrlPrefix.parse("https://cases.example.org/zaken#top")).isNull()
        assertThat(ReturnUrlPrefix.parse("https://user@cases.example.org/")).isNull()
        assertThat(ReturnUrlPrefix.parse("ftp://cases.example.org/")).isNull()
        assertThat(ReturnUrlPrefix.parse("/zaken")).isNull()
        assertThat(ReturnUrlPrefix.parse("cases.example.org")).isNull()
    }

    @Test
    fun `an origin alone allows every path on it`() {
        val origin = prefix("https://cases.example.org")

        assertThat(origin.matches("https://cases.example.org")).isTrue()
        assertThat(origin.matches("https://cases.example.org/anything/at/all?x=1#y")).isTrue()
    }

    @Test
    fun `a path allows itself and what lies under it, not a sibling that shares its letters`() {
        val zaken = prefix("https://cases.example.org/zaken")

        assertThat(zaken.matches("https://cases.example.org/zaken")).isTrue()
        assertThat(zaken.matches("https://cases.example.org/zaken/")).isTrue()
        assertThat(zaken.matches("https://cases.example.org/zaken/Z-1?tab=docs")).isTrue()
        assertThat(zaken.matches("https://cases.example.org/zakenoverzicht")).isFalse()
        assertThat(zaken.matches("https://cases.example.org/")).isFalse()
        assertThat(zaken.matches("https://cases.example.org/admin")).isFalse()
    }

    @Test
    fun `a look-alike host, another port or another scheme is another origin`() {
        val zaken = prefix("https://cases.example.org/zaken")

        assertThat(zaken.matches("https://cases.example.org.evil.example/zaken")).isFalse()
        assertThat(zaken.matches("https://cases.example.org:8443/zaken")).isFalse()
        assertThat(zaken.matches("http://cases.example.org/zaken")).isFalse()
        assertThat(zaken.matches("https://cases.example.org@evil.example/zaken")).isFalse()
        // The classic hole in a string prefix: an origin alone, followed by more host.
        assertThat(prefix("https://cases.example.org").matches("https://cases.example.org.evil.example/")).isFalse()
        assertThat(prefix("http://localhost:4000").matches("http://localhost:40001/")).isFalse()
    }

    @Test
    fun `dot segments are resolved before matching, and hidden ones never match`() {
        val zaken = prefix("https://cases.example.org/zaken")

        assertThat(zaken.matches("https://cases.example.org/zaken/../admin")).isFalse()
        assertThat(zaken.matches("https://cases.example.org/zaken/./Z-1")).isTrue()
        assertThat(zaken.matches("https://cases.example.org/zaken/%2e%2e/admin")).isFalse()
        assertThat(zaken.matches("https://cases.example.org/zaken%2f..%2fadmin")).isFalse()
        assertThat(zaken.matches("https://cases.example.org/zaken/..%5cadmin")).isFalse()
        assertThat(prefix("https://cases.example.org").matches("https://cases.example.org/../../etc")).isFalse()
    }
}
