# Plugin Architecture Design Document

## Overview

Epistola plugins are optional, self-contained feature modules that extend the editor and platform with additional capabilities. A plugin can contribute:

- **Backend services and routes** — Spring beans, UI handler endpoints, configuration properties
- **Frontend editor UI** — sidebar tabs, toolbar actions, custom panels
- **Configuration** — per-plugin settings under the `epistola.plugins.<name>` namespace

Plugins are **disabled by default** and explicitly enabled via configuration:

```yaml
epistola:
  plugins:
    ai:
      enabled: true
```

The AI assistant (see [docs/ai.md](ai.md)) is the first plugin built on this architecture. The design should support future plugins such as version history, collaboration, or analytics.

---

## Backend Plugin Model

Spring Boot already provides the mechanisms needed — plugins are well-structured Gradle modules that leverage auto-configuration and conditional enablement.

### Module Structure

Each plugin is a separate Gradle module under `modules/plugins/<name>/`:

```
modules/plugins/ai/
├── build.gradle.kts                   # Dependencies (including epistola-core)
└── src/main/kotlin/app/epistola/suite/plugins/ai/
    ├── AiPluginAutoConfiguration.kt   # @AutoConfiguration + @ConditionalOnProperty
    ├── AiPluginProperties.kt          # @ConfigurationProperties("epistola.plugins.ai")
    ├── AiService.kt                   # Business logic
    ├── providers/                     # ClaudeProvider, OpenAiProvider, etc.
    ├── routes/                        # AiRoutes.kt (@Bean RouterFunction)
    └── handlers/                      # AiChatHandler, ReferenceUploadHandler
```

### Auto-Configuration

Each plugin module provides a Spring Boot auto-configuration entry:

```
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

This file lists the plugin's `@AutoConfiguration` class. When the plugin module JAR is on the classpath, Spring Boot auto-discovers and loads it — no changes needed in `apps/epistola`.

```kotlin
// modules/plugins/ai/.../AiPluginAutoConfiguration.kt

