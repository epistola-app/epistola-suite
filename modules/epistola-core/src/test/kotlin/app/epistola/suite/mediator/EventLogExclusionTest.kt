// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mediator

import app.epistola.suite.common.NotEventLogged
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.commands.GenerateDocumentBatch
import app.epistola.suite.generation.collect.commands.EmitGenerationResult
import app.epistola.suite.tenants.commands.CreateTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

/**
 * Pins which commands the `event_log` stream records (ADR 0009, Option A): the document-generation
 * path is opted out (high-volume, already its own system of record in `generation_results`), while
 * ordinary authoring commands stream as usual. `EventLogSubscriber` skips any command implementing
 * [NotEventLogged]; this guards the marker placement that drives it.
 */
class EventLogExclusionTest {
    /** A command is recorded in `event_log` iff it does not carry the [NotEventLogged] opt-out. */
    private fun isEventLogged(command: KClass<*>): Boolean = !NotEventLogged::class.java.isAssignableFrom(command.java)

    @Test
    fun `generation path commands are NOT recorded in the event log`() {
        assertThat(isEventLogged(GenerateDocument::class)).`as`("GenerateDocument").isFalse()
        assertThat(isEventLogged(GenerateDocumentBatch::class)).`as`("GenerateDocumentBatch").isFalse()
        assertThat(isEventLogged(EmitGenerationResult::class)).`as`("EmitGenerationResult").isFalse()
    }

    @Test
    fun `ordinary authoring commands ARE recorded in the event log`() {
        assertThat(isEventLogged(CreateTenant::class)).`as`("CreateTenant").isTrue()
    }
}
