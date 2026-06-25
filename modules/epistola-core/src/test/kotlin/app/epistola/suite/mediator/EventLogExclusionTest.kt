package app.epistola.suite.mediator

import app.epistola.suite.common.NotEventLogged
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.commands.GenerateDocumentBatch
import app.epistola.suite.generation.collect.commands.EmitGenerationResult
import app.epistola.suite.tenants.commands.CreateTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins which commands are kept out of the `event_log` stream (ADR 0009, Option A): the
 * document-generation path is `NotEventLogged` (high-volume, tracked in `generation_results`),
 * while ordinary authoring commands stay in the stream. `EventLogSubscriber.persist` skips any
 * command implementing [NotEventLogged]; this guards the marker placement that drives it.
 */
class EventLogExclusionTest {
    @Test
    fun `generation path commands are excluded from the event log`() {
        assertThat(NotEventLogged::class.java).`as`("GenerateDocument").isAssignableFrom(GenerateDocument::class.java)
        assertThat(NotEventLogged::class.java).`as`("GenerateDocumentBatch").isAssignableFrom(GenerateDocumentBatch::class.java)
        assertThat(NotEventLogged::class.java).`as`("EmitGenerationResult").isAssignableFrom(EmitGenerationResult::class.java)
    }

    @Test
    fun `ordinary authoring commands stay in the event log`() {
        assertThat(NotEventLogged::class.java.isAssignableFrom(CreateTenant::class.java))
            .`as`("CreateTenant must remain event-logged")
            .isFalse()
    }
}
