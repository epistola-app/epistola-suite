// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.Banner
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Configuration

/**
 * Proves the split-out Keycloak OAuth2 registration — now a profile-gated document inside
 * `application-local.yaml` — activates **only** under `local,keycloak`, and in particular that
 * `keycloak` on its own does nothing (its config file `application-keycloak.yaml` no longer exists,
 * and `application-local.yaml` is only loaded when `local` is active).
 *
 * Uses a bare `@Configuration` source (no auto-configuration, so no DataSource / web server) and
 * only inspects the resolved `Environment` — the config-data profile logic is what is under test.
 */
@Tag("unit")
class KeycloakProfileActivationTest {

    @Configuration(proxyBeanMethods = false)
    class EmptyConfig

    private fun keycloakClientId(vararg profiles: String): String? {
        val ctx = SpringApplicationBuilder(EmptyConfig::class.java)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .profiles(*profiles)
            .run()
        return ctx.use {
            it.environment.getProperty("spring.security.oauth2.client.registration.keycloak.client-id")
        }
    }

    @Test
    fun `keycloak profile alone does NOT load the registration`() {
        assertThat(keycloakClientId("keycloak")).isNull()
    }

    @Test
    fun `local profile alone does NOT load the registration`() {
        assertThat(keycloakClientId("local")).isNull()
    }

    @Test
    fun `local and keycloak together load the registration`() {
        assertThat(keycloakClientId("local", "keycloak")).isEqualTo("epistola-suite")
    }
}
