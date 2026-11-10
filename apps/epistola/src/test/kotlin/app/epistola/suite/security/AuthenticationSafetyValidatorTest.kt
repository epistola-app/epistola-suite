// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.core.env.StandardEnvironment
import org.springframework.mock.env.MockEnvironment
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType

class AuthenticationSafetyValidatorTest {

    private fun mockUserDetailsService(): UserDetailsService = UserDetailsService { username ->
        User.withUsername(username).password("pass").authorities(emptyList()).build()
    }

    private fun mockClientRegistrationRepository(): ClientRegistrationRepository = InMemoryClientRegistrationRepository(
        ClientRegistration.withRegistrationId("keycloak")
            .clientId("test")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .authorizationUri("http://localhost/auth")
            .tokenUri("http://localhost/token")
            .build(),
    )

    private fun envWith(vararg profiles: String): MockEnvironment {
        val env = MockEnvironment()
        env.setActiveProfiles(*profiles)
        return env
    }

    @Test
    fun `local profile with prod throws`() {
        val validator = AuthenticationSafetyValidator(
            environment = envWith("local", "prod"),
            userDetailsService = mockUserDetailsService(),
            clientRegistrationRepository = null,
        )

        assertThrows<IllegalStateException> {
            validator.afterSingletonsInstantiated()
        }
    }

    @Test
    fun `local and prod are mutually exclusive regardless of auth beans`() {
        // OAuth configured (no in-memory UserDetailsService), so this is caught by the explicit
        // local/prod exclusivity rule, not the in-memory-users check.
        val validator = AuthenticationSafetyValidator(
            environment = envWith("local", "prod"),
            userDetailsService = null,
            clientRegistrationRepository = mockClientRegistrationRepository(),
        )

        val ex = assertThrows<IllegalStateException> {
            validator.afterSingletonsInstantiated()
        }
        assertThat(ex.message).contains("mutually exclusive")
    }

    @Test
    fun `localauth profile with prod throws`() {
        val validator = AuthenticationSafetyValidator(
            environment = envWith("localauth", "prod"),
            userDetailsService = mockUserDetailsService(),
            clientRegistrationRepository = mockClientRegistrationRepository(),
        )

        assertThrows<IllegalStateException> {
            validator.afterSingletonsInstantiated()
        }
    }

    @Test
    fun `demo profile with prod and keycloak passes`() {
        val validator = AuthenticationSafetyValidator(
            environment = envWith("demo", "prod", "keycloak"),
            userDetailsService = null,
            clientRegistrationRepository = mockClientRegistrationRepository(),
        )

        assertDoesNotThrow {
            validator.afterSingletonsInstantiated()
        }
    }

    @Test
    fun `no auth beans throws`() {
        val validator = AuthenticationSafetyValidator(
            environment = envWith("default"),
            userDetailsService = null,
            clientRegistrationRepository = null,
        )

        assertThrows<IllegalStateException> {
            validator.afterSingletonsInstantiated()
        }
    }

    @Test
    fun `demo alone without auth throws`() {
        val validator = AuthenticationSafetyValidator(
            environment = envWith("demo"),
            userDetailsService = null,
            clientRegistrationRepository = null,
        )

        assertThrows<IllegalStateException> {
            validator.afterSingletonsInstantiated()
        }
    }

    @Test
    fun `local profile alone with UserDetailsService passes`() {
        val validator = AuthenticationSafetyValidator(
            environment = envWith("local"),
            userDetailsService = mockUserDetailsService(),
            clientRegistrationRepository = null,
        )

        assertDoesNotThrow {
            validator.afterSingletonsInstantiated()
        }
    }

    @Test
    fun `demo with keycloak passes`() {
        val validator = AuthenticationSafetyValidator(
            environment = envWith("demo", "keycloak"),
            userDetailsService = null,
            clientRegistrationRepository = mockClientRegistrationRepository(),
        )

        assertDoesNotThrow {
            validator.afterSingletonsInstantiated()
        }
    }

    @Test
    fun `localauth with keycloak passes`() {
        val validator = AuthenticationSafetyValidator(
            environment = envWith("localauth", "keycloak"),
            userDetailsService = mockUserDetailsService(),
            clientRegistrationRepository = mockClientRegistrationRepository(),
        )

        assertDoesNotThrow {
            validator.afterSingletonsInstantiated()
        }
    }

    @Test
    fun `prod with ClientRegistrationRepository passes`() {
        val validator = AuthenticationSafetyValidator(
            environment = envWith("prod"),
            userDetailsService = null,
            clientRegistrationRepository = mockClientRegistrationRepository(),
        )

        assertDoesNotThrow {
            validator.afterSingletonsInstantiated()
        }
    }

    @Test
    fun `both auth mechanisms present passes`() {
        val validator = AuthenticationSafetyValidator(
            environment = envWith("local", "keycloak"),
            userDetailsService = mockUserDetailsService(),
            clientRegistrationRepository = mockClientRegistrationRepository(),
        )

        assertDoesNotThrow {
            validator.afterSingletonsInstantiated()
        }
    }

    @Test
    fun `no active profiles with UserDetailsService passes`() {
        val validator = AuthenticationSafetyValidator(
            environment = StandardEnvironment(),
            userDetailsService = mockUserDetailsService(),
            clientRegistrationRepository = null,
        )

        assertDoesNotThrow {
            validator.afterSingletonsInstantiated()
        }
    }

    private fun localUsers(adminPassword: String, userPassword: String) = AuthProperties(
        localUsers = listOf(
            LocalUserProperties(username = "admin@local", password = adminPassword),
            LocalUserProperties(username = "user@local", password = userPassword),
        ),
    )

    private fun validator(authProperties: AuthProperties, vararg profiles: String) = AuthenticationSafetyValidator(
        environment = envWith(*profiles),
        userDetailsService = mockUserDetailsService(),
        clientRegistrationRepository = null,
        authProperties = authProperties,
    )

    @Test
    fun `localauth with a shipped password is refused outside local development`() {
        val ex = assertThrows<IllegalStateException> { validator(localUsers("admin", "s3cret-user"), "localauth").afterSingletonsInstantiated() }
        assertThat(ex.message).contains("admin@local").contains("LOCAL_AUTH_ADMIN_PASSWORD").doesNotContain("user@local")
        assertThrows<IllegalStateException> { validator(localUsers("s3cret-admin", "user"), "demo", "localauth").afterSingletonsInstantiated() }
    }

    @Test
    fun `localauth with passwords of its own starts`() {
        validator(localUsers("s3cret-admin", "s3cret-user"), "demo", "localauth").afterSingletonsInstantiated()
    }

    @Test
    fun `the shipped passwords are fine in local development`() {
        validator(localUsers("admin", "user"), "local", "localauth").afterSingletonsInstantiated()
    }

    @Test
    fun `the passwords refused are the ones the localauth profile falls back to`() {
        val profile = checkNotNull(javaClass.getResource("/application-localauth.yaml")).readText()
        val fallbacks = Regex("""password: \$\{LOCAL_AUTH_\w+_PASSWORD:([^}]*)}""").findAll(profile).map { it.groupValues[1] }.toSet()
        assertThat(fallbacks).isEqualTo(AuthenticationSafetyValidator.DEFAULT_PASSWORDS)
    }
}
