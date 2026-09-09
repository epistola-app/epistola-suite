// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.common.ids.TenantKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.security.MessageDigest

/**
 * Pure tests for the email → tenant-key rule. No Spring, no database: the derivation is a companion
 * function precisely so the rules can be pinned down cheaply and exhaustively, leaving
 * [DemoLoginMembershipResolverTest] to cover only what needs a database.
 */
@Tag("unit")
class DemoTenantKeyDerivationTest {

    private fun keyOf(email: String): TenantKey? = DemoLoginMembershipResolver.deriveTenantKeyFromEmail(email)

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "sander@degroot.dev,   sander-665cdb",
        "user@acme-corp.io,    user-1f5dd7",
        "j.doe+test@acme.io,   j-doe-test-6f7f03",
        "a@b.co,               a-80305c",
    )
    fun `is the local part plus a hash of the whole address`(email: String, expected: String) {
        assertThat(keyOf(email)?.value).isEqualTo(expected)
    }

    @Test
    fun `the hash is plain sha256 of the lowercased address, reproducible outside the app`() {
        // Documented in docs/auth.md so an operator can map an address to its tenant from a shell.
        // No salt and no installation-specific input, on purpose — this is an identifier, not a
        // credential. If this test has to change, that documentation has to change with it.
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("sander@degroot.dev".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(6)

        assertThat(keyOf("  Sander@Degroot.dev  ")?.value).isEqualTo("sander-$expected")
    }

    @Test
    fun `is stable across calls`() {
        assertThat(keyOf("sander@degroot.dev")).isEqualTo(keyOf("sander@degroot.dev"))
    }

    @Test
    fun `prefixes a label that cannot lead a tenant key`() {
        // TenantKey must start with a letter, and the label leads, so it is the only part that can
        // break the rule.
        assertThat(keyOf("1st@acme.io")!!.value).startsWith("u-1st-")
    }

    @Test
    fun `separates addresses that share a local part`() {
        assertThat(keyOf("sander@a.com")).isNotEqualTo(keyOf("sander@b.com"))
    }

    @Test
    fun `separates addresses whose local parts slugify alike`() {
        // The reason every key is hashed rather than only the colliding ones: these two reduce to
        // the same readable label.
        val plus = keyOf("j.doe+test@acme.io")!!
        val dot = keyOf("j.doe.test@acme.io")!!

        assertThat(plus).isNotEqualTo(dot)
        assertThat(plus.value).startsWith("j-doe-test-")
        assertThat(dot.value).startsWith("j-doe-test-")
    }

    @Test
    fun `needs no special case for a reserved word`() {
        // "admin" is reserved on its own; suffixed by the hash it is an ordinary key.
        assertThat(keyOf("admin@acme.io")!!.value).isEqualTo("admin-94039b")
    }

    @Test
    fun `needs no special case for a local part with nothing to slugify`() {
        // Would otherwise have to collapse to the domain — putting every such user in one tenant —
        // or be turned away, leaving them logged in with no tenant at all. Here it is just a key
        // with no label.
        val nihon = keyOf("日本@example.jp")!!
        val kanji = keyOf("漢字@example.jp")!!

        assertThat(nihon.value).isEqualTo("u-6196c7")
        assertThat(nihon).isNotEqualTo(kanji)
    }

    @Test
    fun `splits on the first at sign`() {
        // Only the local part becomes the label, and only the first `@` separates it.
        assertThat(keyOf("a@b@c.com")!!.value).isEqualTo("a-a2f315")
    }

    @Test
    fun `re-trims a truncation that lands on a hyphen`() {
        // Load-bearing, and the reason the documented recipe says to trim *after* truncating:
        // without it the key would carry `--` and TenantKey would reject it outright, leaving the
        // user with no tenant.
        val key = keyOf("${"x".repeat(55)}-yyyy@acme.io")!!

        assertThat(key.value).doesNotContain("--")
        assertThat(key.value).isEqualTo("${"x".repeat(55)}-9a6b15")
    }

    @Test
    fun `counts the fallback prefix against the length cap`() {
        // The stem is truncated after prefixing, so `u-` cannot push the key over 63.
        val key = keyOf("1${"x".repeat(60)}@acme.io")!!

        assertThat(key.value).startsWith("u-1x")
        assertThat(key.value).hasSize(63)
    }

    @Test
    fun `truncates a long local part to a valid key`() {
        val key = keyOf("${"a".repeat(120)}@${"b".repeat(80)}.io")!!

        assertThat(key.value.length).isLessThanOrEqualTo(63)
        assertThat(key.value).doesNotEndWith("-")
    }

    @ValueSource(strings = ["not-an-email", "@acme.io", "user@", "@", ""])
    @ParameterizedTest
    fun `declines a string that is not an address at all`(email: String) {
        assertThat(keyOf(email)).isNull()
    }
}
