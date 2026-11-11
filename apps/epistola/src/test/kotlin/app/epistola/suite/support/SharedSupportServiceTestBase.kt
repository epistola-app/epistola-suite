// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.support.connection.DefaultSupportConnection
import app.epistola.suite.support.connection.SupportConnectionStore
import app.epistola.suite.testing.FakeSupportServer
import app.epistola.suite.testing.SharedFakeSupport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Web tests against a JVM-wide [SharedFakeSupport] server, sharing one context per configuration
 * instead of booting one per class (see [SharedFakeSupport] for why that matters).
 *
 * The classes share the fake and the stored registration of the connection it names, so they hold
 * [SharedFakeSupport.RESOURCE_LOCK] and each class starts as it did with a server of its own: a
 * service that has never been called, and no registration or saved service selection. Within a
 * class, state carries from test to test as before; a class that wants more resets it itself.
 */
@ResourceLock(SharedFakeSupport.RESOURCE_LOCK)
abstract class SharedSupportServiceTestBase(protected val support: FakeSupportServer) : BaseIntegrationTest() {
    @Autowired
    private lateinit var sharedSupportStore: SupportConnectionStore

    @Autowired
    private lateinit var sharedSupportConnection: DefaultSupportConnection

    @BeforeEach
    fun freshSupportServiceForEachClass() {
        if (SharedFakeSupport.lastClass == javaClass) return
        SharedFakeSupport.lastClass = javaClass
        support.reset()
        sharedSupportStore.forget()
        sharedSupportStore.forgetServices()
        sharedSupportConnection.invalidate()
        sharedSupportConnection.resetBackoff()
    }

    companion object {
        /**
         * The connection `epistola.support.*` configures, against [server], with every service
         * selected as an administrator would; the selection itself is tested in
         * `SupportServiceSelectionIntegrationTest`.
         */
        fun supportProperties(registry: DynamicPropertyRegistry, server: FakeSupportServer, autoRegister: Boolean) {
            registry.add("epistola.support.enabled") { "true" }
            registry.add("epistola.support.privacy.tenant-identity") { "named" }
            registry.add("epistola.support.services") { "telemetry,requests,mirror" }
            if (autoRegister) registry.add("epistola.support.auto-register") { "true" }
            registry.add("epistola.support.endpoint") { server.baseUrl }
            registry.add("epistola.support.allow-http") { "true" }
        }
    }
}

/** [SharedSupportServiceTestBase] with `epistola.support.auto-register` on: the check registers by itself. */
abstract class AutoRegisteringSupportTestBase : SharedSupportServiceTestBase(SharedFakeSupport.autoRegistering) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun autoRegisteringSupportService(registry: DynamicPropertyRegistry) = supportProperties(registry, SharedFakeSupport.autoRegistering, autoRegister = true)
    }
}

/** [SharedSupportServiceTestBase] with `epistola.support.auto-register` off (the default): an administrator registers. */
abstract class ManualSupportTestBase : SharedSupportServiceTestBase(SharedFakeSupport.manual) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun manualSupportService(registry: DynamicPropertyRegistry) = supportProperties(registry, SharedFakeSupport.manual, autoRegister = false)
    }
}
