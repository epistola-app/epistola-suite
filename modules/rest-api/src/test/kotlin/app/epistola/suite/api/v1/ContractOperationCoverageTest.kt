// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.web.bind.annotation.RestController

/**
 * Every operation of the contract is answered: each generated server interface (`app.epistola.api.*Api`)
 * has a controller here that implements it, so an operation the suite does not support yet answers
 * `501 operation-not-implemented` (throwing [ApiOperationNotImplementedException]) rather than
 * `404 not-found` for a route nothing maps. The generated interfaces declare every operation
 * abstract, so implementing the interface covers each of its operations.
 */
class ContractOperationCoverageTest {

    @Test
    fun `every generated server interface has a controller implementing it`() {
        val interfaces = classesIn("app/epistola/api").filter { it.isInterface && it.simpleName.endsWith("Api") }
        val controllers = classesIn("app/epistola/suite/api/v1").filter { it.isAnnotationPresent(RestController::class.java) }

        assertThat(interfaces).`as`("the generated interfaces are on the classpath").isNotEmpty()
        val unimplemented = interfaces.filter { api -> controllers.none { api.isAssignableFrom(it) } }.map { it.simpleName }
        assertThat(unimplemented)
            .`as`("generated interfaces no controller implements: their operations answer 404 instead of 501")
            .isEmpty()
    }

    private fun classesIn(packagePath: String): List<Class<*>> = PathMatchingResourcePatternResolver()
        .getResources("classpath*:$packagePath/*.class")
        .mapNotNull { it.filename?.removeSuffix(".class") }
        .filterNot { it.contains('$') }
        .map { Class.forName("${packagePath.replace('/', '.')}.$it") }
}
