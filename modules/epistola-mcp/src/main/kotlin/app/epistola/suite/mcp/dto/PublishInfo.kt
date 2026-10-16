// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

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
