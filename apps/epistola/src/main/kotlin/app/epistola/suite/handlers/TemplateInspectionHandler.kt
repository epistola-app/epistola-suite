// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.contracts.model.ContractVersion
import app.epistola.suite.templates.contracts.model.ContractVersionStatus
import app.epistola.suite.templates.contracts.queries.GetLatestContractVersion
import app.epistola.suite.templates.contracts.queries.GetLatestPublishedContractVersion
import app.epistola.suite.templates.model.VariantSummary
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.GetEditorContext
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.templates.validation.JsonSchemaValidator
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Returns stable, semantic facts about a template for trusted embedded hosts.
 * This stays in the UI layer: it uses the current UI session and its normal
 * permission checks, and deliberately does not expand the public REST API.
 */
@Component
class TemplateInspectionHandler(
    private val objectMapper: ObjectMapper,
    private val jsonSchemaValidator: JsonSchemaValidator,
) {
    fun assess(request: ServerRequest): ServerResponse {
        val body = try {
            objectMapper.readTree(request.body(String::class.java)) as? ObjectNode
        } catch (_: Exception) {
            null
        }
            ?: return ServerResponse.badRequest().build()
        val resources = body.get("resources") ?: return ServerResponse.badRequest().build()
        val predicates = body.get("predicates") ?: return ServerResponse.badRequest().build()
        val tenant = try {
            TenantId(TenantKey.of(request.pathVariable("tenantId")))
        } catch (_: IllegalArgumentException) {
            return ServerResponse.badRequest().build()
        }
        val byId = resources.associateBy { it.get("id")?.asString() }
        val results = objectMapper.createArrayNode()
        predicates.forEach { predicate ->
            val resource = byId[predicate.get("resource")?.asString()]
            val status = resource?.let { evaluate(tenant, it as? ObjectNode, predicate as? ObjectNode) } ?: "unknown"
            results.addObject().apply {
                set("predicate", predicate as? ObjectNode ?: objectMapper.createObjectNode())
                put("status", status)
            }
        }
        val response = objectMapper.createObjectNode().set("results", results)
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(objectMapper.writeValueAsString(response))
    }

    private fun evaluate(tenant: TenantId, resource: ObjectNode?, predicate: ObjectNode?): String {
        if (resource?.get("resourceType")?.asString() != "template" || predicate == null) return "unknown"
        val template = try {
            TemplateId(TemplateKey.of(resource.get("key")?.asString() ?: return "unknown"), CatalogId(CatalogKey.of(resource.get("catalogKey")?.asString() ?: return "unknown"), tenant))
        } catch (_: IllegalArgumentException) {
            return "unknown"
        }
        if (GetDocumentTemplate(template).query() == null) return "unsatisfied"
        return when (predicate.get("type")?.asString()) {
            "resource-exists" -> "satisfied"
            "data-contract-published" -> if (hasPublishedContract(GetLatestPublishedContractVersion(template).query())) "satisfied" else "unsatisfied"
            "data-example-valid" -> {
                val contract = GetLatestContractVersion(template).query()
                val exampleName = predicate.get("exampleName")?.asString()
                if (exampleName != null && hasValidNamedExample(contract, exampleName)) "satisfied" else "unsatisfied"
            }
            "template-expression-present" -> {
                val variant = selectVariant(
                    GetVariantSummaries(template).query(),
                    predicate.get("variant")?.asString(),
                )
                val editor = variant?.let { GetEditorContext(VariantId(it.id, template)).query() }
                val path = predicate.get("path")?.asString()
                if (path != null && path in (editor?.let { expressions(it.templateModel.nodes.values.map { node -> node.props }) } ?: emptySet())) "satisfied" else "unsatisfied"
            }
            "data-contract-property" -> {
                val contract = GetLatestContractVersion(template).query()
                val path = predicate.get("path")?.asString()
                if (path != null && matchesDataContractProperty(contract?.dataModel, path, predicate)) "satisfied" else "unsatisfied"
            }
            "default-variant-heading-expression" -> {
                val variant = GetVariantSummaries(template).query().firstOrNull { it.isDefault }
                val editor = variant?.let { GetEditorContext(VariantId(it.id, template)).query() }
                if (predicate.get("path")?.asString() in (editor?.let { headingExpressions(it.templateModel.nodes.values.map { node -> node.props }) } ?: emptySet())) "satisfied" else "unsatisfied"
            }
            "default-variant-published" -> if (hasPublishedDefaultVariant(GetVariantSummaries(template).query())) "satisfied" else "unsatisfied"
            else -> "unknown"
        }
    }

    internal fun hasPublishedDefaultVariant(variants: List<VariantSummary>): Boolean = variants.any { it.isDefault && it.publishedVersions.isNotEmpty() }

    internal fun hasPublishedContract(contract: ContractVersion?): Boolean = contract?.status == ContractVersionStatus.PUBLISHED

    internal fun hasValidNamedExample(contract: ContractVersion?, exampleName: String): Boolean {
        val dataModel = contract?.dataModel ?: return false
        val example = contract.dataExamples.firstOrNull { it.name == exampleName } ?: return false
        return runCatching { jsonSchemaValidator.validate(dataModel, example.data).isEmpty() }.getOrDefault(false)
    }

    internal fun selectVariant(variants: List<VariantSummary>, variantKey: String?): VariantSummary? = if (variantKey == null) {
        variants.firstOrNull { it.isDefault }
    } else {
        variants.firstOrNull { it.id.value == variantKey }
    }

    /**
     * Checks only the property's own schema at an exact dot-separated path.
     * References and composition keywords are deliberately not resolved here;
     * training predicates must describe the authored, directly inspectable shape.
     */
    internal fun matchesDataContractProperty(
        schema: ObjectNode?,
        path: String,
        predicate: ObjectNode,
    ): Boolean {
        val segments = path.split('.')
        if (segments.any(String::isBlank)) return false

        var current = schema ?: return false
        var required = false
        for ((index, segment) in segments.withIndex()) {
            val properties = current.get("properties") as? ObjectNode ?: return false
            val property = properties.get(segment) as? ObjectNode ?: return false
            required = current.get("required")?.any { it.asString(null) == segment } == true
            if (index == segments.lastIndex) {
                val requestedRequired = predicate.get("required")
                if (requestedRequired != null && requestedRequired.isBoolean && requestedRequired.asBoolean() != required) return false

                val requestedType = predicate.get("dataType")?.takeIf { it.isString }?.asString()
                if (requestedType != null && property.get("type")?.takeIf { it.isString }?.asString() != requestedType) return false

                val requestedFormat = predicate.get("format")?.takeIf { it.isString }?.asString()
                if (requestedFormat != null && property.get("format")?.takeIf { it.isString }?.asString() != requestedFormat) return false

                val requestedMinimum = predicate.get("minimum")?.takeIf { it.isNumber }?.asDouble()
                if (requestedMinimum != null && property.get("minimum")?.takeIf { it.isNumber }?.asDouble() != requestedMinimum) return false

                return true
            }
            current = property
        }
        return false
    }

    internal fun requiredFields(schema: tools.jackson.databind.node.ObjectNode?): Set<String> = buildSet {
        fun collect(node: tools.jackson.databind.node.ObjectNode, prefix: String = "") {
            val required = node.get("required")?.mapNotNull { it.asString(null) }.orEmpty().toSet()
            val properties = node.get("properties") as? tools.jackson.databind.node.ObjectNode ?: return
            properties.properties().forEach { (name, child) ->
                val path = if (prefix.isEmpty()) name else "$prefix.$name"
                if (name in required) add(path)
                (child as? tools.jackson.databind.node.ObjectNode)?.let { collect(it, path) }
            }
        }
        schema?.let(::collect)
    }

    internal fun headingExpressions(nodeProps: Collection<Map<String, Any?>?>): Set<String> = buildSet {
        nodeProps.forEach { props -> collectHeadingExpressions(props?.get("content"), this) }
    }

    internal fun expressions(nodeProps: Collection<Map<String, Any?>?>): Set<String> = buildSet {
        nodeProps.forEach { props -> collectExpressions(props?.get("content"), this) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun collectExpressions(value: Any?, expressions: MutableSet<String>) {
        when (value) {
            is Map<*, *> -> {
                if (value["type"] == "expression") {
                    val attrs = value["attrs"] as? Map<String, Any?>
                    (attrs?.get("expression") as? String)?.takeIf { it.isNotBlank() }?.let(expressions::add)
                }
                (value["content"] as? Collection<*>)?.forEach { collectExpressions(it, expressions) }
            }
            is Collection<*> -> value.forEach { collectExpressions(it, expressions) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun collectHeadingExpressions(value: Any?, expressions: MutableSet<String>, inHeading: Boolean = false) {
        when (value) {
            is Map<*, *> -> {
                val type = value["type"] as? String
                val heading = inHeading || type == "heading"
                if (heading && type == "expression") {
                    val attrs = value["attrs"] as? Map<String, Any?>
                    (attrs?.get("expression") as? String)?.takeIf { it.isNotBlank() }?.let(expressions::add)
                }
                (value["content"] as? Collection<*>)?.forEach { collectHeadingExpressions(it, expressions, heading) }
            }
            is Collection<*> -> value.forEach { collectHeadingExpressions(it, expressions, inHeading) }
        }
    }
}
