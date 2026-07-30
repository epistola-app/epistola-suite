// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.templates.model.TemplateVersion
import java.time.OffsetDateTime

/** Exact persisted content and frozen rendering context for one template version. */
data class VersionContentInfo(
    val id: Int,
    val variantId: String,
    val status: String,
    val templateModel: Any,
    val createdAt: OffsetDateTime,
    val publishedAt: OffsetDateTime?,
    val archivedAt: OffsetDateTime?,
    val renderingDefaultsVersion: Int?,
    val resolvedTheme: Any?,
    val contractVersion: Int?,
) {
    companion object {
        fun from(version: TemplateVersion): VersionContentInfo = VersionContentInfo(
            id = version.id.value,
            variantId = version.variantKey.value,
            status = version.status.name.lowercase(),
            templateModel = version.templateModel,
            createdAt = version.createdAt,
            publishedAt = version.publishedAt,
            archivedAt = version.archivedAt,
            renderingDefaultsVersion = version.renderingDefaultsVersion,
            resolvedTheme = version.resolvedTheme,
            contractVersion = version.contractVersion?.value,
        )
    }
}
