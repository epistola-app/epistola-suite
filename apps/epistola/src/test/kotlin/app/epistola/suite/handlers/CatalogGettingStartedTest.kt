// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** From a new catalog to a document an application generates from it: which step is next. */
class CatalogGettingStartedTest {

    private fun steps(
        templateKey: String? = null,
        published: Boolean = false,
        environments: List<String> = listOf("test"),
        liveIn: List<String> = emptyList(),
    ) = CatalogGettingStarted("acme", "letters", templateKey, published, environments, liveIn, mayEdit = true, mayPublish = true, mayDeploy = true)

    @Test
    fun `the steps go template, then publish and make live as one, and stop showing once something is live`() {
        assertThat(steps().current).isEqualTo(1)
        assertThat(steps(templateKey = "invoice").current).isEqualTo(2)
        assertThat(steps(templateKey = "invoice", published = true).current).`as`("published but not live: still step 2").isEqualTo(2)

        val live = steps(templateKey = "invoice", published = true, liveIn = listOf("test"))
        assertThat(live.show).isFalse()
    }

    @Test
    fun `making live needs an environment first, and the application example names the one it is live in`() {
        assertThat(steps(environments = emptyList()).needsEnvironment).isTrue()
        assertThat(steps(environments = listOf("acceptance", "test")).exampleEnvironment).isEqualTo("acceptance")
        assertThat(steps(environments = listOf("acceptance", "test"), liveIn = listOf("test")).exampleEnvironment).isEqualTo("test")
    }
}
