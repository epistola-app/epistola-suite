// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey

/**
 * An operation authorized as a whole by the one permission it declares.
 *
 * A REST operation enforces the permission its contract declares (`x-required-permissions`, "the
 * enforced permission on each operation"). A write does more than its command, though: it reads to
 * answer, and its command's handler reads what it needs. Dispatched one by one, each of those reads
 * asked for the caller's view permission, so a key holding only the declared one (an author's
 * `TEMPLATE_EDIT`) was refused building the answer to a write it was allowed to make.
 *
 * [authorizedOperation] checks the declared permission once, with the usual refusals, then runs the
 * operation. In it, a **query** of the same tenant needs no permission of its own: what the operation
 * reads to do and answer what it was allowed is part of it, and what the caller sees is what the
 * operation returns. Tenant access is still checked for it. A **command** is checked as always, so an
 * operation never writes more than its caller may.
 *
 * A command's handler may also build on other commands, as a draft opened from a release is filled
 * by exporting the release and importing it into the draft. Those are how the command does what it
 * was authorized for, not further requests of the caller: [partOfCurrentCommand] runs them as part
 * of the command being handled, commands and queries alike, and only while the mediator is handling
 * an authorized command of that tenant.
 *
 * Grants live on the dispatching thread and are restored when the operation ends; work handed to
 * another thread does not carry them, and is checked as usual.
 */
object AuthorizedOperation {
    private val current = ThreadLocal<Grant?>()

    /** The tenants of the authorized commands the mediator is handling on this thread, innermost last. */
    private val handling = ThreadLocal<ArrayDeque<TenantKey>>()

    /**
     * What is being run: its tenant, and the permission it was authorized by, or null for the part
     * of a command ([partOfCurrentCommand]). [commands] says whether it covers commands too.
     */
    data class Grant(val tenantKey: TenantKey, val permission: Permission?, val commands: Boolean = false)

    /**
     * Requires [permission] in [tenantKey] of the current principal and starts the operation it
     * authorizes. Returns the grant it replaces, for [end].
     *
     * @throws TenantAccessDeniedException without access to the tenant
     * @throws PermissionDeniedException without [permission]
     */
    fun begin(tenantKey: TenantKey, permission: Permission): Grant? {
        if (!SecurityContext.current().grantsInEveryTenant(permission)) requireTenantAccess(tenantKey)
        requirePermission(tenantKey, permission)
        return current.get().also { current.set(Grant(tenantKey, permission)) }
    }

    /**
     * Starts running part of the command being handled, in [tenantKey]. Returns the grant it replaces,
     * for [end]. When the mediator is not handling an authorized command of [tenantKey] (system work,
     * a command of another tenant), nothing is granted: what follows is checked as usual.
     */
    fun beginPartOfCommand(tenantKey: TenantKey): Grant? {
        val previous = current.get()
        if (handling.get()?.lastOrNull() == tenantKey) current.set(Grant(tenantKey, permission = null, commands = true))
        return previous
    }

    /** Ends what [begin] or [beginPartOfCommand] started, restoring the grant it replaced. */
    fun end(previous: Grant?) = if (previous == null) current.remove() else current.set(previous)

    /** Whether a query, or with [isQuery] false a command, of [tenantKey] is part of what is being run. */
    fun covers(tenantKey: TenantKey, isQuery: Boolean): Boolean = current.get()?.let { it.tenantKey == tenantKey && (isQuery || it.commands) } == true

    /** For the mediator: runs [block], the handling of an authorized command of [tenantKey]. */
    fun <T> handlingCommand(tenantKey: TenantKey, block: () -> T): T {
        val stack = handling.get() ?: ArrayDeque<TenantKey>().also { handling.set(it) }
        stack.addLast(tenantKey)
        try {
            return block()
        } finally {
            stack.removeLast()
            if (stack.isEmpty()) handling.remove()
        }
    }
}

/**
 * Runs [block] as an operation authorized by [permission] in [tenantKey] ([AuthorizedOperation]).
 * Inline, so a `return` in [block] returns from the caller, as in the operation's own body.
 */
inline fun <T> authorizedOperation(tenantKey: TenantKey, permission: Permission, block: () -> T): T {
    val previous = AuthorizedOperation.begin(tenantKey, permission)
    try {
        return block()
    } finally {
        AuthorizedOperation.end(previous)
    }
}

/**
 * Runs [block] as part of the authorized command of [tenantKey] the mediator is handling: the commands
 * and queries it dispatches in that tenant are how that command does what it was authorized for
 * ([AuthorizedOperation]). Inline, so a `return` in [block] returns from the caller.
 */
inline fun <T> partOfCurrentCommand(tenantKey: TenantKey, block: () -> T): T {
    val previous = AuthorizedOperation.beginPartOfCommand(tenantKey)
    try {
        return block()
    } finally {
        AuthorizedOperation.end(previous)
    }
}
