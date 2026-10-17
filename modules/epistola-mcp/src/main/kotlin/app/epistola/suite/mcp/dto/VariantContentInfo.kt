// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.templates.model.TemplateDocument

/** A variant's working model after a write; [templateModel] is set only when asked for. */
data class VariantContentInfo(
    val variantId: String,
    val templateModel: TemplateDocument? = null,
)
