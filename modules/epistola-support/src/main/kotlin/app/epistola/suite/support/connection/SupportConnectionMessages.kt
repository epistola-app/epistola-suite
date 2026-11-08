// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.installation.InstallationService
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.security.Permission
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.support.SupportProperties
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.validate
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * One support service and where this installation stands with it.
 *
 * @property scope the service, or null for a selected name this version does not know.
 * @property name its wire value.
 * @property selected an installation administrator selected it (or the configuration did).
 * @property granted Epistola granted it, and the support service has not refused it since.
 * @property offered whether the support service lists it in `scopes_supported`; null when this
 *   node does not know (not read yet, or the service does not say).
 */
data class SupportServiceGrant(
    val scope: SupportScope?,
    val name: String,
    val title: String,
    val selected: Boolean,
    val granted: Boolean,
    val offered: Boolean? = null,
    /** Epistola paused it now; null when it is not paused. */
    val pause: SupportPause? = null,
) {
    /** In use: selected **and** granted, not paused and not retired. What [SupportConnection.hasScope] says. */
    val inUse: Boolean get() = scope != null && selected && granted && pause == null && offered != false

    /** Granted, but held back by Epistola for now. */
    val paused: Boolean get() = granted && pause != null

    /** This version knows the service. */
    val known: Boolean get() = scope != null

    /** Selected, but the support service no longer offers it: retired by Epistola. */
    val noLongerOffered: Boolean get() = selected && offered == false
}

/**
 * Every service this suite knows, then any selected name it does not, with where the installation
 * stands with each.
 */
internal fun supportServices(connection: SupportConnection, selected: List<String>, offered: Set<String>?): List<SupportServiceGrant> {
    val active = connection.status().state as? SupportConnectionState.Active
    val granted = active?.scopes.orEmpty()
    val pause = active?.pause?.takeIf { it.inForceAt(EpistolaClock.instant()) }
    val known = SupportScope.entries.map { scope ->
        SupportServiceGrant(scope, scope.value, scope.title(), scope.value in selected, scope in granted, offered?.let { scope.value in it }, pause?.takeIf { scope in it.scopes })
    }
    val unknown = selected.filter { it !in SupportScope.NAMES }.map { name ->
        SupportServiceGrant(null, name, name, selected = true, granted = false, offered = offered?.let { name in it })
    }
    return known + unknown
}

/**
 * The installation's support connection, for `/platform/support`.
 *
 * @property canCheck whether "Check now" has anything to do: only with the connection on.
 * @property canRegister whether "Register" is offered: while not registered, after the claim code
 *   expired, or after disconnecting. The only way to register without `epistola.support.auto-register`.
 * @property contact who Epistola contacts about this installation; checked and correctable before registering.
 * @property pause the services Epistola paused now; null when none.
 * @property canDisconnect whether "Disconnect" is offered: while there is a registration to end.
 * @property autoRegister whether the scheduled check registers on its own (`epistola.support.auto-register`).
 * @property claimCodeExpired the pending claim code's expiry has passed; the next check finds the
 *   registration gone.
 * @property canRegisterAgain whether "Register again" is offered: after a refusal or a decline, or while pending.
 * @property registerAgainNeedsConfirm a pending registration has a claim code someone may already
 *   have sent, which registering again invalidates.
 * @property selection the selected services and where the selection comes from.
 * @property canSelect whether the services can be selected here: whenever the connection is on.
 */
data class SupportConnectionOverview(
    val installationId: UUID,
    val status: SupportConnectionStatus,
    val services: List<SupportServiceGrant>,
    val canCheck: Boolean,
    val canRegister: Boolean,
    val canDisconnect: Boolean,
    val canRegisterAgain: Boolean,
    val registerAgainNeedsConfirm: Boolean,
    val autoRegister: Boolean,
    val claimCodeExpired: Boolean,
    val selection: SupportServiceSelection,
    val canSelect: Boolean,
    val contact: SupportContact,
    val pause: SupportPause? = null,
) {
    val state: SupportConnectionState get() = status.state
    val issuer: URI? get() = status.issuer
    val checkedAt: Instant? get() = status.checkedAt
    val lastError: String? get() = status.lastError
    val nodeWarning: String? get() = status.nodeWarning

    /** The paused services' names, for the page; empty when nothing is paused. */
    val pausedServices: String get() = pause?.scopes?.sortedBy { it.ordinal }?.joinToString(", ") { it.title() }.orEmpty()
}

/** Reads the support connection for the installation page. No network I/O. */
class GetSupportConnectionOverview :
    Query<SupportConnectionOverview>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

