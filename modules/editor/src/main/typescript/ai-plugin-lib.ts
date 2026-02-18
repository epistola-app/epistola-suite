/**
 * @module @epistola/editor/ai-plugin
 *
 * AI Chat Plugin — separate entry point to keep AI code out of the
 * main template-editor bundle. Host page loads this dynamically
 * when the AI plugin is enabled.
 *
 * Public API:
 *   createAiPlugin(options)      → EditorPlugin
 *   createMockTransport(options) → SendMessageFn
 */

import './styles/ai-panel.css'

export { createAiPlugin, type AiPluginOptions } from './plugins/ai/ai-plugin.js'
export { createMockTransport, type MockTransportOptions } from './plugins/ai/mock-transport.js'
export type { SendMessageFn, ChatRequest, ChatChunk, ChatAttachment, AiProposal } from './plugins/ai/types.js'
