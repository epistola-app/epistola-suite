// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mediator

import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import org.slf4j.MDC

/**
 * What a log line was written for: the message being dispatched (`command` or `query`) and, for a
 * message on one catalog version, its catalog. Set around each dispatch and restored after, so a
 * nested dispatch names itself and its caller's values come back once it returns. Structured
 * logging writes every MDC entry, so these appear as fields in the JSON output, and the database
 * log keeps them in `attributes`.
 *
 * The tenant is not here: it has one source, the principal (`tenant_id`, `tenant_key`; see
 * `docs/application-logs.md`).
 *
 * MDC is per thread and a virtual thread does not inherit it, so work handed off through
 * [MediatorContext.runnable] or [MediatorContext.callable] carries these values across ([capture]).
 */
internal object DispatchMdc {
    const val COMMAND: String = "command"
    const val QUERY: String = "query"
    const val CATALOG: String = "catalog"

    private val keys = listOf(COMMAND, QUERY, CATALOG)

    /** Runs [block] with [message]'s values in the MDC under [kind] ([COMMAND] or [QUERY]). */
    fun <R> around(kind: String, name: String, message: Any, block: () -> R): R {
        val values = buildMap {
            put(kind, name)
            catalogOf(message)?.let { put(CATALOG, it) }
        }
        return with(values, block)
    }

    /** This thread's dispatch values, to carry to another thread. */
    fun capture(): Map<String, String> = keys.mapNotNull { key -> MDC.get(key)?.let { key to it } }.toMap()

    /** Runs [block] with [values] in the MDC, restoring what was there before. */
    fun <R> with(values: Map<String, String>, block: () -> R): R {
        if (values.isEmpty()) return block()
        val previous = values.keys.associateWith { MDC.get(it) }
        values.forEach { (key, value) -> MDC.put(key, value) }
        try {
            return block()
        } finally {
            previous.forEach { (key, value) -> if (value == null) MDC.remove(key) else MDC.put(key, value) }
        }
    }

    private fun catalogOf(message: Any): String? = (message as? CatalogReleaseScoped)?.catalogVersion?.catalog?.address
}
