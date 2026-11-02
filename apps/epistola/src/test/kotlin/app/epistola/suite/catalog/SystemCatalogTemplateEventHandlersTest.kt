// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.CommandCompleted
import app.epistola.suite.mediator.EventHandler
import app.epistola.suite.testing.SystemCatalogSetup
import app.epistola.suite.testing.TestNamespaces
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.PayloadApplicationEvent
import org.springframework.core.ResolvableType
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

/**
 * A test tenant whose system catalog is copied from the template ([app.epistola.suite.testing.SystemCatalogTemplate])
 * is created without the commands installing it would send, so nothing that reacts to those commands
 * runs for it. None does today; this fails when an [EventHandler] for one of them is added, since the
 * tests would then no longer see what it does. (What the commands write, the copy brings:
 * `SystemCatalogTemplateTest`.) Here, in the application, every module's handlers are in the context.
 */
class SystemCatalogTemplateEventHandlersTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `no event handler reacts to a command that copying the system catalog skips`() {
        val installing = commandsCompletedCreating(SystemCatalogSetup.INSTALL, TenantKey.of("evh-installed"))
        val copying = commandsCompletedCreating(SystemCatalogSetup.FROM_TEMPLATE, TenantKey.of("evh-copied"))
        val skipped = installing - copying
        assertThat(skipped).describedAs("commands installing the system catalog sends").isNotEmpty()

        val handled = applicationContext.getBeansOfType(EventHandler::class.java).values.associate { handler ->
            ResolvableType.forClass(AopUtils.getTargetClass(handler)).`as`(EventHandler::class.java).getGeneric(0).resolve()?.kotlin to handler.javaClass.simpleName
        }
        assertThat(handled.filterKeys { it in skipped }).describedAs("event handlers of commands the copy skips").isEmpty()
    }

    /** Every command about [tenant] that completed while it was created with its system catalog as [setup] says. */
    private fun commandsCompletedCreating(setup: SystemCatalogSetup, tenant: TenantKey): Set<KClass<*>> {
        val completed = ConcurrentHashMap.newKeySet<KClass<*>>()
        // The mediator publishes every completed command; only this tenant's are counted, so tests
        // running beside this one add nothing.
        (applicationContext as ConfigurableApplicationContext).addApplicationListener(
            ApplicationListener<PayloadApplicationEvent<*>> { event ->
                val command = (event.payload as? CommandCompleted<*>)?.command ?: return@ApplicationListener
                if (tenantOf(command) == tenant) completed += command::class
            },
        )
        withMediator { systemCatalogTemplate.createTenant(TestNamespaces.createTenant(tenant, tenant.value), setup) }
        return completed
    }

    /**
     * The tenant a command is about, by its `tenantKey` (the authorization markers name it so), or its
     * `id` for `CreateTenant`. Through Kotlin's reflection: [TenantKey] is a value class, whose JVM
     * getter is renamed.
     */
    private fun tenantOf(command: Any): TenantKey? = listOf("tenantKey", "id").firstNotNullOfOrNull { name ->
        runCatching { command::class.memberProperties.firstOrNull { it.name == name }?.getter?.call(command) as? TenantKey }.getOrNull()
    }
}
