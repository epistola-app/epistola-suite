// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Where the publish dialog offers to make the new version live, and what it ticks. */
class PublishLiveChoiceTest {

    private val test = PublishLiveChoice.Target("test", "Test", emptyList())
    private val production = PublishLiveChoice.Target("production", "Production", listOf("1.0.0"))

    @Test
    fun `one environment is ticked, several are not, so nothing goes to production by accident`() {
        assertThat(PublishLiveChoice(listOf(test), mayDeploy = true).checked("test")).isTrue()

        val two = PublishLiveChoice(listOf(test, production), mayDeploy = true)
        assertThat(two.checked("test")).isFalse()
        assertThat(two.checked("production")).isFalse()
    }

    @Test
    fun `shown again after a refusal, it keeps what was ticked`() {
        val again = PublishLiveChoice(listOf(test, production), mayDeploy = true, chosen = setOf("production"))

        assertThat(again.checked("production")).isTrue()
        assertThat(again.checked("test")).isFalse()
    }

    @Test
    fun `offered with an environment and the right to deploy, and said why when the right is missing`() {
        assertThat(PublishLiveChoice(emptyList(), mayDeploy = true).offer).isFalse()
        assertThat(PublishLiveChoice(listOf(test), mayDeploy = false).offer).isFalse()
        assertThat(PublishLiveChoice(listOf(test), mayDeploy = false).withheld).isTrue()
        assertThat(PublishLiveChoice(emptyList(), mayDeploy = false).withheld).`as`("nothing to make live in").isFalse()
    }
}
