// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.env.MockEnvironment

/**
 * The demo module IS on this test's classpath (the app takes it on `testAndDevelopmentOnly`), so the
 * "present" cases are real. The absent case is covered by a class loader that hides it — which is
 * exactly what the default image's classpath does.
 */
@Tag("unit")
class DemoProfileImageValidatorTest {

    private fun validator(vararg profiles: String) = DemoProfileImageValidator(
        MockEnvironment().apply { setActiveProfiles(*profiles) },
    )

    /** Reproduces the default image's classpath: nothing under the demo package resolves. */
    private val withoutDemoModule = object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("app.epistola.suite.demo.")) throw ClassNotFoundException(name)
            return super.loadClass(name, resolve)
        }
    }

    @Test
    fun `the demo profile passes on an image that contains demo mode`() {
        assertDoesNotThrow { validator("demo").validate(javaClass.classLoader) }
    }

    @Test
    fun `no demo profile passes on either image`() {
        assertDoesNotThrow { validator("prod").validate(withoutDemoModule) }
        assertDoesNotThrow { validator().validate(withoutDemoModule) }
        assertDoesNotThrow { validator("prod").validate(javaClass.classLoader) }
    }

    @Test
    fun `the demo profile fails on an image without demo mode`() {
        val error = assertThrows<IllegalStateException> {
            validator("demo").validate(withoutDemoModule)
        }

        assertThat(error).hasMessageContaining("does not contain demo mode")
        assertThat(error).hasMessageContaining("-demo")
    }
}
