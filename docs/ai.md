# AI Plugin Design Document

## Overview

This document describes the design for the AI assistant plugin — the first plugin built on Epistola's [plugin architecture](plugins.md). The AI plugin enables users to build and modify templates through a conversational interface — describing what they want in natural language and having the AI produce structural changes to the template document.

For the general plugin model (backend auto-configuration, frontend `EditorPlugin` interface, dynamic sidebar), see [docs/plugins.md](plugins.md). This document focuses on the AI-specific design.

### Goals

- **Conversational editing**: An AI panel in the editor sidebar where users can describe template changes
- **Reference uploads**: Upload existing templates (PDF, DOCX) as context for the AI to replicate or draw from
- **Multi-provider**: Support Claude, OpenAI, and Mistral behind a provider abstraction
- **Backend-mediated**: All AI communication goes through the backend — no direct browser-to-provider calls
- **Two change modes**: Command-based (individual InsertNode, UpdateNodeProps, etc.) and full document replacement
- **Structure first**: Initial focus on template structure; styling support comes in a later phase

### Non-goals (for now)

- Direct chat with an AI from the Epistola UI outside the editor context
- Autonomous multi-step agents that plan and execute without user approval
- Training or fine-tuning models on template data
- AI-powered data model generation

---

## Architecture Overview

The AI plugin is a self-contained Gradle module (`modules/plugins/ai`) with its own routes, handlers, and services. It is conditionally enabled via `epistola.plugins.ai.enabled`.

```
┌──────────────────────────────────────────────────────────────┐
│  Browser                                                      │
│  ┌─────────────────────┐  EditorPlugin  ┌──────────────────┐ │
│  │  <epistola-ai-panel> │ ◄───────────── │   editor.html    │ │
│  │  (AI plugin sidebar) │  init/context  │   (host page)    │ │
│  └────────┬────────────┘               └──────┬───────────┘ │
│           │ SSE stream                         │ fetch       │
└───────────┼────────────────────────────────────┼─────────────┘
            │                                    │
            ▼                                    ▼
┌───────────────────────────────────────────────────────────────┐
│  Backend (Spring Boot)                                        │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │  modules/plugins/ai  (@AutoConfiguration)                │ │
│  │  ┌────────────────────┐    ┌───────────────────────────┐│ │
│  │  │  AiRoutes (router) │───▶│  AiService                ││ │
│  │  │  AiChatHandler     │    │  - conversation management ││ │
│  │  │  ReferenceUploadHdl│    │  - system prompt assembly  ││ │
│  │  └────────────────────┘    │  - response parsing        ││ │
│  │                            └──────────┬────────────────┘│ │
│  │                                       │                  │ │
│  │                            ┌──────────▼────────────────┐│ │
│  │                            │  AiProvider (interface)     ││ │
│  │                            │  ├─ ClaudeProvider         ││ │
│  │                            │  ├─ OpenAiProvider         ││ │
│  │                            │  └─ MistralProvider        ││ │
│  │                            └────────────────────────────┘│ │
│  └─────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────┘
```

---

## Backend Architecture

All AI backend code lives in the plugin module `modules/plugins/ai/`. The module depends on `epistola-core` for shared types (e.g. `TemplateDocument`, `EpistolaPlugin`) but is otherwise self-contained.

### Module Structure

```
modules/plugins/ai/
├── build.gradle.kts
└── src/main/kotlin/app/epistola/suite/plugins/ai/
    ├── AiPluginAutoConfiguration.kt   # @AutoConfiguration + @ConditionalOnProperty
    ├── AiPluginProperties.kt          # @ConfigurationProperties("epistola.plugins.ai")
    ├── AiService.kt                   # Orchestration
    ├── AiProvider.kt                  # Provider interface + shared types
    ├── providers/
    │   ├── ClaudeProvider.kt
    │   ├── OpenAiProvider.kt
    │   └── MistralProvider.kt
    ├── routes/
    │   └── AiRoutes.kt               # @Bean RouterFunction
    └── handlers/
        ├── AiChatHandler.kt
        └── ReferenceUploadHandler.kt
```

