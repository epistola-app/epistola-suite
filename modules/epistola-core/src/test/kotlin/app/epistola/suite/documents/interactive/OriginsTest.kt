// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OriginsTest {

    @Test
    fun `the origin of a URL is its scheme, host and non-default port, lowercased`() {
        assertThat(Origins.of("https://Cases.Example.org/z/1?a=b#c")).isEqualTo("https://cases.example.org")
        assertThat(Origins.of("https://cases.example.org:443/x")).isEqualTo("https://cases.example.org")
        assertThat(Origins.of("http://localhost:4000/x")).isEqualTo("http://localhost:4000")
        assertThat(Origins.of("http://cases.example.org:80")).isEqualTo("http://cases.example.org")
    }

    @Test
    fun `anything that is not an absolute http URL with a host has no origin`() {
        assertThat(Origins.of("javascript:alert(1)")).isNull()
        assertThat(Origins.of("//cases.example.org/x")).isNull()
        assertThat(Origins.of("/relative")).isNull()
        assertThat(Origins.of("ftp://cases.example.org")).isNull()
        // Credentials in the authority are a classic way to disguise the real host.
        assertThat(Origins.of("https://cases.example.org@evil.example/x")).isNull()
    }

    @Test
    fun `a look-alike host is a different origin`() {
        assertThat(Origins.of("https://cases.example.org.evil.example/x")).isNotEqualTo("https://cases.example.org")
    }

    @Test
    fun `a configured origin is normalised, and refused when it carries a path or query`() {
        assertThat(Origins.normalise("https://Cases.Example.org/")).isEqualTo("https://cases.example.org")
        assertThat(Origins.normalise("https://cases.example.org/cases")).isNull()
        assertThat(Origins.normalise("https://cases.example.org?x=1")).isNull()
    }
}
