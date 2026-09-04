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
 * The production side of the guard, tested against the real thing: demo mode is genuinely absent
 * from this project's classpath, so nothing is stubbed or hidden here. If someone were to put demo
 * mode back into `apps/epistola`, this test would start failing — which is the point.
 *
 * The other branch lives in `apps/epistola-demo`, where the classes are present.
 */
@Tag("unit")
class DemoProfileImageValidatorTest {

    private fun validator(vararg profiles: String) = DemoProfileImageValidator(
        MockEnvironment().apply { setActiveProfiles(*profiles) },
    )

    @Test
    fun `the demo profile fails on this image, which does not contain demo mode`() {
        val error = assertThrows<IllegalStateException> {
            validator("demo").validate(javaClass.classLoader)
        }

        assertThat(error).hasMessageContaining("does not contain demo mode")
        assertThat(error).hasMessageContaining("-demo")
    }

    @Test
    fun `every other profile starts normally`() {
        assertDoesNotThrow { validator("prod").validate(javaClass.classLoader) }
        assertDoesNotThrow { validator("local", "localauth").validate(javaClass.classLoader) }
        assertDoesNotThrow { validator().validate(javaClass.classLoader) }
    }
}