### Auto-Configuration

The plugin provides a Spring Boot auto-configuration entry point:

```kotlin
// modules/plugins/ai/.../AiPluginAutoConfiguration.kt

@AutoConfiguration
@ConditionalOnProperty("epistola.plugins.ai.enabled", havingValue = "true")
@ConfigurationPropertiesScan
class AiPluginAutoConfiguration {

    @Bean
    fun aiPlugin(): EpistolaPlugin = object : EpistolaPlugin {
        override val id = "ai"
        override val name = "AI Assistant"
        override val version = "1.0.0"
    }

    @Bean
    fun aiService(
        providers: Map<String, AiProvider>,
        properties: AiPluginProperties,
    ): AiService = AiService(providers, properties)

    @Bean
    fun aiRoutes(aiChatHandler: AiChatHandler, referenceUploadHandler: ReferenceUploadHandler): RouterFunction<ServerResponse> {
        return router {
            "/tenants/{tenantId}/templates/{templateId}/ai".nest {
                POST("/chat", aiChatHandler::handleChat)
                POST("/references", referenceUploadHandler::handleUpload)
            }
        }
    }
}
```

When `epistola.plugins.ai.enabled` is not `true` (the default), none of these beans are created. See [docs/plugins.md](plugins.md) for the general auto-configuration pattern.

### Provider Abstraction

A simple interface decoupling the orchestration layer from specific AI providers.

```kotlin
// modules/plugins/ai/.../AiProvider.kt

interface AiProvider {
    val name: String

    fun chat(
        messages: List<AiMessage>,
        options: AiOptions = AiOptions(),
    ): Flow<AiResponseChunk>
}

data class AiMessage(
    val role: AiRole,
    val content: String,
)

enum class AiRole { SYSTEM, USER, ASSISTANT }

data class AiOptions(
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
)

sealed interface AiResponseChunk {
    data class Text(val content: String) : AiResponseChunk
    data class Done(val usage: AiUsage?) : AiResponseChunk
    data class Error(val message: String) : AiResponseChunk
}

data class AiUsage(
    val inputTokens: Int,
    val outputTokens: Int,
)
```

#### Why not Spring AI?

Spring AI provides a higher-level abstraction over AI providers, but for our use case we need:

- Full control over system prompt construction and streaming behaviour
- Custom response parsing (extracting JSON proposals from freeform text)
- Provider-specific tuning without fighting the framework
- Minimal dependency surface — Spring AI pulls in a large dependency tree

The `AiProvider` interface is intentionally thin. If Spring AI matures into a better fit, migration would only affect the provider implementations.

### Provider Implementations

Each provider implementation uses Spring's `RestClient` (or `WebClient` for streaming) to call the respective API:

| Provider | Class | API |
|----------|-------|-----|
| Claude | `ClaudeProvider` | `api.anthropic.com/v1/messages` (streaming) |
| OpenAI | `OpenAiProvider` | `api.openai.com/v1/chat/completions` (streaming) |
| Mistral | `MistralProvider` | `api.mistral.ai/v1/chat/completions` (streaming) |

All providers return `Flow<AiResponseChunk>`, normalising the different streaming formats (Claude's SSE events vs OpenAI's `data: [DONE]` convention) into a uniform stream.

### Configuration

```yaml
# application.yaml (or per-profile overrides)
epistola:
  plugins:
    ai:
      enabled: true
      default-provider: claude
      providers:
        claude:
          api-key: ${CLAUDE_API_KEY:}
          model: claude-sonnet-4-5-20250929
          base-url: https://api.anthropic.com
          max-tokens: 4096
        openai:
          api-key: ${OPENAI_API_KEY:}
          model: gpt-4o
          base-url: https://api.openai.com
          max-tokens: 4096
        mistral:
          api-key: ${MISTRAL_API_KEY:}
          model: mistral-large-latest
          base-url: https://api.mistral.ai
          max-tokens: 4096
      conversation:
        ttl-minutes: 60        # Conversations expire after inactivity
        max-messages: 100       # Max messages per conversation
      upload:
        max-file-size-mb: 10
        allowed-types:
          - application/pdf
          - application/vnd.openxmlformats-officedocument.wordprocessingml.document
```

