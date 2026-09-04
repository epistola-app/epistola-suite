// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.env.MockEnvironment

/**
 * The guard is `@Profile("!test")`, so — like `AuthenticationSafetyValidatorTest` — this constructs
 * it directly against a [MockEnvironment] rather than booting a context.
 */
@Tag("unit")
class DemoSharedSecretSafetyValidatorTest {

    private fun validator(secret: String, vararg profiles: String) = DemoSharedSecretSafetyValidator(
        environment = MockEnvironment().apply { setActiveProfiles(*profiles) },
        properties = DemoProperties(enabled = true, sharedSecret = secret),
    )

    @Test
    fun `a secret under the demo profile is fine`() {
        assertDoesNotThrow { validator(SECRET, "demo").afterSingletonsInstantiated() }
    }

    @Test
    fun `a secret alongside other profiles is fine as long as demo is one of them`() {
        // demo + prod is a supported staging combination; this guard is about the profile, not
        // about whether the deployment is production-like.
        assertDoesNotThrow { validator(SECRET, "demo", "prod").afterSingletonsInstantiated() }
    }

    @Test
    fun `a secret without the demo profile fails the boot`() {
        val error = assertThrows<IllegalStateException> { validator(SECRET, "prod").afterSingletonsInstantiated() }

        assertThat(error).hasMessageContaining("SECURITY")
        assertThat(error).hasMessageContaining("epistola.demo.shared-secret")
        assertThat(error).hasMessageContaining("prod")
    }

    @Test
    fun `the local profile is not the demo profile`() {
        // The trap this guard exists for: `local` sets epistola.demo.enabled=true, so a
        // property-only gate would look satisfied while the filter is nowhere in the chain.
        assertThrows<IllegalStateException> { validator(SECRET, "local").afterSingletonsInstantiated() }
    }

    @Test
    fun `no secret is fine in any profile`() {
        assertDoesNotThrow { validator("").afterSingletonsInstantiated() }
        assertDoesNotThrow { validator("", "prod").afterSingletonsInstantiated() }
        assertDoesNotThrow { validator("   ", "local").afterSingletonsInstantiated() }
    }

    companion object {
        /** Deliberately unmistakable and low-entropy — a realistic-looking one trips secret scanners. */
        private const val SECRET = "not-a-real-secret-only-used-in-tests"
    }
}
