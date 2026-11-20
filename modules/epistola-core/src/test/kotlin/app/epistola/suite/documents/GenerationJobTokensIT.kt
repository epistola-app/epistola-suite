// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents

import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.documents.interactive.InteractiveFormLinks
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.time.EpistolaClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration

/**
 * The token a person in the web UI follows their own generation job with: it names one request, works
 * for the person it was issued to only, until it expires, and nothing else signed by the installation
 * passes for one.
 */
class GenerationJobTokensIT : IntegrationTestBase() {

    @Autowired
    private lateinit var tokens: GenerationJobTokens

    @Autowired
    private lateinit var formLinks: InteractiveFormLinks

    private val person = UserKey.generate()
    private val request = GenerationRequestKey.generate()

    /** Runs [block] on the test clock; a test method must return Unit, so this one does. */
    private fun inScope(block: () -> Unit): Unit = withMediator(block)

    @Test
    fun `a token names its request for the person it was issued to`() = inScope {
        val token = tokens.issue(request, person, EpistolaClock.offsetDateTime().plusHours(1))

        assertThat(tokens.verify(token, person)).isEqualTo(request)
    }

    @Test
    fun `someone else holding the token gets nothing`() = inScope {
        val token = tokens.issue(request, person, EpistolaClock.offsetDateTime().plusHours(1))

        assertThat(tokens.verify(token, UserKey.generate())).isNull()
    }

    @Test
    fun `an expired token opens nothing`() = inScope {
        val token = tokens.issue(request, person, EpistolaClock.offsetDateTime().plusHours(1))

        testClock.advanceBy(Duration.ofHours(1))

        assertThat(tokens.verify(token, person)).isNull()
    }

    @Test
    fun `a token with a changed payload or signature opens nothing`() = inScope {
        val token = tokens.issue(request, person, EpistolaClock.offsetDateTime().plusHours(1))
        val (payload, signature) = token.split('.')
        // Another request in the payload, with the original signature.
        val other = tokens.issue(GenerationRequestKey.generate(), person, EpistolaClock.offsetDateTime().plusHours(1)).substringBefore('.')

        assertThat(tokens.verify("$other.$signature", person)).isNull()
        assertThat(tokens.verify("$payload.${signature.reversed()}", person)).isNull()
        assertThat(tokens.verify("$payload.", person)).isNull()
        assertThat(tokens.verify("not a token", person)).isNull()
    }

    @Test
    fun `a form session, signed by the same key, is not a job token`() = inScope {
        // A session's payload is as long as a token's; only the purpose in the signature tells them apart.
        val session = formLinks.openSession(request, EpistolaClock.offsetDateTime().plusHours(1))

        assertThat(tokens.verify(session.token, person)).isNull()
    }
}