### AiService (Orchestrator)

`AiService` is the core orchestration layer. It manages:

1. **Conversation lifecycle** — Creating, retrieving, and expiring conversations
2. **System prompt assembly** — Building context from the template schema, component types, current document state, and uploaded references
3. **Response parsing** — Extracting structured proposals from AI text responses

```kotlin
class AiService(
    private val providers: Map<String, AiProvider>,
    private val config: AiPluginProperties,
) {
    fun chat(
        conversationId: String,
        userMessage: String,
        currentDocument: TemplateDocument,
        providerName: String? = null,
    ): Flow<AiStreamEvent>

    fun uploadReference(
        conversationId: String,
        file: MultipartFile,
    ): ReferenceUploadResult

    fun getConversation(id: String): Conversation?
    fun createConversation(): Conversation
}
```

#### Conversation Storage

Conversations are stored **in-memory** with TTL-based expiry. They are ephemeral working sessions, not audit records. This keeps the implementation simple and avoids schema changes.

```kotlin
data class Conversation(
    val id: String,
    val messages: MutableList<AiMessage>,
    val references: MutableList<Reference>,
    val createdAt: Instant,
    var lastAccessedAt: Instant,
)

data class Reference(
    val filename: String,
    val extractedText: String,
    val mimeType: String,
)
```

A scheduled task (`@Scheduled`) evicts conversations that have been inactive for longer than `ttl-minutes`.

### File Upload & Text Extraction

Reference documents (PDF, DOCX) are parsed server-side to extract text content that gets included in the AI's context window.

| Format | Library | Notes |
|--------|---------|-------|
| PDF | Apache PDFBox | Already available in the project (used by generation module) |
| DOCX | Apache POI | New dependency; lightweight text extraction only |

Extracted text is truncated to a configurable maximum (e.g. 10,000 characters) to stay within token limits. The text is stored in the `Conversation` object, not persisted to disk or database.

### SSE Streaming Endpoint

The chat endpoint uses Server-Sent Events (SSE) to stream responses back to the browser. This is a natural fit because:

- AI responses are unidirectional (server → client)
- SSE works over standard HTTP (no WebSocket upgrade needed)
- Spring Boot has excellent SSE support via `SseEmitter` or reactive `Flux`
- The browser `EventSource` API handles reconnection automatically

#### Why SSE over WebSocket?

WebSocket would work but adds complexity:
- Requires a separate connection lifecycle
- Needs its own CSRF/auth handling
- Bidirectional capability is unnecessary — the user sends discrete chat messages via POST

#### Endpoint Design

```
POST /tenants/{tenantId}/templates/{templateId}/ai/chat
Content-Type: application/json
Accept: text/event-stream

{
  "conversationId": "conv_abc123",
  "message": "Add a header with the company name and a paragraph for the introduction",
  "document": { ... current TemplateDocument ... }
}
```

Response stream (SSE events):

```
event: text
data: {"content": "I'll add a header and introduction paragraph. "}

event: text
data: {"content": "Here's my proposal:\n\n```json\n{\"type\": ..."}

event: proposal
data: {"changes": [...], "description": "Add header and introduction paragraph"}

event: done
data: {"usage": {"inputTokens": 1200, "outputTokens": 450}}
```

Event types:

| Event | Purpose |
|-------|---------|
| `text` | Streaming text content (displayed as the AI "types") |
| `proposal` | Parsed structured change proposal (extracted from the AI's response) |
| `done` | Stream complete, includes token usage |
| `error` | An error occurred |

### Reference Upload Endpoint

```
POST /tenants/{tenantId}/templates/{templateId}/ai/references
Content-Type: multipart/form-data

