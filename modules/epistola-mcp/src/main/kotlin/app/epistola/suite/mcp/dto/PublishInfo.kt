// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.stencils.commands.UpdateStencilInTemplateResult
import app.epistola.suite.templates.contracts.commands.PublishContractVersionResult

/**
 * The outcome of `publish_data_contract`. When the draft breaks the published contract and the
 * caller did not confirm, nothing is published: `published` is false and the breaking changes and
 * affected template versions say what confirming would do.
 */
data class ContractPublishInfo(
    val published: Boolean,
    /** The contract version that was published, or null when nothing was. */
    val publishedVersion: Int?,
    /** False when the draft removes or retypes something the published contract had. */
    val compatible: Boolean,
    val breakingChanges: List<BreakingChangeInfo>,
    /** Template versions whose data no longer fits the new contract. */
    val incompatibleVersions: List<IncompatibleVersionInfo>,
    /** Template versions moved onto the new contract version. */
    val upgradedVersionCount: Int,
) {
    companion object {
        fun from(result: PublishContractVersionResult): ContractPublishInfo = ContractPublishInfo(
            published = result.published,
            publishedVersion = result.publishedVersion?.id?.value,
            compatible = result.compatible,
            breakingChanges = result.breakingChanges.map { BreakingChangeInfo(it.type.name, it.path, it.description) },
            incompatibleVersions = result.incompatibleVersions.map {
                IncompatibleVersionInfo(it.variantKey.value, it.versionId.value, it.activeEnvironments)
            },
            upgradedVersionCount = result.upgradedVersionCount,
        )
    }
}

data class BreakingChangeInfo(val type: String, val path: String, val description: String)

data class IncompatibleVersionInfo(val variantId: String, val versionId: Int, val activeEnvironments: List<String>)

/**
 * The outcome of `upgrade_stencil_in_template`. The maps are keyed by the stencil node's id in the
 * template; an empty map means nothing was lost.
 */
data class StencilUpgradeInfo(
    val upgradedCount: Int,
    /** Placeholder fills whose placeholder no longer exists in the new version. */
    val droppedFills: Map<String, List<DroppedFillInfo>>,
    /** Parameter bindings whose parameter no longer exists in the new version. */
    val droppedBindings: Map<String, List<DroppedBindingInfo>>,
    /** Required parameters of the new version with no binding and no default. Bind them before publishing. */
    val unboundRequired: Map<String, List<String>>,
) {
    companion object {
        fun from(result: UpdateStencilInTemplateResult): StencilUpgradeInfo = StencilUpgradeInfo(
            upgradedCount = result.upgradedCount,
            droppedFills = result.droppedFills.mapValues { (_, fills) -> fills.map { DroppedFillInfo(it.name, it.contentSummary) } },
            droppedBindings = result.droppedBindings.mapValues { (_, bindings) ->
                bindings.map { DroppedBindingInfo(it.name, it.expression) }
            },
            unboundRequired = result.unboundRequired,
        )
    }
}

data class DroppedFillInfo(val placeholder: String, val contentSummary: String)

data class DroppedBindingInfo(val parameter: String, val expression: String)
