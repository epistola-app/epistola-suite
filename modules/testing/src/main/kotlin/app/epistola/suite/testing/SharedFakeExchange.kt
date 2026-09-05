// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

/**
 * One [FakeExchangeServer] per test JVM, shared by every test class that talks to Exchange.
 *
 * A fake per class meant a distinct Exchange URL per class, and a distinct URL is a distinct
 * Spring context: a dozen full boots for what is one configuration. With one server the classes
 * share a context. The fake is scripted through mutable fields, so the classes that share it must
 * not run concurrently; the base classes that use it hold a JUnit `@ResourceLock` for that, and
 * reset the fake before every test. The server lives for the JVM; there is nothing to stop.
 */
object SharedFakeExchange {
    const val RESOURCE_LOCK = "shared-fake-exchange"

    val server: FakeExchangeServer by lazy { FakeExchangeServer() }
}