file: <PDF or DOCX>
conversationId: conv_abc123
```

Response:

```json
{
  "filename": "invoice-template.pdf",
  "extractedLength": 4523,
  "conversationId": "conv_abc123"
}
```

### Routes Live in the Plugin Module

Unlike the current pattern where UI handler routes are in `apps/epistola/handlers/`, the AI plugin's routes live inside the plugin module itself (`modules/plugins/ai/.../routes/AiRoutes.kt`). This keeps the plugin self-contained — adding it means adding a Gradle dependency and enabling the config, not modifying `apps/epistola` handler code.

Spring discovers `@Bean RouterFunction` beans from the auto-configuration class automatically. See [docs/plugins.md](plugins.md) for the rationale behind this design choice.

---

## Frontend Architecture

The AI plugin uses the `EditorPlugin` interface defined in the [plugin architecture](plugins.md) to contribute a sidebar tab to the editor.

### AI as an EditorPlugin

The AI assistant implements the `EditorPlugin` interface, contributing a sidebar tab for the chat panel:

```typescript
// modules/editor/src/main/typescript/plugins/ai/ai-plugin.ts

import type { EditorPlugin, PluginContext, PluginDisposeFn } from '../types.js'

export interface AiPluginOptions {
  /** Tenant context for building endpoint URLs */
  tenantId: string
  templateId: string
  /** CSRF token provider (host-page concern) */
  getCsrfToken: () => string
}

export function createAiPlugin(options: AiPluginOptions): EditorPlugin {
  return {
    id: 'ai',

    sidebarTab: {
      id: 'ai',
      label: 'AI',
      icon: 'sparkles',
      render: (context: PluginContext) => {
        // Renders <epistola-ai-panel> with current context
      },
    },

    init(context: PluginContext): PluginDisposeFn {
      const chatService = new AiChatService(/* ... */)
      // Wire up SSE streaming to backend endpoints
      return () => chatService.dispose()
    },
  }
}
```

The AI panel is no longer a hardcoded 4th tab in `EpistolaSidebar.ts`. Instead, the sidebar's dynamic tab system (see [plugins.md](plugins.md#dynamic-sidebar)) picks up the AI tab from the plugin's `sidebarTab` contribution.

### AI Panel Component

The `<epistola-ai-panel>` Lit component renders:
- A scrollable message thread (user messages + AI responses)
- A text input area for composing messages
- Reference file upload (drag-and-drop or file picker)
- Proposal cards with Apply / Reject buttons when the AI suggests changes

```typescript
// modules/editor/src/main/typescript/plugins/ai/EpistolaAiPanel.ts

@customElement('epistola-ai-panel')
export class EpistolaAiPanel extends LitElement {
  @property({ attribute: false }) engine?: EditorEngine
  @property({ attribute: false }) doc?: TemplateDocument

  @state() private _messages: ChatMessage[] = []
  @state() private _streaming: boolean = false
  @state() private _pendingProposal: AiProposal | null = null
}
```

### Host Page Wiring

The host page (Thymeleaf) conditionally constructs the AI plugin based on backend-provided `enabledPlugins` data. HTTP concerns (endpoint URLs, CSRF tokens) stay in the host page — the plugin receives them via its factory function:

```javascript
// editor.html — conditional AI plugin loading
const plugins = [];

if (window.ENABLED_PLUGINS.includes('ai')) {
  const { createAiPlugin } = await import('/editor/plugins/ai.js');
  plugins.push(createAiPlugin({
    tenantId: window.TENANT_ID,
    templateId: window.TEMPLATE_ID,
    getCsrfToken: window.getCsrfToken,
  }));
}

mountEditor({
  container: document.getElementById('editor-container'),
  template: window.TEMPLATE_MODEL,
  plugins,
  onSave: async (template) => { /* ... */ },
  onFetchPreview: async (doc, data, signal) => { /* ... */ },
});
```

This replaces the earlier design where `onAiChat` and `onAiUpload` were direct properties on `EditorOptions`. Instead, HTTP communication is encapsulated inside the AI plugin's `init()` function, which constructs `AiChatService` with fetch-based SSE streaming internally.

### AI-Specific Types

These types are internal to the AI plugin (not part of the core `EditorPlugin` interface):

```typescript
// modules/editor/src/main/typescript/plugins/ai/types.ts

