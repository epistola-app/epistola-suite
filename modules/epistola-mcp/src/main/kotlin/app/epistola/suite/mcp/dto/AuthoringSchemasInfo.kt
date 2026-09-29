// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import tools.jackson.databind.JsonNode

/** The JSON Schemas and style registry the `epistola-catalog` artifact ships, passed through unchanged. */
data class AuthoringSchemasInfo(
    val templateDocument: JsonNode,
    val theme: JsonNode,
    val shared: JsonNode,
    val styleRegistry: JsonNode,
)
