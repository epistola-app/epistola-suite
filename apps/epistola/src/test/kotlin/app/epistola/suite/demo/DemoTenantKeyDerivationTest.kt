// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Pure tests for the email → tenant-key rule. No Spring, no database: the derivation is a companion
 * function precisely so the slug rules can be pinned down cheaply and exhaustively, leaving
 * [DemoLoginMembershipResolverTest] to cover only what needs a database.
 */
@Tag("unit")
class DemoTenantKeyDerivationTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "sander@degroot.dev,        sander-degroot-dev",
        "user@acme-corp.io,         user-acme-corp-io",
        "j.doe+test@acme.io,        j-doe-test-acme-io",
        "first_last@my.company.co.uk, first-last-my-company-co-uk",
        "a@b.co,                    a-b-co",
    )
    fun `slugifies the whole address`(email: String, expected: String) {
        assertThat(DemoLoginMembershipResolver.deriveTenantKeyFromEmail(email)?.value).isEqualTo(expected)
    }

    @Test
    fun `prefixes addresses that would not start with a letter`() {
        // TenantKey requires a leading letter; the address is otherwise perfectly usable.
        assertThat(DemoLoginMembershipResolver.deriveTenantKeyFromEmail("1st@acme.io")?.value)
            .isEqualTo("u-1st-acme-io")
    }

    @Test
    fun `keeps a reserved word usable once the domain is appended`() {
        // "admin" is reserved; "admin-acme-io" is not, so there is nothing to work around.
        assertThat(DemoLoginMembershipResolver.deriveTenantKeyFromEmail("admin@acme.io")?.value)
            .isEqualTo("admin-acme-io")
    }

    @ValueSource(strings = ["not-an-email", "@acme.io", "user@", "@", ""])
    @ParameterizedTest
    fun `declines an address with nothing on one side of the at sign`(email: String) {
        assertThat(DemoLoginMembershipResolver.deriveTenantKeyFromEmail(email)).isNull()
    }

    @Test
    fun `declines an address whose local part slugifies to nothing`() {
        // Would otherwise collapse to the bare domain and hand every such user the same tenant —
        // the exact failure the per-user change exists to remove.
        assertThat(DemoLoginMembershipResolver.deriveTenantKeyFromEmail("日本@example.jp")).isNull()
        assertThat(DemoLoginMembershipResolver.deriveTenantKeyFromEmail("+@acme.io")).isNull()
    }

    @Test
    fun `truncates an over-long address to a valid key`() {
        val key = DemoLoginMembershipResolver.deriveTenantKeyFromEmail("${"a".repeat(80)}@${"b".repeat(80)}.io")

        assertThat(key).isNotNull()
        assertThat(key!!.value.length).isLessThanOrEqualTo(63)
        assertThat(key.value).doesNotEndWith("-")
    }

    @Test
    fun `the hashed fallback is stable, distinct per address, and readable`() {
        val one = DemoLoginMembershipResolver.hashedTenantKeyForEmail("j.doe+test@acme.io")
        val again = DemoLoginMembershipResolver.hashedTenantKeyForEmail("j.doe+test@acme.io")
        val other = DemoLoginMembershipResolver.hashedTenantKeyForEmail("j.doe.test@acme.io")

        assertThat(one).isNotNull().isEqualTo(again)
        // The two addresses share a plain slug; the fallback is what stops them sharing a sandbox.
        assertThat(DemoLoginMembershipResolver.deriveTenantKeyFromEmail("j.doe+test@acme.io"))
            .isEqualTo(DemoLoginMembershipResolver.deriveTenantKeyFromEmail("j.doe.test@acme.io"))
        assertThat(one).isNotEqualTo(other)
        assertThat(one!!.value).startsWith("j-doe-test-acme-io-")
    }

    @Test
    fun `the hashed fallback stays within the key length limit`() {
        val key = DemoLoginMembershipResolver.hashedTenantKeyForEmail("${"a".repeat(80)}@${"b".repeat(80)}.io")

        assertThat(key).isNotNull()
        assertThat(key!!.value.length).isLessThanOrEqualTo(63)
    }
}
