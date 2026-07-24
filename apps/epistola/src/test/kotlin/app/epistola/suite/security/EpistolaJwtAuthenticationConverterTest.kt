// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant

@Tag("unit")
class EpistolaJwtAuthenticationConverterTest {

    private val converter = EpistolaJwtAuthenticationConverter(AuthProperties())

    private fun jwt(claims: Map<String, Any>): Jwt {
        val builder = Jwt.withTokenValue("test-token")
            .header("alg", "RS256")
            .claim("sub", "test-subject")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))

        claims.forEach { (key, value) -> builder.claim(key, value) }
        return builder.build()
    }

    @Test
    fun `extracts per-tenant roles from groups claim`() {
        val token = converter.convert(
            jwt(
                mapOf(
                    "groups" to listOf(
                        "/epistola/tenants/acme-corp/content-viewer",
                        "/epistola/tenants/acme-corp/content-author",
                        "/epistola/tenants/beta-org/content-viewer",
                        "/epistola/tenants/beta-org/document-generator",
                    ),
                ),
            ),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.tenantMemberships).hasSize(2)
        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_AUTHOR)
        assertThat(principal.tenantMemberships[TenantKey.of("beta-org")])
            .containsExactlyInAnyOrder(TenantRole.CONTENT_VIEWER, TenantRole.DOCUMENT_GENERATOR)
    }

    @Test
    fun `extracts global roles from groups claim`() {
        val token = converter.convert(
            jwt(mapOf("groups" to listOf("/epistola/global/content-viewer", "/epistola/global/content-author"))),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.globalRoles).containsExactlyInAnyOrder(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_AUTHOR)
    }

    @Test
    fun `extracts platform roles from groups claim`() {
        val token = converter.convert(
            jwt(mapOf("groups" to listOf("/epistola/platform/tenant-manager"))),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.platformRoles).containsExactly(PlatformRole.TENANT_MANAGER)
    }

    @Test
    fun `platform roles added as Spring Security authorities`() {
        val token = converter.convert(
            jwt(mapOf("groups" to listOf("/epistola/platform/tenant-manager"))),
        )

        val authorityNames = token.authorities.map { it.authority }
        assertThat(authorityNames).contains("ROLE_TENANT_MANAGER")
    }

    @Test
    fun `empty memberships when no groups claim`() {
        val token = converter.convert(jwt(emptyMap()))

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.tenantMemberships).isEmpty()
        assertThat(principal.globalRoles).isEmpty()
        assertThat(principal.platformRoles).isEmpty()
    }

    @Test
    fun `ignores non-epistola groups`() {
        val token = converter.convert(
            jwt(
                mapOf(
                    "groups" to listOf("/other-app/group", "admin", "/epistola/tenants/acme-corp/content-viewer"),
                ),
            ),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.tenantMemberships).hasSize(1)
        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")])
            .containsExactly(TenantRole.CONTENT_VIEWER)
    }

    @Test
    fun `unknown tenant roles are ignored`() {
        val token = converter.convert(
            jwt(
                mapOf(
                    "groups" to listOf("/epistola/tenants/acme-corp/content-viewer", "/epistola/tenants/acme-corp/superadmin"),
                ),
            ),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")])
            .containsExactly(TenantRole.CONTENT_VIEWER)
    }

    @Test
    fun `full example with per-tenant, global, and platform roles`() {
        val token = converter.convert(
            jwt(
                mapOf(
                    "groups" to listOf(
                        "/epistola/tenants/acme-corp/content-viewer",
                        "/epistola/tenants/acme-corp/content-author",
                        "/epistola/global/content-viewer",
                        "/epistola/platform/tenant-manager",
                        "/other-app/group",
                    ),
                ),
            ),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_AUTHOR)
        assertThat(principal.globalRoles).containsExactly(TenantRole.CONTENT_VIEWER)
        assertThat(principal.platformRoles).containsExactly(PlatformRole.TENANT_MANAGER)
    }

    @Test
    fun `extracts memberships from flat roles claim alone`() {
        val token = converter.convert(
            jwt(
                mapOf(
                    "roles" to listOf(
                        "ept_acme-corp_content-viewer",
                        "ept_acme-corp_content-author",
                        "epg_document-generator",
                        "eps_tenant_manager",
                    ),
                ),
            ),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_AUTHOR)
        assertThat(principal.globalRoles).containsExactly(TenantRole.DOCUMENT_GENERATOR)
        assertThat(principal.platformRoles).containsExactly(PlatformRole.TENANT_MANAGER)
    }

    @Test
    fun `merges groups and flat-roles claim into a single principal`() {
        val token = converter.convert(
            jwt(
                mapOf(
                    "groups" to listOf("/epistola/tenants/acme-corp/content-viewer"),
                    "roles" to listOf("ept_acme-corp_content-author", "epg_document-generator"),
                ),
            ),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_AUTHOR)
        assertThat(principal.globalRoles).containsExactly(TenantRole.DOCUMENT_GENERATOR)
    }

    @Test
    fun `honours a custom flat-roles claim name`() {
        val customConverter = EpistolaJwtAuthenticationConverter(
            AuthProperties(flatRoles = FlatRolesProperties(claimName = "myroles")),
        )

        val token = customConverter.convert(
            jwt(mapOf("myroles" to listOf("ept_acme-corp_tenant-administrator"))),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")])
            .containsExactly(TenantRole.TENANT_ADMINISTRATOR)
    }

    @Test
    fun `ignores flat-roles claim when it uses the wrong configured name`() {
        // Default config reads "roles" — strings in "myroles" should be ignored.
        val token = converter.convert(jwt(mapOf("myroles" to listOf("ept_acme-corp_content-viewer"))))

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.tenantMemberships).isEmpty()
    }
}
