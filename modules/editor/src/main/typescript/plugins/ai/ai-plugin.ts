/**
 * AI chat plugin factory.
 *
 * Creates an EditorPlugin that:
 * - Contributes a sidebar tab with an AI chat panel
 * - Creates and manages an AiChatService instance
 * - Wires up state change notifications between service and panel
 */

import { html } from 'lit'
import type { EditorPlugin, PluginContext } from '../types.js'
import type { SendMessageFn } from './types.js'
import { AiChatService } from './ai-chat-service.js'
import './ai-panel.js'

export interface AiPluginOptions {
  /** Transport function for sending messages. Host page provides this. */
  sendMessage: SendMessageFn
  /** Optional conversation ID (defaults to auto-generated) */
  conversationId?: string
}

export function createAiPlugin(options: AiPluginOptions): EditorPlugin {
  let chatService: AiChatService | undefined

  return {
    id: 'ai',

    sidebarTab: {
      id: 'ai',
      label: 'AI',
      icon: 'sparkles',
      render: (ctx: PluginContext) => {
        return html`
          <epistola-ai-panel
            .chatService=${chatService}
            .engine=${ctx.engine}
            .doc=${ctx.doc}
            .selectedNodeId=${ctx.selectedNodeId}
          ></epistola-ai-panel>
        `
      },
    },

    init(_ctx: PluginContext) {
      chatService = new AiChatService(
        options.sendMessage,
        (state) => {
          // Find the panel and push state to it
          const panel = document.querySelector('epistola-ai-panel')
          if (panel) {
            panel.handleStateChange(state)
          }
        },
        options.conversationId,
      )

      return () => {
        chatService?.dispose()
        chatService = undefined
      }
    },
  }
}