@Component
class GetSupportConnectionOverviewHandler(
    private val connection: DefaultSupportConnection,
    private val installations: InstallationService,
    private val properties: SupportProperties,
) : QueryHandler<GetSupportConnectionOverview, SupportConnectionOverview> {
    override fun handle(query: GetSupportConnectionOverview): SupportConnectionOverview {
        val status = connection.status()
        val state = status.state
        val selection = connection.selectedServices()
        return SupportConnectionOverview(
            installationId = installations.get().id,
            status = status,
            services = supportServices(connection, selection.services, connection.offeredServices()),
            canCheck = state !is SupportConnectionState.Disabled,
            canRegister = state is SupportConnectionState.Unregistered || state is SupportConnectionState.Expired || state is SupportConnectionState.Disconnected,
            canDisconnect = state is SupportConnectionState.Pending || state is SupportConnectionState.Active || state is SupportConnectionState.Refused,
            canRegisterAgain = state is SupportConnectionState.Refused || state is SupportConnectionState.Pending || state is SupportConnectionState.Declined,
            registerAgainNeedsConfirm = state is SupportConnectionState.Pending,
            autoRegister = properties.autoRegister,
            claimCodeExpired = (state as? SupportConnectionState.Pending)?.claimCodeExpiresAt?.let { !it.isAfter(EpistolaClock.instant()) } == true,
            selection = selection,
            canSelect = state !is SupportConnectionState.Disabled,
            contact = connection.contact(),
            pause = (state as? SupportConnectionState.Active)?.pause?.takeIf { it.inForceAt(EpistolaClock.instant()) },
        )
    }
}

/** What a tenant administrator is told about the installation's support connection. */
enum class TenantSupportConnection { OFF, NOT_CONNECTED, PENDING, CONNECTED }

/**
 * A tenant's view of support: only whether the installation is connected. Nothing about other
 * tenants, and not the claim code, which is for installation administrators.
 */
data class TenantSupportSummary(val tenantKey: TenantKey, val connection: TenantSupportConnection, val services: List<SupportServiceGrant>)

/** Reads [TenantSupportSummary] for the tenant's support page. No network I/O. */
data class GetTenantSupportSummary(override val tenantKey: TenantKey) :
    Query<TenantSupportSummary>,
    RequiresPermission {
    override val permission = Permission.TENANT_SETTINGS
}

@Component
class GetTenantSupportSummaryHandler(
    private val connection: SupportConnection,
) : QueryHandler<GetTenantSupportSummary, TenantSupportSummary> {
    override fun handle(query: GetTenantSupportSummary): TenantSupportSummary {
        val state = connection.status().state
        val summary = when (state) {
            SupportConnectionState.Disabled -> TenantSupportConnection.OFF
            is SupportConnectionState.Active -> TenantSupportConnection.CONNECTED
            is SupportConnectionState.Pending -> TenantSupportConnection.PENDING
            SupportConnectionState.Unregistered, is SupportConnectionState.Expired, is SupportConnectionState.Refused, is SupportConnectionState.Disconnected, is SupportConnectionState.Declined -> TenantSupportConnection.NOT_CONNECTED
        }
        val selected = SupportScope.entries.filter(connection::isSelected).map { it.value }
        // A tenant sees the services this version knows; a selected name it does not know is for the installation page.
        return TenantSupportSummary(query.tenantKey, summary, supportServices(connection, selected, connection.offeredServices()).filter { it.known })
    }
}

/**
 * Registers when needed and refreshes the connection status now, instead of at the next scheduled
 * check. Talks to the support service, so it runs outside a database transaction.
 */