@AutoConfiguration
@ConditionalOnProperty("epistola.plugins.ai.enabled", havingValue = "true")
@ConfigurationPropertiesScan
class AiPluginAutoConfiguration {

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

### Conditional Enablement

The `@ConditionalOnProperty` annotation ensures the plugin is a complete no-op unless explicitly enabled. When disabled (the default):
- No beans are created
- No routes are registered
- No resources are consumed

This is pure Spring Boot — no custom "plugin framework" needed.

### Routes Live in the Plugin Module

This is a **deliberate departure** from the current pattern where routes are in `apps/epistola/handlers/`. Plugin routes live inside the plugin module itself because:

- **Self-containment**: Adding a plugin means adding a Gradle dependency, not modifying `apps/epistola` handler code
- **Clean boundaries**: Plugin routes, handlers, and services live in the same module
- **Spring discovery**: `@Bean RouterFunction` beans are discovered from any module in the component scan path

Since `EpistolaSuiteApplication` uses `@SpringBootApplication` (which implies `@EnableAutoConfiguration`), auto-configured beans from plugin modules are picked up automatically.

### Plugin Metadata Interface

A lightweight marker interface in `epistola-core` enables runtime discovery of active plugins:

```kotlin
// modules/epistola-core/.../plugins/EpistolaPlugin.kt

interface EpistolaPlugin {
    /** Unique plugin identifier, e.g. "ai" */
    val id: String

    /** Human-readable name, e.g. "AI Assistant" */
    val name: String

    /** Plugin version */
    val version: String
}
```

Each plugin's auto-configuration class exposes a bean implementing this interface:

```kotlin
@Bean
fun aiPlugin(): EpistolaPlugin = object : EpistolaPlugin {
    override val id = "ai"
    override val name = "AI Assistant"
    override val version = "1.0.0"
}
```

This enables:
- A `/plugins` UI handler endpoint that lists active plugins (for the frontend to know which plugins to load)
- A Thymeleaf model attribute (`enabledPlugins`) injected into editor pages
- Admin/diagnostic visibility into which plugins are active

### Configuration Pattern

All plugin configuration lives under `epistola.plugins.<name>`:

```yaml
epistola:
  plugins:
    ai:
      enabled: true
      default-provider: claude
      providers:
        claude:
          api-key: ${CLAUDE_API_KEY:}
          model: claude-sonnet-4-5-20250929
      # ... more plugin-specific settings
```

Each plugin defines its own `@ConfigurationProperties` class:

```kotlin
@ConfigurationProperties("epistola.plugins.ai")
data class AiPluginProperties(
    val enabled: Boolean = false,
    val defaultProvider: String = "claude",
    val providers: Map<String, ProviderProperties> = emptyMap(),
    // ...
)
```

### Adding a Plugin to the Application

From the application's perspective, enabling a plugin is a two-step process:

1. **Add the Gradle dependency** in `apps/epistola/build.gradle.kts`:
   ```kotlin
   dependencies {
       implementation(project(":modules:plugins:ai"))
   }
   ```

2. **Enable via configuration** in `application.yaml` (or environment variable):
   ```yaml
   epistola:
     plugins:
       ai:
         enabled: true
   ```

No code changes in `apps/epistola` are required — Spring Boot auto-configuration handles the rest.

---

## Frontend Plugin Model

The editor currently hardcodes sidebar tabs, toolbar items, and service wiring. Plugins need a way to contribute UI elements.

### `EditorPlugin` Interface

```typescript
// modules/editor/src/main/typescript/plugins/types.ts

export interface EditorPlugin {
  /** Unique plugin identifier (matches backend plugin id) */
  id: string

  /** Sidebar tab contributed by this plugin (optional) */
  sidebarTab?: SidebarTabContribution

  /** Toolbar actions contributed by this plugin (optional) */
  toolbarActions?: ToolbarAction[]

  /**
   * Called when the editor engine is ready. Returns a dispose function
   * for cleanup when the editor unmounts.
   */
  init(context: PluginContext): PluginDisposeFn
}

export interface SidebarTabContribution {
  /** Tab identifier (used as the active tab key) */
  id: string

  /** Display label shown on the tab button */
  label: string

  /** Optional icon identifier */
  icon?: string

  /** Renders the tab content. Called reactively when context changes. */
  render: (context: PluginContext) => TemplateResult
}

export interface ToolbarAction {
  /** Action identifier */
  id: string

  /** Tooltip / aria label */
  label: string

  /** Icon identifier */
  icon: string

  /** Called when the toolbar button is clicked */
  onClick: () => void
}

export interface PluginContext {
  /** The editor engine instance — plugins can dispatch commands via engine.dispatch() */
  engine: EditorEngine

  /** Current document state */
  doc: TemplateDocument

  /** Currently selected node, or null */
  selectedNodeId: NodeId | null
}

export type PluginDisposeFn = () => void
```

### `EditorOptions` Extension

The `plugins` array is added to `EditorOptions`:

```typescript
// modules/editor/src/main/typescript/lib.ts

export interface EditorOptions {
  container: HTMLElement
  template?: TemplateDocument
  onSave?: (template: TemplateDocument) => Promise<void>
  dataModel?: object
  dataExamples?: object[]
  onFetchPreview?: FetchPreviewFn

  /** Optional plugins that extend the editor with additional sidebar tabs, toolbar actions, etc. */
  plugins?: EditorPlugin[]
}
```

### Dynamic Sidebar

`EpistolaSidebar.ts` changes from a hardcoded `SidebarTab` union to a dynamic tab registry. Built-in tabs (blocks, structure, inspector) form the base set, and plugin tabs are appended:

```typescript
// Current (hardcoded):
type SidebarTab = 'blocks' | 'structure' | 'inspector'

// New (dynamic):
interface TabDefinition {
  id: string
  label: string
  icon?: string
  render: (context: SidebarRenderContext) => TemplateResult
}
```

Built-in tabs are defined as `TabDefinition` objects internally. Plugin sidebar tab contributions are appended to this list. The sidebar renders tabs dynamically from the combined list.

The tab bar becomes data-driven:

```typescript
// Pseudocode — actual implementation detail
private get _allTabs(): TabDefinition[] {
  return [
    ...this._builtinTabs,
    ...this._pluginTabs, // from EditorPlugin.sidebarTab contributions
  ]
}
```

### Plugin Lifecycle

The plugin lifecycle is managed by `EpistolaEditor` (the root component):

1. **Mount**: `mountEditor()` receives the `plugins` array in `EditorOptions`
2. **Init**: `EpistolaEditor.initEngine()` calls `plugin.init(context)` for each plugin after the engine is ready, storing the returned dispose functions
3. **Update**: Plugin sidebar tabs receive an updated `PluginContext` on every `doc:change` and `selection:change` event (reactive via Lit properties)
4. **Dispose**: `EpistolaEditor.disconnectedCallback()` calls all dispose functions, allowing plugins to clean up subscriptions, timers, or abort in-flight requests

```typescript
// EpistolaEditor.ts — plugin lifecycle (simplified)

private _pluginDisposers: PluginDisposeFn[] = []

initEngine(doc, registry, options) {
  // ... existing engine setup ...

  // Initialize plugins
  const context: PluginContext = {
    engine: this._engine,
    doc: this._doc,
    selectedNodeId: this._selectedNodeId,
  }
  this._pluginDisposers = (this._plugins ?? []).map(p => p.init(context))
}

disconnectedCallback() {
  this._pluginDisposers.forEach(dispose => dispose())
  this._pluginDisposers = []
  // ... existing cleanup ...
}
```

### Host Page Wiring

The Thymeleaf host page conditionally constructs plugin instances based on backend-provided data about which plugins are enabled:

```html
<!-- editor.html -->
<script th:inline="javascript">
  window.ENABLED_PLUGINS = /*[[${enabledPlugins}]]*/ [];
</script>
```

```javascript
// Editor mount script — conditional plugin loading
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
  // ... other options
});
```

This pattern:
- Only loads plugin JS when the plugin is enabled (code splitting)
- Passes host-page concerns (tenant context, CSRF) to the plugin factory
- Keeps the editor module unaware of specific plugin implementations

---

## What a Plugin Does NOT Get

Plugins are intentionally limited in scope:

- **No core component registry modification** — Plugins cannot add custom component types to `ComponentRegistry`. Component types are a separate extension point (see `registry.ts`). If a plugin needs a new component type, that's a core feature, not a plugin contribution.
- **No command dispatch interception** — Plugins can call `engine.dispatch()` but cannot intercept or modify commands dispatched by other parts of the system.
- **No custom engine events** — The `EngineEvents` map is fixed. Plugins should use their own internal event/state mechanisms.
- **No Flyway migrations** — Plugins do not contribute database migrations. If a plugin needs persistent storage, it uses core schema tables or a separate datasource (decided per plugin).

These constraints keep the plugin surface area small and predictable. They can be relaxed in the future if compelling use cases arise.

---

## Design Decisions

### 1. Spring Boot Auto-Configuration over Custom Plugin Framework

**Decision**: Use Spring Boot's built-in auto-configuration and conditional property mechanism rather than building a custom plugin registry, classloader, or SPI mechanism.

**Rationale**: Spring Boot already solves plugin discovery (`AutoConfiguration.imports`), conditional loading (`@ConditionalOnProperty`), and configuration binding (`@ConfigurationProperties`). A custom framework would add complexity without clear benefit. The tradeoff is that plugins must be compiled and included at build time (no hot-loading), which is acceptable for our deployment model.

### 2. Plugin Routes Inside Plugin Modules

**Decision**: Plugin routes live in the plugin module, not in `apps/epistola/handlers/`.

**Rationale**: This breaks with the current convention where all routes are in `apps/epistola`, but it's the right choice for plugins. Self-contained modules mean adding a plugin is a Gradle dependency + config toggle — no source code changes in the main app. The alternative (routes in `apps/epistola` with `@ConditionalOnBean` guards) would scatter plugin logic across modules.

### 3. Frontend Plugins via EditorOptions, not Global Registry

**Decision**: Frontend plugins are passed as an array in `EditorOptions`, not registered globally (e.g. `EditorPluginRegistry.register(...)`).

**Rationale**: A global registry creates hidden state and ordering dependencies. Passing plugins explicitly in options is simple, testable, and makes the editor's dependencies visible. It also aligns with how `onSave` and `onFetchPreview` already work — the host page assembles everything the editor needs.

### 4. Dynamic Import for Plugin Frontend Code

**Decision**: Plugin frontend code is loaded via dynamic `import()` in the host page, conditional on `ENABLED_PLUGINS`.

**Rationale**: This keeps the main editor bundle small. Plugin code is only loaded when needed. The alternative (always bundling all plugin code) would increase the initial load even when plugins are disabled.

### 5. Plugins Disabled by Default

**Decision**: All plugins default to `enabled: false`.

**Rationale**: Explicit opt-in prevents accidental activation of features that may require API keys, additional infrastructure, or have cost implications (e.g. AI provider API costs). This also keeps the default Epistola experience clean and predictable.

---

## Future Plugin Ideas

These are not designed in detail — they illustrate the kind of features that fit the plugin model:

| Plugin | Contributes |
|--------|-------------|
| **AI Assistant** | Sidebar tab (chat panel), backend AI routes, provider integrations |
| **Version History** | Sidebar tab (version list), backend version storage/diff |
| **Collaboration** | Presence indicators, cursor sharing (would need WebSocket — extends the model) |
| **Analytics** | Dashboard tab, template usage tracking |
| **Export Formats** | Additional export options (HTML email, MJML), toolbar actions |

---

## Referenced Files

| File | Relevance |
|------|-----------|
| `modules/editor/src/main/typescript/lib.ts` | `EditorOptions` — gains `plugins` array |
| `modules/editor/src/main/typescript/ui/EpistolaSidebar.ts` | Hardcoded tabs → dynamic tab registry |
| `modules/editor/src/main/typescript/ui/EpistolaEditor.ts` | Plugin lifecycle (init/dispose) |
| `modules/editor/src/main/typescript/engine/registry.ts` | Existing extension point pattern (`ComponentDefinition` hooks) |
| `apps/epistola/src/main/resources/templates/templates/editor.html` | Host page — conditional plugin loading |
| `apps/epistola/src/main/resources/application.yaml` | Config under `epistola.plugins.*` |
| `apps/epistola/src/main/kotlin/app/epistola/suite/EpistolaSuiteApplication.kt` | `@SpringBootApplication` — auto-discovers plugin beans |
| `docs/ai.md` | AI assistant — first plugin built on this architecture |
