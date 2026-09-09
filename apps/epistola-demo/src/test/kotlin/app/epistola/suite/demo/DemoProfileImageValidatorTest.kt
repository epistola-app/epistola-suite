// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.config.DemoProfileImageValidator
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.mock.env.MockEnvironment

/**
 * The demo side of the guard. Its counterpart in `apps/epistola` asserts the failure against a
 * classpath that genuinely lacks demo mode; this one asserts the pass against one that has it.
 *
 * Together they pin the thing that actually matters: the guard tracks which artifact it is running
 * in, rather than a flag someone could set either way.
 */
@Tag("unit")
class DemoProfileImageValidatorTest {

    @Test
    fun `the demo profile starts on this image, which contains demo mode`() {
        val validator = DemoProfileImageValidator(MockEnvironment().apply { setActiveProfiles("demo") })

        assertDoesNotThrow { validator.validate(javaClass.classLoader) }
    }

    @Test
    fun `demo alongside prod is allowed - the artifact is the boundary, not the profile`() {
        val validator = DemoProfileImageValidator(
            MockEnvironment().apply { setActiveProfiles("demo", "prod") },
        )

        assertDoesNotThrow { validator.validate(javaClass.classLoader) }
    }
}
