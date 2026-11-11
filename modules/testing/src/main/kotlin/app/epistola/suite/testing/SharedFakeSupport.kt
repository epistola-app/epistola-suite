// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

/**
 * [FakeSupportServer]s shared per test JVM by the test classes that only need *a* support service,
 * the way [SharedFakeExchange] is for Exchange.
 *
 * A fake per class meant a distinct support endpoint per class, and a distinct endpoint is a
 * distinct Spring context, each with the support background work running in it: a full boot per
 * class, and enough live contexts to run the apps test JVM out of heap. With a shared server, the
 * classes of one configuration share a context.
 *
 * There is one server per configuration, because the endpoint names the support connection: two
 * contexts on one server would share its stored registration. The fake is scripted through mutable
 * fields and the classes share that registration, so they must not run concurrently: their base
 * classes hold a JUnit `@ResourceLock` on [RESOURCE_LOCK], and give every class a fresh service and
 * no registration when it starts. The servers live for the JVM; there is nothing to stop.
 */
object SharedFakeSupport {
    const val RESOURCE_LOCK = "shared-fake-support"

    /** For a context with `epistola.support.auto-register` on: the check registers by itself. */
    val autoRegistering: FakeSupportServer by lazy { FakeSupportServer() }

    /** For a context with `epistola.support.auto-register` off: an administrator registers. */
    val manual: FakeSupportServer by lazy { FakeSupportServer() }

    /** The test class that last started on a shared server; the next one starts afresh. */
    @Volatile
    var lastClass: Class<*>? = null
}
