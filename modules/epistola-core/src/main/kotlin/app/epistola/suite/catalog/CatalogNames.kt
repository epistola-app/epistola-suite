// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException

/**
 * Which namespace a catalog key means, for the work running now (ADR 0034, phase 4).
 *
 * Within a tenant a catalog is unique by namespace and key, so a key alone names one only while no
 * other namespace has a catalog with that key. Commands and queries still address a catalog by key
 * ([CatalogKey], `catalogAt`); a caller that knows the namespace says so around its work with
 * [within], and every message it dispatches looks the key up there: `SpringMediator` writes the
 * names into the transaction's `epistola.catalog_names` setting, which the database function
 * `epistola_catalog_id` reads. A key named nowhere is looked up by itself, and refused when it is
 * ambiguous ([ValidationCode.CATALOG_KEY_AMBIGUOUS]).
 *
 * Names nest: an inner [within] adds to, and for the same key replaces, the outer ones.
 */
object CatalogNames {
    private val NAMES: ScopedValue<Map<String, String>> = ScopedValue.newInstance()

    /** The PostgreSQL setting `epistola_catalog_id` reads: a JSON object from catalog key to namespace. */
    const val SETTING: String = "epistola.catalog_names"

    /** Names learned while a [frame] runs, by [name]. */
    private val FRAME: ScopedValue<MutableMap<String, String>> = ScopedValue.newInstance()

    /** The names the running work gives, key to namespace; empty when it gives none. */
    fun current(): Map<String, String> {
        val learned = if (FRAME.isBound) FRAME.get() else emptyMap()
        val given = if (NAMES.isBound) NAMES.get() else emptyMap()
        return if (learned.isEmpty()) given else learned + given
    }

    /**
     * Runs [block] as a unit of work that learns names as it goes ([name]): an MCP tool call reads
     * `namespace/key` from its arguments only once it runs.
     */
    fun <R> frame(block: () -> R): R = ScopedValue.where(FRAME, LinkedHashMap()).call<R, RuntimeException> { block() }

    /** Records that [catalogKey] means the catalog in [namespace] for the rest of the [frame]; false outside one. */
    fun name(catalogKey: CatalogKey, namespace: String): Boolean {
        if (!FRAME.isBound) return false
        FRAME.get()[catalogKey.value] = namespace
        return true
    }

    /** Runs [block] with [catalogKey] meaning the catalog in [namespace]; a null namespace adds nothing. */
    fun <R> within(catalogKey: CatalogKey, namespace: String?, block: () -> R): R = if (namespace == null) block() else within(mapOf(catalogKey.value to namespace), block)

    /** Runs [block] with each key of [names] meaning the catalog in its namespace. */
    fun <R> within(names: Map<String, String>, block: () -> R): R {
        if (names.isEmpty()) return block()
        return ScopedValue.where(NAMES, current() + names).call<R, RuntimeException> { block() }
    }

    /**
     * Runs [block] on [handle] with [catalogKey] meaning the catalog in [namespace]: for a walk over
     * several catalogs on one handle (an environment's releases and what they are pinned to), where
     * two can share a key. The names go into the handle's transaction, one begun when it has none, for
     * the length of [block], and what the transaction held before is put back after.
     */
    fun <R> on(handle: Handle, catalogKey: CatalogKey, namespace: String, block: () -> R): R = within(catalogKey, namespace) {
        handle.inTransaction<R, RuntimeException> {
            val before = handle.createQuery("SELECT COALESCE(current_setting('$SETTING', TRUE), '')").mapTo(String::class.java).one()
            apply(handle)
            val result = block()
            handle.createQuery("SELECT set_config('$SETTING', :value, TRUE)").bind("value", before).mapTo(String::class.java).one()
            result
        }
    }

    /** Writes [names] into [handle]'s transaction, for `epistola_catalog_id`. */
    fun apply(handle: Handle, names: Map<String, String> = current()) {
        handle.createQuery("SELECT set_config('$SETTING', :value, TRUE)")
            .bind("value", if (names.isEmpty()) "" else json(names))
            .mapTo(String::class.java)
            .one()
    }

    private fun json(names: Map<String, String>): String = names.entries.joinToString(",", "{", "}") { (key, namespace) -> "\"${escape(key)}\":\"${escape(namespace)}\"" }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** SQLSTATE `epistola_catalog_id` raises when a key names several catalogs. */
    const val AMBIGUOUS_SQL_STATE: String = "EP409"

    /**
     * [failure] as the refusal people read when what it wraps is the database refusing an ambiguous
     * key (`CATALOG_KEY_AMBIGUOUS`, field `catalogKey`); null otherwise.
     */
    fun ambiguity(failure: Throwable): ValidationException? {
        var cause: Throwable? = failure
        val seen = mutableSetOf<Throwable>()
        while (cause != null && seen.add(cause)) {
            if (cause is ValidationException && cause.code == ValidationCode.CATALOG_KEY_AMBIGUOUS) return cause
            if (cause is SQLException && cause.sqlState == AMBIGUOUS_SQL_STATE) {
                val message = cause.message.orEmpty().substringAfter("ERROR: ").substringBefore("\n").trim()
                return ValidationException("catalogKey", message.ifEmpty { "A catalog key names several catalogs: name its namespace." }, ValidationCode.CATALOG_KEY_AMBIGUOUS).also { it.initCause(failure) }
            }
            cause = cause.cause
        }
        return null
    }
}

/**
 * Writes [CatalogNames] into the running transaction: `SpringMediator` around every message, and a
 * handler that learns a namespace itself ([named]). It records what the transaction holds, so a
 * message given no names costs nothing unless an earlier one in its transaction was given some, and
 * restores it when the work ends.
 */
@Component
class CatalogNamesApplier(
    private val jdbi: Jdbi,
    private val transactionManager: ObjectProvider<PlatformTransactionManager>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Runs [block] with [CatalogNames.current] written into the transaction, if one is active. */
    fun <R> run(block: () -> R): R {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) return block()
        val names = CatalogNames.current()

        @Suppress("UNCHECKED_CAST")
        val held = TransactionSynchronizationManager.getResource(CatalogNames) as Map<String, String>? ?: emptyMap()
        if (names == held) return block()
        write(names)
        try {
            return block()
        } finally {
            try {
                write(held)
            } catch (e: Exception) {
                // The transaction failed and will roll back, which reverts the setting with it.
                logger.debug("Could not restore catalog names after failed work: {}", e.message)
            }
        }
    }

    /**
     * Runs [block] with [catalogKey] meaning the catalog in [namespace], in this transaction too: for a
     * handler that learns a namespace itself, or one that walks every catalog of a tenant and reads each
     * by key. With no transaction running, [block] gets one of its own, since the names live in one.
     */
    fun <R> named(catalogKey: CatalogKey, namespace: String, block: () -> R): R = CatalogNames.within(catalogKey, namespace) {
        val manager = transactionManager.ifAvailable
        if (TransactionSynchronizationManager.isActualTransactionActive() || manager == null) run(block) else TransactionTemplate(manager).execute { run(block) }
    }

    private fun write(names: Map<String, String>) {
        jdbi.useHandle<Exception> { CatalogNames.apply(it, names) }
        val bound = TransactionSynchronizationManager.unbindResourceIfPossible(CatalogNames) != null
        if (names.isEmpty()) return
        TransactionSynchronizationManager.bindResource(CatalogNames, names)
        // The record goes with its transaction: the next one on this thread starts with no names.
        if (!bound && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        TransactionSynchronizationManager.unbindResourceIfPossible(CatalogNames)
                    }
                },
            )
        }
    }
}
