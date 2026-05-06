package app.epistola.suite.mcp.dto

/**
 * Result of a document preview render. The PDF bytes are returned base64-encoded
 * since MCP transports JSON; AI clients that can decode binary should decode
 * `data` as base64 to recover the PDF.
 */
data class PreviewResult(
    /** Always "application/pdf" for the current renderer. */
    val mediaType: String,
    /** Base64-encoded PDF bytes. */
    val data: String,
    /** Decoded byte length. */
    val byteCount: Int,
)
