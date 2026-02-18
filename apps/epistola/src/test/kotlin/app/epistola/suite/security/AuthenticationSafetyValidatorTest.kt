package app.epistola.suite.security

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
    fun `demo profile with prod throws`() {
        val validator = AuthenticationSafetyValidator(
            environment = envWith("demo", "prod"),
            userDetailsService = mockUserDetailsService(),
            clientRegistrationRepository = null,
        )

        assertThrows<IllegalStateException> {
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
    fun `demo profile alone with UserDetailsService passes`() {
        val validator = AuthenticationSafetyValidator(
            environment = envWith("demo"),
            userDetailsService = mockUserDetailsService(),
            clientRegistrationRepository = null,
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
}
