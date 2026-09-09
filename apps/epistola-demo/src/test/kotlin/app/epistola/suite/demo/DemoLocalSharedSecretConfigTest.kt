// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment

/**
 * The committed shared secret is scoped to `demo` **and** `local` by a second document in
 * `application-demo.yaml`. Two things have to stay true and neither is obvious from reading the
 * file: the published demo image runs `demo` without `local`, so the value must not reach it, and
 * `DemoSecurityConfiguration` refuses to start on a secret shorter than
 * [DemoProperties.MIN_SHARED_SECRET_LENGTH].
 *
 * Binds the real resource rather than asserting on the literal, so moving the value between
 * documents cannot quietly pass.
 */
@Tag("unit")
class DemoLocalSharedSecretConfigTest {

    private fun bind(vararg activeProfiles: String): DemoProperties {
        val environment = StandardEnvironment()
        YamlPropertySourceLoader()
            .load("application-demo", ClassPathResource("application-demo.yaml"))
            .filter { source ->
                // Documents carry their activation as a plain property; the profile-less one always
                // applies, and a gated one only when that profile is active.
                val onProfile = source.getProperty("spring.config.activate.on-profile") as String?
                onProfile == null || onProfile in activeProfiles
            }
            .forEach { environment.propertySources.addLast(it) }
        return Binder.get(environment).bindOrCreate("epistola.demo", DemoProperties::class.java)
    }

    @Test
    fun `the local document supplies a usable secret`() {
        val properties = bind("demo", "local")

        assertThat(properties.sharedSecretConfigured).isTrue()
        assertThat(properties.sharedSecret.length)
            .describedAs("DemoSecurityConfiguration refuses to start below this length")
            .isGreaterThanOrEqualTo(DemoProperties.MIN_SHARED_SECRET_LENGTH)
    }

    @Test
    fun `the demo profile alone carries no secret`() {
        // This is the shape the published demo image runs in.
        assertThat(bind("demo").sharedSecretConfigured).isFalse()
    }

    @Test
    fun `the committed value is unmistakably not a real credential`() {
        // A realistic-looking one trips secret scanners and invites being copied somewhere real.
        assertThat(bind("demo", "local").sharedSecret).contains("not-a-real-secret")
    }

    @Test
    fun `the guard still rejects the local secret without the demo profile`() {
        val validator = DemoSharedSecretSafetyValidator(
            environment = MockEnvironment().apply { setActiveProfiles("local") },
            properties = bind("demo", "local"),
        )

        assertThat(runCatching { validator.afterSingletonsInstantiated() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
    }
}
