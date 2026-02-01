package app.epistola.suite.testing

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.Query
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.TemplateModel
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.model.TemplateVersion
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.DeleteTenant
import org.springframework.stereotype.Component

/**
 * DSL marker to scope the scenario builder and prevent scope leakage.
 */
@DslMarker
annotation class ScenarioDsl

/**
 * Factory component for creating scenarios with automatic cleanup.
 *
 * Usage:
 * ```kotlin
 * scenario {
 *     given {
 *         val tenant = tenant("Test Tenant")
 *         val template = execute(CreateDocumentTemplate(tenant.id, "Invoice"))
 *         DocumentSetup(tenant, template)
 *     }.whenever { setup ->
 *         execute(GenerateDocument(...))
 *     }.then { setup, result ->
 *         assertThat(result.id).isNotNull()
 *     }
 * }
 * ```
 */
@Component
class ScenarioFactory(
    private val mediator: Mediator,
) {
    /**
     * Creates and executes a scenario with automatic resource cleanup.
     */
    fun <T> scenario(block: ScenarioBuilder.() -> T): T {
        val builder = ScenarioBuilder(mediator)
        return try {
            builder.block()
        } finally {
            builder.cleanup()
        }
    }

    /**
     * Runs the given block with the mediator bound to the current scope.
     * This enables use of Command.execute() and Query.query() extension functions.
     */
    fun <T> withMediator(block: () -> T): T = MediatorContext.runWithMediator(mediator, block)
}

/**
 * Builder for constructing test scenarios using Given-When-Then pattern.
 */
@ScenarioDsl
class ScenarioBuilder(
    private val mediator: Mediator,
) {
    private val cleanupActions = mutableListOf<() -> Unit>()

    /**
     * Define the test setup in the given block.
     * Returns a [GivenResult] that can be chained with [GivenResult.whenever].
     *
     * @param G the type of the setup data returned from the block
     * @param block the setup block that creates test data and returns a typed result
     * @return [GivenResult] containing the setup data for use in subsequent blocks
     */
    fun <G> given(block: GivenScope.() -> G): GivenResult<G> {
        val scope = GivenScope()
        val value = scope.block()
        return GivenResult(value, mediator)
    }

    /**
     * Registers a cleanup action to be executed when the scenario completes.
     * Actions are executed in reverse order (LIFO).
     */
    internal fun registerCleanup(action: () -> Unit) {
        cleanupActions.add(action)
    }

    /**
     * Executes all registered cleanup actions in reverse order.
     */
    internal fun cleanup() {
        cleanupActions.asReversed().forEach { action ->
            runCatching { action() }
        }
    }

    /**
     * Scope for the "given" block providing test data setup methods.
     */
    @ScenarioDsl
    inner class GivenScope {
        // Capture mediator reference to work with DSL marker restrictions
        private val scopeMediator = this@ScenarioBuilder.mediator

        /**
         * Executes a command and returns its result.
         */
        fun <R> execute(command: Command<R>): R = scopeMediator.send(command)

        /**
         * Executes a query and returns its result.
         */
        fun <R> query(query: Query<R>): R = scopeMediator.query(query)

        /**
         * Creates a tenant with automatic cleanup when the scenario ends.
         *
         * @param name the name of the tenant to create
         * @return the created [Tenant]
         */
        fun tenant(name: String): Tenant {
            val tenant = scopeMediator.send(CreateTenant(id = TenantId.generate(), name = name))
            this@ScenarioBuilder.registerCleanup {
                scopeMediator.send(DeleteTenant(tenant.id))
            }
            return tenant
        }

        /**
         * Creates a document template.
         *
         * @param tenantId the tenant ID
         * @param name the template name
         * @return the created [DocumentTemplate]
         */
        fun template(
            tenantId: TenantId,
            name: String,
        ): DocumentTemplate = scopeMediator.send(
            CreateDocumentTemplate(
                id = TemplateId.generate(),
                tenantId = tenantId,
                name = name,
            ),
        )

        /**
         * Creates a template variant.
         *
         * @param tenantId the tenant ID
         * @param templateId the template ID
         * @param title optional variant title
         * @param description optional variant description
         * @param tags optional tags map
         * @return the created [TemplateVariant]
         */
        fun variant(
            tenantId: TenantId,
            templateId: TemplateId,
            title: String? = null,
            description: String? = null,
            tags: Map<String, String> = emptyMap(),
        ): TemplateVariant = scopeMediator.send(
            CreateVariant(
                id = VariantId.generate(),
                tenantId = tenantId,
                templateId = templateId,
                title = title,
                description = description,
                tags = tags,
            ),
        )!!

        /**
         * Updates a template version draft with a template model.
         *
         * @param tenantId the tenant ID
         * @param templateId the template ID
         * @param variantId the variant ID
         * @param templateModel the template model to set
         * @return the updated [TemplateVersion]
         */
        fun version(
            tenantId: TenantId,
            templateId: TemplateId,
            variantId: VariantId,
            templateModel: TemplateModel,
        ): TemplateVersion = scopeMediator.send(
            UpdateDraft(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
                templateModel = templateModel,
            ),
        )!!
    }
}

/**
 * Result of the "given" block, holding the setup data.
 * Provides the [whenever] method to chain the action phase.
 *
 * @param G the type of the setup data
 */
class GivenResult<G>(
    val value: G,
    private val mediator: Mediator,
) {
    /**
     * Defines the action to test, with access to the setup data.
     *
     * @param W the type of the result from the action
     * @param block the action block that receives the setup data and returns a result
     * @return [WhenResult] containing both setup and action result
     */
    fun <W> whenever(block: WhenScope.(G) -> W): WhenResult<G, W> {
        val scope = WhenScope(mediator)
        val result = scope.block(value)
        return WhenResult(value, result)
    }
}

/**
 * Scope for the "whenever" block providing command/query execution methods.
 */
@ScenarioDsl
class WhenScope(
    private val mediator: Mediator,
) {
    /**
     * Executes a command and returns its result.
     */
    fun <R> execute(command: Command<R>): R = mediator.send(command)

    /**
     * Executes a query and returns its result.
     */
    fun <R> query(query: Query<R>): R = mediator.query(query)
}

/**
 * Result of the "whenever" block, holding both setup and action results.
 * Provides the [then] method to chain the assertion phase.
 *
 * @param G the type of the setup data
 * @param W the type of the action result
 */
class WhenResult<G, W>(
    val given: G,
    val result: W,
) {
    /**
     * Defines assertions on the setup and action results.
     *
     * @param block the assertion block that receives both setup data and action result
     */
    fun then(block: ThenScope.(G, W) -> Unit) {
        val scope = ThenScope()
        scope.block(given, result)
    }
}

/**
 * Scope for the "then" block. Currently a marker class for DSL consistency.
 * Assertions are made using the parameters passed to the block.
 */
@ScenarioDsl
class ThenScope

// ============================================================================
// Reusable setup data classes
// ============================================================================

/**
 * Common setup data for document generation tests.
 * Contains a tenant, template, variant, and version ready for document generation.
 */
data class DocumentSetup(
    val tenant: Tenant,
    val template: DocumentTemplate,
    val variant: TemplateVariant,
    val version: TemplateVersion,
)
