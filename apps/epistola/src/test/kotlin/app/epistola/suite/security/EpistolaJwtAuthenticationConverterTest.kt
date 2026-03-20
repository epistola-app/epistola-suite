package app.epistola.suite.security

import app.epistola.suite.security.PlatformRole
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
    fun `extracts platform roles from resource_access`() {
        val token = converter.convert(
            jwt(
                mapOf(
                    "resource_access" to mapOf(
                        "epistola-suite" to mapOf(
                            "roles" to listOf("tenant-manager"),
                        ),
                    ),
                ),
            ),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.platformRoles).containsExactly(PlatformRole.TENANT_MANAGER)
    }

    @Test
    fun `platform roles empty when no resource_access claim`() {
        val token = converter.convert(jwt(emptyMap()))

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.platformRoles).isEmpty()
    }

    @Test
    fun `platform roles empty when client not in resource_access`() {
        val token = converter.convert(
            jwt(
                mapOf(
                    "resource_access" to mapOf(
                        "other-client" to mapOf("roles" to listOf("some-role")),
                    ),
                ),
            ),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.platformRoles).isEmpty()
    }

    @Test
    fun `extracts composable tenant roles from roles array`() {
        val token = converter.convert(
            jwt(
                mapOf(
                    "epistola_tenants" to listOf(
                        mapOf("id" to "acme-corp", "roles" to listOf("reader", "editor")),
                        mapOf("id" to "beta-org", "roles" to listOf("reader", "generator")),
                    ),
                ),
            ),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.tenantMemberships).hasSize(2)
        assertThat(principal.tenantMemberships.values.first()).containsExactlyInAnyOrder(
            TenantRole.READER,
            TenantRole.EDITOR,
        )
        assertThat(principal.tenantMemberships.values.last()).containsExactlyInAnyOrder(
            TenantRole.READER,
            TenantRole.GENERATOR,
        )
    }

    @Test
    fun `extracts legacy single role format`() {
        val token = converter.convert(
            jwt(
                mapOf(
                    "epistola_tenants" to listOf(
                        mapOf("id" to "acme-corp", "role" to "MANAGER"),
                    ),
                ),
            ),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.tenantMemberships).hasSize(1)
        assertThat(principal.tenantMemberships.values.first()).containsExactly(TenantRole.MANAGER)
    }

    @Test
    fun `extracts legacy flat tenant list defaulting to READER`() {
        val token = converter.convert(
            jwt(
                mapOf(
                    "epistola_tenants" to listOf("acme-corp", "beta-org"),
                ),
            ),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.tenantMemberships).hasSize(2)
        assertThat(principal.tenantMemberships.values).allMatch { it == setOf(TenantRole.READER) }
    }

    @Test
    fun `platform roles added as Spring Security authorities`() {
        val token = converter.convert(
            jwt(
                mapOf(
                    "resource_access" to mapOf(
                        "epistola-suite" to mapOf(
                            "roles" to listOf("tenant-manager"),
                        ),
                    ),
                ),
            ),
        )

        val authorityNames = token.authorities.map { it.authority }
        assertThat(authorityNames).contains("ROLE_TENANT_MANAGER")
    }

    @Test
    fun `unknown tenant roles are ignored`() {
        val token = converter.convert(
            jwt(
                mapOf(
                    "epistola_tenants" to listOf(
                        mapOf("id" to "acme-corp", "roles" to listOf("reader", "unknown-role")),
                    ),
                ),
            ),
        )

        val principal = (token as JwtAuthenticationToken).details as EpistolaPrincipal
        assertThat(principal.tenantMemberships.values.first()).containsExactly(TenantRole.READER)
    }
}