export type AiStreamEvent =
  | { type: 'text'; content: string }
  | { type: 'proposal'; changes: AiProposal }
  | { type: 'done'; usage?: { inputTokens: number; outputTokens: number } }
  | { type: 'error'; message: string }

export interface AiProposal {
  description: string
  mode: 'commands' | 'replace'
  commands?: AnyCommand[]    // For command mode
  document?: TemplateDocument // For replace mode
}

export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  proposal?: AiProposal
}
```

### AiChatService

A service class (following the `PreviewService` / `SaveService` pattern) that manages the SSE stream lifecycle. Created inside the plugin's `init()` function:

```typescript
// modules/editor/src/main/typescript/plugins/ai/ai-chat-service.ts

export class AiChatService {
  private _abortController: AbortController | null = null
  private _conversationId: string | null = null

  constructor(
    private _endpoint: string,
    private _getCsrfToken: () => string,
    private _onChange: (event: AiChatStateEvent) => void,
  ) {}

  async sendMessage(message: string, document: TemplateDocument): Promise<void>
  abort(): void
  dispose(): void
}
```

This mirrors the state machine pattern from `PreviewService`:
- `idle` → `streaming` → `done` | `error`
- New `sendMessage()` while streaming aborts the in-flight request

### AiChangeApplier

Applies AI proposals to the editor document through the existing engine:

```typescript
// modules/editor/src/main/typescript/plugins/ai/ai-change-applier.ts

export class AiChangeApplier {
  constructor(private engine: EditorEngine) {}

  /**
   * Apply a proposal. In command mode, dispatches each command
   * sequentially via engine.dispatch(). In replace mode, calls
   * engine.replaceDocument().
   */
  apply(proposal: AiProposal): ApplyResult
}
```

Integration points on `EditorEngine`:
- **Command mode**: `engine.dispatch(command)` — for surgical changes (InsertNode, UpdateNodeProps, MoveNode, etc.)
- **Replace mode**: `engine.replaceDocument(doc)` — for wholesale template replacement

Command mode is preferred because it preserves undo history. Replace mode clears the undo stack (as documented in `EditorEngine.replaceDocument()`).

### User Approval Flow

AI proposals are never auto-applied. The flow is:

1. User sends a message
2. AI streams back a text response + a structured proposal
3. The proposal is rendered as a card with a summary and Apply / Reject buttons
4. **Apply**: `AiChangeApplier.apply()` dispatches the changes
5. **Reject**: Proposal is discarded; user can refine their request

This keeps the user in control and avoids surprises from malformed AI output.

---

## Prompt Engineering

### System Prompt Structure

The system prompt provides the AI with everything it needs to produce valid template changes:

```
You are a template editor assistant for Epistola.

## Template Model
The template document is a tree of nodes connected by slots.
<JSON Schema of TemplateDocument>

## Available Component Types
<List of registered component types with their properties>

Component types:
- root: Document root (1 slot: children)
- text: Rich text block (no slots, props: content)
- container: Generic container (1 slot: children)
- columns: Multi-column layout (dynamic slots, props: columnSizes)
- table: Static table (props: rows, columns, headers, cells)
- datatable: Data-bound table (child datatable-column nodes)
- conditional: Show/hide based on expression (props: condition, inverse)
- loop: Repeat content for each item (props: expression, itemAlias)
- pagebreak: Force a page break
- pageheader: Page header content
- pagefooter: Page footer content

## Current Document State
<Serialised current TemplateDocument>

## Reference Documents (if any)
<Extracted text from uploaded PDFs/DOCX files>

## Response Format
When suggesting changes, include a JSON code block with a proposal object:
```json
{
  "mode": "commands",
  "description": "What these changes do",
  "commands": [...]
}
```

