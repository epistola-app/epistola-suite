# Component Registry Pipeline

The editor's TypeScript component registry — the catalog of every block type a template can use (text, container, columns, datatable, …) — is the canonical source of component metadata for the entire system. The Kotlin backend doesn't maintain a parallel registry; instead, it reads a JSON snapshot of the TS registry that's generated at editor build time and bundled into the editor JAR. The MCP tool `list_component_types` is the main consumer.

This page documents the end-to-end pipeline: where the registry lives, how the snapshot is produced, where it ends up, how the backend reads it, and what to check when something breaks.

## Why a build-time snapshot?

Two alternatives we deliberately chose against:

- **Hand-curated Kotlin mirror** of the TS registry — would drift the moment one side is updated without the other.
- **Runtime extraction** (e.g. via a JS engine inside the JVM, or an HTTP call from backend to a frontend service) — couples deployments and adds runtime failure modes.

Build-time dump is decoupled, deterministic, and fails loudly: if the editor build hasn't run, the Gradle build refuses to produce a deployable.

## The TS registry

[`modules/editor/src/main/typescript/engine/registry.ts`](../modules/editor/src/main/typescript/engine/registry.ts)

`createDefaultRegistry()` returns a `ComponentRegistry` with one `ComponentDefinition` per block type. Each definition carries:

- `type`, `label`, `icon`, `category`
- `slots`, `allowedChildren`, `applicableStyles`, `inspector`, `defaultStyles`, `defaultProps`
- Optional `examples` — hand-curated `TemplateDocument` fragments showing realistic usage
- Optional render hooks (`renderCanvas`, `renderInspector`, …) that are NOT serializable and are dropped from the snapshot

Several block types have their definitions in dedicated files under `modules/editor/src/main/typescript/components/*/` — they're factory-imported into `registry.ts`.

## The dump script

[`modules/editor/scripts/dump-component-registry.ts`](../modules/editor/scripts/dump-component-registry.ts)

Run via `pnpm --filter @epistola/editor dump-registry` (also called automatically as the last step of `pnpm build` in the editor module — see [`modules/editor/package.json`](../modules/editor/package.json)):

```json
"build": "tsc -b && vite build && pnpm dump-registry"
"dump-registry": "vite-node scripts/dump-component-registry.ts -- dist/component-registry.json"
```

The script:

1. Bootstraps a `happy-dom` window so module-load-time DOM access in Lit web components doesn't fail.
2. Imports `createDefaultRegistry()` via `vite-node` (vite-node handles the TS-with-Vite resolution and Lit decorator transforms that `tsx` alone can't).
3. Projects each `ComponentDefinition` to its serializable subset (drops render hooks, callbacks, scope providers).
4. Writes the result to `dist/component-registry.json`.

The output is **deterministic** — no timestamps, no nondeterministic ordering — so committing or caching the JSON is safe.

## How it lands in the editor JAR

[`modules/editor/build.gradle.kts`](../modules/editor/build.gradle.kts) wires the JS bundle and the JSON into the JAR's resources at the same time:

```kotlin
tasks.named<ProcessResources>("processResources") {
    dependsOn(verifyFrontendBuild)
    from("dist") {
        into("META-INF/resources/editor")
    }
}
```

After `./gradlew :modules:editor:jar`, the JAR contains `META-INF/resources/editor/component-registry.json` alongside the editor JS bundle.

## How the backend reads it

[`modules/epistola-mcp/build.gradle.kts`](../modules/epistola-mcp/build.gradle.kts) declares a runtime dependency on the editor module so the JSON is on `epistola-mcp`'s classpath:

```kotlin
implementation(project(":modules:editor"))
```

[`modules/epistola-mcp/.../support/ComponentRegistryProvider.kt`](../modules/epistola-mcp/src/main/kotlin/app/epistola/suite/mcp/support/ComponentRegistryProvider.kt) reads it via Spring's `Resource` injection:

```kotlin
@Component
@ConditionalOnProperty(name = ["epistola.mcp.enabled"], havingValue = "true", matchIfMissing = true)
class ComponentRegistryProvider(
    @Value("classpath:META-INF/resources/editor/component-registry.json")
    private val resource: Resource,
    private val objectMapper: ObjectMapper,
) {
    val components: List<ComponentTypeInfo> by lazy { load() }
    ...
}
```

Loaded once on first access (lazy), parsed with the project's standard Jackson `ObjectMapper`, served by the MCP `list_component_types` / `get_component_type` tools.

## Build-order dependency

The flow only works if the editor is built **before** Gradle tries to package it. CLAUDE.md and CI both run them in the right order:

```bash
pnpm install && pnpm build       # produces dist/component-registry.json
./gradlew build                  # processResources copies it into the editor JAR
```

If you skip the `pnpm build` step, the `verifyFrontendBuild` task in `modules/editor/build.gradle.kts` fails fast with:

```
Frontend build output not found at: <path>/modules/editor/dist
Please run the frontend build first:
  pnpm install && pnpm build
```

There's no path to ship a JAR without the JSON inside it.

## Test coverage

Three layers prove the snapshot is intact and consumable:

- [`modules/editor/src/main/typescript/engine/registry-examples.test.ts`](../modules/editor/src/main/typescript/engine/registry-examples.test.ts) — vitest, asserts every example is structurally consistent (cross-references, allowedChildren, no cycles).
- [`modules/epistola-mcp/.../mcp/ComponentTypesIntegrationTest.kt`](../modules/epistola-mcp/src/test/kotlin/app/epistola/suite/mcp/ComponentTypesIntegrationTest.kt) — Kotlin integration test against the real Spring context; if the JSON is missing or malformed, the bean factory fails to start and the test errors.
- [`modules/epistola-mcp/.../mcp/ExampleRenderingIntegrationTest.kt`](../modules/epistola-mcp/src/test/kotlin/app/epistola/suite/mcp/ExampleRenderingIntegrationTest.kt) — wraps every example fragment in a `TemplateDocument` and renders it to PDF, catching any prop/expression issues that pass JSON-schema validation but fail at render time.

## Debugging checklist

When `list_component_types` returns unexpected data — for example after a TS registry change you don't see reflected:

1. **`pnpm --filter @epistola/editor build`** — regenerate `dist/component-registry.json`. The dump runs as the last step of the editor build.
2. **`./gradlew :apps:epistola:bootRun --args='--spring.profiles.active=local'`** — restart. The JSON is read at startup; running app instances cache it for the JVM lifetime.
3. **`jq '.components[] | .type' modules/editor/dist/component-registry.json`** — verify the regenerated JSON has the expected shape locally.
4. **Inside the running app:** `unzip -p modules/editor/build/libs/editor-dev.jar META-INF/resources/editor/component-registry.json | jq …` to confirm the JSON actually made it into the JAR.
5. If `epistola.mcp.enabled=false`, `ComponentRegistryProvider` isn't instantiated at all — the tools simply won't be exposed. See `docs/mcp.md` for the toggle.

## Related

- [`docs/mcp.md`](mcp.md) — MCP server, tool list, client setup
- [`docs/editor.md`](editor.md) — broader editor / frontend architecture
- [`modules/editor/src/main/typescript/engine/registry.ts`](../modules/editor/src/main/typescript/engine/registry.ts) — canonical TS registry
- [`modules/editor/scripts/dump-component-registry.ts`](../modules/editor/scripts/dump-component-registry.ts) — the dump script
- [`modules/epistola-mcp/.../support/ComponentRegistryProvider.kt`](../modules/epistola-mcp/src/main/kotlin/app/epistola/suite/mcp/support/ComponentRegistryProvider.kt) — backend reader
