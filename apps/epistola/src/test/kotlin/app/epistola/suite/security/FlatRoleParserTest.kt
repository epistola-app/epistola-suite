// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class FlatRoleParserTest {

    @Test
    fun `parses per-tenant roles from ept-prefixed entries`() {
        val result = parseFlatRoles(
            listOf("ept_acme-corp_content-viewer", "ept_acme-corp_content-author"),
        )

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_AUTHOR)
    }

    @Test
    fun `parses multiple tenants`() {
        val result = parseFlatRoles(
            listOf("ept_acme-corp_content-viewer", "ept_beta-org_tenant-administrator"),
        )

        assertThat(result.tenantRoles).hasSize(2)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")]).containsExactly(TenantRole.CONTENT_VIEWER)
        assertThat(result.tenantRoles[TenantKey.of("beta-org")]).containsExactly(TenantRole.TENANT_ADMINISTRATOR)
    }

    @Test
    fun `parses global roles from epg-prefixed entries`() {
        val result = parseFlatRoles(listOf("epg_content-viewer", "epg_content-author"))

        assertThat(result.globalRoles).containsExactlyInAnyOrder(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_AUTHOR)
        assertThat(result.tenantRoles).isEmpty()
    }

    @Test
    fun `parses platform roles from eps-prefixed entries normalising underscore to hyphen`() {
        val result = parseFlatRoles(listOf("eps_tenant_manager"))

        assertThat(result.platformRoles).containsExactly(PlatformRole.TENANT_MANAGER)
    }

    @Test
    fun `accepts hyphen form for platform roles too`() {
        val result = parseFlatRoles(listOf("eps_tenant-manager"))

        assertThat(result.platformRoles).containsExactly(PlatformRole.TENANT_MANAGER)
    }

    @Test
    fun `ignores entries without a recognised prefix`() {
        val result = parseFlatRoles(
            listOf("admin", "user", "ROLE_USER", "epistola_reader", "ept_acme-corp_content-viewer"),
        )

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")]).containsExactly(TenantRole.CONTENT_VIEWER)
        assertThat(result.globalRoles).isEmpty()
        assertThat(result.platformRoles).isEmpty()
    }

    @Test
    fun `ignores unknown tenant role`() {
        val result = parseFlatRoles(listOf("ept_acme-corp_superadmin", "ept_acme-corp_content-viewer"))

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")]).containsExactly(TenantRole.CONTENT_VIEWER)
    }

    @Test
    fun `ignores unknown global role`() {
        val result = parseFlatRoles(listOf("epg_overlord"))

        assertThat(result.globalRoles).isEmpty()
    }

    @Test
    fun `ignores unknown platform role`() {
        val result = parseFlatRoles(listOf("eps_global_admin"))

        assertThat(result.platformRoles).isEmpty()
    }

    @Test
    fun `ignores ept entries with wrong segment count`() {
        val result = parseFlatRoles(
            listOf(
                "ept_reader", // missing tenant
                "ept_acme-corp_reader_extra", // too many segments
                "ept_acme-corp_content-viewer", // valid
            ),
        )

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")]).containsExactly(TenantRole.CONTENT_VIEWER)
    }

    @Test
    fun `ignores ept entries with invalid tenant keys`() {
        val result = parseFlatRoles(
            listOf("ept_INVALID_content-viewer", "ept_acme-corp_content-viewer"),
        )

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")]).containsExactly(TenantRole.CONTENT_VIEWER)
    }

    @Test
    fun `returns empty for empty input`() {
        val result = parseFlatRoles(emptyList())

        assertThat(result.tenantRoles).isEmpty()
        assertThat(result.globalRoles).isEmpty()
        assertThat(result.platformRoles).isEmpty()
    }

    @Test
    fun `full example mixing all three prefixes`() {
        val result = parseFlatRoles(
            listOf(
                "ept_acme-corp_content-viewer",
                "ept_acme-corp_content-author",
                "epg_content-viewer",
                "eps_tenant_manager",
                "ROLE_OTHER",
            ),
        )

        assertThat(result.tenantRoles[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_AUTHOR)
        assertThat(result.globalRoles).containsExactly(TenantRole.CONTENT_VIEWER)
        assertThat(result.platformRoles).containsExactly(PlatformRole.TENANT_MANAGER)
    }

    @Test
    fun `handles all four tenant roles via flat encoding`() {
        val result = parseFlatRoles(
            listOf(
                "ept_acme-corp_content-viewer",
                "ept_acme-corp_content-author",
                "ept_acme-corp_document-generator",
                "ept_acme-corp_tenant-administrator",
            ),
        )

        assertThat(result.tenantRoles[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(
                TenantRole.CONTENT_VIEWER,
                TenantRole.CONTENT_AUTHOR,
                TenantRole.DOCUMENT_GENERATOR,
                TenantRole.TENANT_ADMINISTRATOR,
            )
    }
}