For simple conversations (explanations, questions), respond normally without a proposal block.
```

### Provider-Agnostic Response Parsing

Rather than using provider-specific tool calling (Claude's tool_use, OpenAI's function_calling), proposals are extracted from the AI's freeform text response by scanning for JSON code blocks. This approach:

- Works identically across all providers
- Doesn't require provider-specific prompt tuning
- Allows the AI to explain its reasoning alongside the proposal
- Is simpler to implement and debug

The parser looks for the first ```json ... ``` block in the response and attempts to parse it as an `AiProposal`. If parsing fails, the response is treated as plain text (no proposal).

---

## Phased Implementation

### Phase 1: Plugin Infrastructure + Provider Abstraction + Basic Chat

**Goal**: Establish the plugin infrastructure and get end-to-end chat working with AI in the editor.

Plugin infrastructure (prerequisite — benefits all future plugins):
- Frontend `EditorPlugin` interface and `PluginContext` types (`modules/editor/.../plugins/types.ts`)
- Dynamic sidebar tab system in `EpistolaSidebar.ts` (replace hardcoded `SidebarTab` union)
- Plugin lifecycle in `EpistolaEditor.ts` (init/dispose)
- `plugins` array on `EditorOptions`
- Backend `EpistolaPlugin` marker interface in `epistola-core`
- `enabledPlugins` Thymeleaf model attribute + conditional loading in `editor.html`

AI plugin backend:
- `modules/plugins/ai/` Gradle module with auto-configuration
- `AiProvider` interface + `ClaudeProvider` implementation
- `AiService` with conversation management
- `AiRoutes` with `AiChatHandler` SSE endpoint
- Configuration properties (`epistola.plugins.ai.*`)

AI plugin frontend:
- `createAiPlugin()` factory function implementing `EditorPlugin`
- `<epistola-ai-panel>` component with message thread and input
- `AiChatService` for SSE stream management
- AI tab contributed via `sidebarTab`

**Deliverable**: Users can enable the AI plugin and chat with an AI about their template, getting text responses streamed back in a sidebar tab.

### Phase 2: Reference Upload + Context Enrichment

**Goal**: Upload reference documents and include them in the AI's context.

Backend:
- `ReferenceUploadHandler` endpoint in `AiRoutes`
- PDF text extraction (PDFBox)
- DOCX text extraction (Apache POI)
- System prompt enrichment with reference text

Frontend:
- File upload UI in the AI panel (drag-and-drop + file picker)
- Reference file list with remove capability

**Deliverable**: Users can upload a PDF of an existing invoice and say "Make a template that looks like this."

### Phase 3: Structured Template Changes

**Goal**: AI produces actionable proposals that modify the template document.

Backend:
- System prompt with template model schema and component types
- Response parsing: extract JSON proposals from AI text
- Current document state included in context

Frontend:
- `AiChangeApplier` to dispatch commands or replace documents
- Proposal cards with Apply / Reject UI
- Visual feedback after applying changes (e.g. selecting the new node)

**Deliverable**: Users can say "Add a 2-column layout with a logo on the left and company info on the right" and apply the suggested changes.

### Phase 4: Additional Providers + Styling

**Goal**: Multi-provider support and AI-driven styling.

Backend:
- `OpenAiProvider` and `MistralProvider` implementations
- Provider selection UI (or admin configuration)
- Style-aware system prompts (theme presets, style properties)

Frontend:
- Provider selector in AI panel (if user-facing)
- Style-related proposals (UpdateNodeStyles, SetStylePreset commands)

**Deliverable**: Users can switch providers and ask the AI to style their templates.

### Phase 5: Advanced Agent Capabilities

**Goal**: Multi-step planning and autonomous execution (with user approval).

- AI plans a sequence of changes and presents them as a batch
- "Undo all AI changes" to revert an entire AI session
- Template-to-template transformation ("Convert this invoice to a credit note")
- Data model-aware suggestions ("Add a loop over `order.items`")

---

## Key Design Decisions

### 1. AI as a plugin, not core functionality

**Decision**: Implement AI as an optional plugin module (`modules/plugins/ai/`) rather than embedding it in `epistola-core` or `apps/epistola`.

**Rationale**: AI integration requires external API keys, has cost implications, and not all deployments need it. The plugin architecture (see [plugins.md](plugins.md)) makes it a clean opt-in: add the Gradle dependency + set `epistola.plugins.ai.enabled: true`. No code changes in the main app. This also establishes the pattern for future plugins.

### 2. AI panel via EditorPlugin sidebar tab

**Decision**: The AI chat panel is contributed to the sidebar via the `EditorPlugin.sidebarTab` mechanism, not hardcoded in `EpistolaSidebar.ts`.

**Alternatives considered**:
- Hardcoded 4th tab — Would work but couples the sidebar to AI, making it always present even when the plugin is disabled
- Floating panel / modal — Breaks the spatial model of the editor
- Separate page — Loses the context of the active editor

The plugin sidebar tab approach means the AI tab only appears when the plugin is enabled, and the sidebar remains extensible for future plugins.

### 3. HTTP encapsulated in plugin, not in EditorOptions

**Decision**: The AI plugin handles its own HTTP communication internally (endpoint URLs, CSRF, SSE parsing), rather than exposing `onAiChat`/`onAiUpload` callbacks on `EditorOptions`.

**Rationale**: Adding AI-specific callbacks to `EditorOptions` would pollute the core editor API with plugin concerns. Instead, the host page passes HTTP context (tenant ID, CSRF token) to the plugin's factory function, and the plugin manages its own `fetch` + SSE internally. This keeps `EditorOptions` focused on core editor needs (`onSave`, `onFetchPreview`).

### 4. SSE over WebSocket

**Decision**: Use POST + SSE for streaming AI responses.

**Rationale**: AI chat is request-response with streamed replies — inherently unidirectional. SSE is simpler (standard HTTP, automatic reconnection, works with existing CSRF/auth), and we don't need bidirectional communication.

### 5. Custom AiProvider over Spring AI

**Decision**: Define our own `AiProvider` interface rather than using Spring AI.

**Rationale**: Spring AI is still evolving rapidly and brings a large dependency tree. Our interface is ~20 lines of Kotlin. We need precise control over prompt construction, streaming, and response parsing. Migration to Spring AI remains possible later if it proves worthwhile.

### 6. In-memory conversations

**Decision**: Store conversations in-memory with TTL expiry.

**Rationale**: Conversations are ephemeral working sessions. They don't need to survive server restarts or be shared across instances. This avoids schema changes and keeps the implementation simple. If persistence is needed later (e.g. for multi-instance deployments), we can add a `conversation` table without changing the API.

### 7. Extract proposals from text over tool calling

**Decision**: Parse JSON proposals from the AI's freeform text response rather than using provider-specific tool calling.

**Rationale**: Tool calling (Claude's `tool_use`, OpenAI's `function_calling`) is powerful but provider-specific. By extracting JSON from text, we get a provider-agnostic approach that's simpler to implement and debug. The AI can still explain its reasoning in natural language alongside the structured proposal.

---

## Referenced Files

| File | Relevance |
|------|-----------|
| `docs/plugins.md` | General plugin architecture — `EditorPlugin` interface, dynamic sidebar, auto-configuration |
| `modules/editor/src/main/typescript/lib.ts` | `EditorOptions` interface — gains `plugins` array |
| `modules/editor/src/main/typescript/ui/EpistolaSidebar.ts` | Sidebar with tabs — becomes dynamic to support plugin tabs |
| `modules/editor/src/main/typescript/ui/EpistolaEditor.ts` | Root component — manages plugin lifecycle (init/dispose) |
| `modules/editor/src/main/typescript/engine/EditorEngine.ts` | `dispatch()` and `replaceDocument()` — the two integration points for AI changes |
| `modules/editor/src/main/typescript/engine/commands.ts` | Command types (InsertNode, RemoveNode, MoveNode, etc.) the AI must produce |
| `modules/editor/src/main/typescript/engine/registry.ts` | Component definitions and types available for the system prompt |
| `modules/editor/src/main/typescript/ui/preview-service.ts` | Reference pattern for `AiChatService` (state machine, abort, dispose) |
| `apps/epistola/src/main/resources/templates/templates/editor.html` | Host page — conditional plugin loading, `ENABLED_PLUGINS` |
| `apps/epistola/src/main/resources/application.yaml` | Where `epistola.plugins.ai.*` configuration goes |