class CheckSupportConnection :
    Command<SupportConnectionStatus>,
    RequiresPlatformRole,
    SelfManagedTransaction {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

@Component
class CheckSupportConnectionHandler(
    private val connection: DefaultSupportConnection,
) : CommandHandler<CheckSupportConnection, SupportConnectionStatus> {
    /** @throws SupportConnectionBusyException when a check or registration already runs, on any node. */
    override fun handle(command: CheckSupportConnection): SupportConnectionStatus {
        if (!connection.checkIfIdle()) throw SupportConnectionBusyException()
        return connection.status()
    }
}

/**
 * Registers this installation with the support service now, as an installation administrator
 * asked: applying for support. [companyName] and [adminEmail], when given, are who Epistola contacts,
 * as the administrator checked them; they are kept for registering again. Allowed while not
 * registered, after the claim code expired and after disconnecting; talks to the support service, so
 * it runs outside a database transaction.
 */
data class RegisterSupportConnection(val companyName: String? = null, val adminEmail: String? = null) :
    Command<SupportConnectionStatus>,
    RequiresPlatformRole,
    SelfManagedTransaction {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR

    init {
        if (companyName != null || adminEmail != null) {
            validate("companyName", !companyName.isNullOrBlank() && companyName.length <= MAX_CONTACT, ValidationCode.GENERIC) { "Enter the company or organisation name, at most $MAX_CONTACT characters." }
            validate("adminEmail", !adminEmail.isNullOrBlank() && adminEmail.length <= MAX_CONTACT && EMAIL.matches(adminEmail.trim()), ValidationCode.GENERIC) { "Enter the e-mail address Epistola can contact." }
        }
    }

    private companion object {
        /** The support service keeps at most 200 characters of each. */
        const val MAX_CONTACT = 200
        val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
    }
}

@Component
class RegisterSupportConnectionHandler(
    private val connection: DefaultSupportConnection,
) : CommandHandler<RegisterSupportConnection, SupportConnectionStatus> {
    override fun handle(command: RegisterSupportConnection): SupportConnectionStatus {
        val contact = command.companyName?.let {
            StoredSupportContact(it.trim(), command.adminEmail.orEmpty().trim(), EpistolaClock.instant(), SecurityContext.currentOrNull()?.displayName)
        }
        connection.registerNow(contact)
        return connection.status()
    }
}

/**
 * Forgets the registration and registers anew with a new key. Allowed after a refusal; while
 * pending only with [confirmed], because it invalidates the claim code shown so far.
 */
data class RegisterSupportConnectionAgain(val confirmed: Boolean = false) :
    Command<SupportConnectionStatus>,
    RequiresPlatformRole,
    SelfManagedTransaction {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

/** Registering again is not possible in the connection's current state. */
open class SupportRegistrationNotAllowedException(message: String) : RuntimeException(message)

/** A check or registration already runs, on this or another node; nothing waited for it. */
class SupportConnectionBusyException : SupportRegistrationNotAllowedException("A check or registration with the support service is already running; try again in a moment")

@Component
class RegisterSupportConnectionAgainHandler(
    private val connection: DefaultSupportConnection,
) : CommandHandler<RegisterSupportConnectionAgain, SupportConnectionStatus> {
    override fun handle(command: RegisterSupportConnectionAgain): SupportConnectionStatus {
        connection.invalidate()
        when (val state = connection.status().state) {
            is SupportConnectionState.Refused, is SupportConnectionState.Declined -> Unit

            is SupportConnectionState.Pending -> if (!command.confirmed) {
                throw SupportRegistrationNotAllowedException("Registering again invalidates claim code ${state.claimCode}; confirm to go ahead")
            }

            else -> throw SupportRegistrationNotAllowedException("Registering again is only possible after a refusal or while pending, not while ${state.javaClass.simpleName}")
        }
        connection.registerAgain()
        return connection.status()
    }
}

/**
 * Ends this installation's connection with Epistola support, as an installation administrator
 * asked: the support service ends the registration (it deletes the catalog mirror and keeps the
 * feedback for its retention period), this installation forgets it, and every tenant's sharing
 * choice and the installation's preset are cleared, so registering again asks everyone again.
 * [localOnly] skips telling the service, for when it cannot be reached. Talks to the support
 * service, so it runs outside a database transaction.
 */
data class DisconnectSupportConnection(val localOnly: Boolean = false) :
    Command<SupportDisconnected>,
    RequiresPlatformRole,
    SelfManagedTransaction {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

/** What [DisconnectSupportConnection] did. */
data class SupportDisconnected(val outcome: SupportDisconnectOutcome, val status: SupportConnectionStatus)

@Component
class DisconnectSupportConnectionHandler(
    private val connection: DefaultSupportConnection,
    private val disconnector: SupportDisconnector,
) : CommandHandler<DisconnectSupportConnection, SupportDisconnected> {
    /**
     * @throws SupportUnavailableException when the support service could not be told; nothing changed then.
     * @throws SupportRegistrationNotAllowedException when there is no registration to end.
     */
    override fun handle(command: DisconnectSupportConnection): SupportDisconnected {
        val outcome = disconnector.disconnect(command.localOnly, SecurityContext.currentOrNull()?.displayName)
        return SupportDisconnected(outcome, connection.status())
    }
}

/**
 * Selects the support services this installation uses, installation-wide; from now on the saved
 * selection wins over `epistola.support.services`. Names this version does not know are kept. No
 * network I/O: it works while the support service is unreachable. A deselected service stops at once
 * on this node and within a second on the others; the next token asks for the new selection.
 */
data class SaveSupportServices(val services: Set<String>) :
    Command<SupportServiceSelection>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

@Component
class SaveSupportServicesHandler(
    private val connection: DefaultSupportConnection,
) : CommandHandler<SaveSupportServices, SupportServiceSelection> {
    override fun handle(command: SaveSupportServices): SupportServiceSelection {
        validate("services", command.services.size <= MAX_SELECTED_SERVICES, ValidationCode.GENERIC) { "At most $MAX_SELECTED_SERVICES services can be selected." }
        validate("services", command.services.all { SERVICE_NAME.matches(it.trim().lowercase()) }, ValidationCode.GENERIC) { "A service name is malformed." }
        val by = SecurityContext.currentOrNull()?.displayName
        return connection.saveSelectedServices(command.services, by)
    }

    private companion object {
        /** The support service takes at most 16 scope names; so does the selection. */
        const val MAX_SELECTED_SERVICES = 16

        /** A scope name as the protocol allows it, within the service's 64 characters. */
        val SERVICE_NAME = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    }
}

/** How a scope is named to people. */
fun SupportScope.title(): String = when (this) {
    SupportScope.TELEMETRY -> "Telemetry"
    SupportScope.REQUESTS -> "Support requests"
    SupportScope.MIRROR -> "Catalog mirror"
    SupportScope.USAGE -> "Usage"
}
