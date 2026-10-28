// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mediator

import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.SystemInternal
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.context.support.StaticApplicationContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The dispatch's log context: set around a command or query, restored after, carried across threads. */
class SpringMediatorMdcTest {

    private lateinit var mediator: SpringMediator

    /** Each message records the MDC its handler saw. */
    data class Seen(val values: Map<String, String?>)

    data class OnCatalog(override val catalogVersion: CatalogReleaseRef) :
        Command<Seen>,
        CatalogReleaseScoped,
        SystemInternal

    data class Outer(val inner: OnCatalog) :
        Command<Pair<Seen, Seen>>,
        SystemInternal

    data class Ask(val id: Int) :
        Query<Seen>,
        SystemInternal

    data class HandOff(val id: Int) :
        Command<Seen>,
        SystemInternal

    class OnCatalogHandler : CommandHandler<OnCatalog, Seen> {
        override fun handle(command: OnCatalog) = seen()
    }

    class OuterHandler : CommandHandler<Outer, Pair<Seen, Seen>> {
        override fun handle(command: Outer): Pair<Seen, Seen> {
            val inner = command.inner.execute()
            return inner to seen()
        }
    }

    class AskHandler : QueryHandler<Ask, Seen> {
        override fun handle(query: Ask) = seen()
    }

    /** Hands its work to a virtual thread the way handlers are told to: through [MediatorContext.callable]. */
    class HandOffHandler : CommandHandler<HandOff, Seen> {
        override fun handle(command: HandOff): Seen = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            executor.submit(MediatorContext.callable(MediatorContext.current()) { seen() }).get(5, TimeUnit.SECONDS)
        }
    }

    @BeforeEach
    fun setUp() {
        val context = StaticApplicationContext()
        context.beanFactory.registerSingleton("onCatalog", OnCatalogHandler())
        context.beanFactory.registerSingleton("outer", OuterHandler())
        context.beanFactory.registerSingleton("ask", AskHandler())
        context.beanFactory.registerSingleton("handOff", HandOffHandler())
        context.refresh()
        mediator = SpringMediator(context, context, SimpleMeterRegistry(), emptyList(), emptyList(), null)
    }

    @AfterEach
    fun clear() = MDC.clear()

    @Test
    fun `a command's handler logs with the command and its catalog, and nothing is left after`() {
        val seen = mediator.send(OnCatalog(ref))

        assertThat(seen.values).containsEntry(DispatchMdc.COMMAND, "OnCatalog")
            .containsEntry(DispatchMdc.CATALOG, "local/letters")
            .containsEntry(DispatchMdc.QUERY, null)
        assertThat(MDC.getCopyOfContextMap().orEmpty()).isEmpty()
    }

    @Test
    fun `a query's handler logs with the query`() {
        assertThat(mediator.query(Ask(1)).values).containsEntry(DispatchMdc.QUERY, "Ask").containsEntry(DispatchMdc.COMMAND, null)
    }

    @Test
    fun `a nested dispatch names itself, and its caller's values come back after it`() {
        val (inner, outerAfter) = mediator.send(Outer(OnCatalog(ref)))

        assertThat(inner.values).containsEntry(DispatchMdc.COMMAND, "OnCatalog").containsEntry(DispatchMdc.CATALOG, "local/letters")
        assertThat(outerAfter.values).containsEntry(DispatchMdc.COMMAND, "Outer").containsEntry(DispatchMdc.CATALOG, null)
    }

    @Test
    fun `work handed to a virtual thread logs with the dispatch that handed it off`() {
        assertThat(mediator.send(HandOff(1)).values).containsEntry(DispatchMdc.COMMAND, "HandOff")
    }

    private companion object {
        val ref = CatalogReleaseRef(CatalogId("local", CatalogKey.of("letters"), TenantId(TenantKey.of("acme"))), DraftRef.Next)

        fun seen() = Seen(listOf(DispatchMdc.COMMAND, DispatchMdc.QUERY, DispatchMdc.CATALOG).associateWith { MDC.get(it) })
    }
}
