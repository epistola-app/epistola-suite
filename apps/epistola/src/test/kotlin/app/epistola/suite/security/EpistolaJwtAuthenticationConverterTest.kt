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

    private val converter = EpistolaJwtAuthenticationConverter()

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
                        "/epistola/tenants/acme-corp/reader",
                        "/epistola/tenants/acme-corp/editor",
                        "/epistola/tenants/beta-org/reader",
                        "/epistola/tenants/beta-org/generator",
                    ),
                ),
            ),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.tenantMemberships).hasSize(2)
        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(TenantRole.READER, TenantRole.EDITOR)
        assertThat(principal.tenantMemberships[TenantKey.of("beta-org")])
            .containsExactlyInAnyOrder(TenantRole.READER, TenantRole.GENERATOR)
    }

    @Test
    fun `extracts global roles from groups claim`() {
        val token = converter.convert(
            jwt(mapOf("groups" to listOf("/epistola/global/reader", "/epistola/global/editor"))),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.globalRoles).containsExactlyInAnyOrder(TenantRole.READER, TenantRole.EDITOR)
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
                    "groups" to listOf("/other-app/group", "admin", "/epistola/tenants/acme-corp/reader"),
                ),
            ),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.tenantMemberships).hasSize(1)
        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")])
            .containsExactly(TenantRole.READER)
    }

    @Test
    fun `unknown tenant roles are ignored`() {
        val token = converter.convert(
            jwt(
                mapOf(
                    "groups" to listOf("/epistola/tenants/acme-corp/reader", "/epistola/tenants/acme-corp/superadmin"),
                ),
            ),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")])
            .containsExactly(TenantRole.READER)
    }

    @Test
    fun `full example with per-tenant, global, and platform roles`() {
        val token = converter.convert(
            jwt(
                mapOf(
                    "groups" to listOf(
                        "/epistola/tenants/acme-corp/reader",
                        "/epistola/tenants/acme-corp/editor",
                        "/epistola/global/reader",
                        "/epistola/platform/tenant-manager",
                        "/other-app/group",
                    ),
                ),
            ),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.tenantMemberships[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(TenantRole.READER, TenantRole.EDITOR)
        assertThat(principal.globalRoles).containsExactly(TenantRole.READER)
        assertThat(principal.platformRoles).containsExactly(PlatformRole.TENANT_MANAGER)
    }
}
