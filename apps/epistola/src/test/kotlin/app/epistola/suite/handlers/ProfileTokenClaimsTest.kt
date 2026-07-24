// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

@Tag("unit")
class ProfileTokenClaimsTest {

    @Test
    fun `extracts the curated scalar claims in order`() {
        val rows = ProfileTokenClaims.extract(
            mapOf(
                "sub" to "abc-123",
                "preferred_username" to "jdoe",
                "email" to "jdoe@example.com",
                "name" to "Jane Doe",
                "iss" to "https://idp.example.com/realms/epistola",
                "azp" to "epistola-suite",
            ),
            flatRolesClaimName = "roles",
        )

        assertThat(rows.map { it.claim })
            .containsExactly("sub", "preferred_username", "email", "name", "iss", "azp")
        assertThat(rows.first { it.claim == "email" }.value).isEqualTo("jdoe@example.com")
    }

    @Test
    fun `renders iat and exp human-readable in UTC`() {
        // 2026-06-18T10:30:00Z
        val iat = Instant.parse("2026-06-18T10:30:00Z")
        val exp = Instant.parse("2026-06-18T11:30:00Z")

        val rows = ProfileTokenClaims.extract(
            mapOf("iat" to iat, "exp" to exp),
            flatRolesClaimName = "roles",
        )

        assertThat(rows.first { it.claim == "iat" }.value).isEqualTo("2026-06-18 10:30:00 UTC")
        assertThat(rows.first { it.claim == "exp" }.value).isEqualTo("2026-06-18 11:30:00 UTC")
    }

    @Test
    fun `renders epoch-second timestamps human-readable`() {
        val rows = ProfileTokenClaims.extract(
            mapOf("exp" to Instant.parse("2026-06-18T11:30:00Z").epochSecond),
            flatRolesClaimName = "roles",
        )

        assertThat(rows.first { it.claim == "exp" }.value).isEqualTo("2026-06-18 11:30:00 UTC")
    }

    @Test
    fun `joins list claims like aud and groups`() {
        val rows = ProfileTokenClaims.extract(
            mapOf(
                "aud" to listOf("epistola-suite", "account"),
                "groups" to listOf("/epistola/tenants/acme-corp/reader", "/epistola/global/editor"),
            ),
            flatRolesClaimName = "roles",
        )

        assertThat(rows.first { it.claim == "aud" }.value).isEqualTo("epistola-suite, account")
        assertThat(rows.first { it.claim == "groups" }.value)
            .isEqualTo("/epistola/tenants/acme-corp/reader, /epistola/global/editor")
    }

    @Test
    fun `renders the configured flat-roles claim under its own name`() {
        val rows = ProfileTokenClaims.extract(
            mapOf("myroles" to listOf("ept_acme-corp_reader", "epg_editor")),
            flatRolesClaimName = "myroles",
        )

        assertThat(rows.first { it.claim == "myroles" }.value)
            .isEqualTo("ept_acme-corp_reader, epg_editor")
    }

    @Test
    fun `omits absent claims`() {
        val rows = ProfileTokenClaims.extract(
            mapOf("sub" to "abc-123"),
            flatRolesClaimName = "roles",
        )

        assertThat(rows.map { it.claim }).containsExactly("sub")
    }

    @Test
    fun `never exposes the raw token value`() {
        val rows = ProfileTokenClaims.extract(
            mapOf(
                "sub" to "abc-123",
                // Even if a raw token were somehow present in the claims map, it is not curated.
                "id_token" to "eyJraWQ.SECRET.SIGNATURE",
                "access_token" to "eyJhbGci.SECRET.SIGNATURE",
            ),
            flatRolesClaimName = "roles",
        )

        assertThat(rows.map { it.claim }).containsExactly("sub")
        assertThat(rows).noneMatch { it.value.contains("SECRET") }
    }
}
