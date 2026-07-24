<!--
  SPDX-FileCopyrightText: Epistola Nederland B.V.

  SPDX-License-Identifier: AGPL-3.0-only
-->

# Component Registry Pipeline

The static editor component vocabulary is owned by `epistola-contract` and
published in `epistola-model`:

- npm: `@epistola.app/epistola-model/registry/component-registry.json`
- npm: `@epistola.app/epistola-model/registry/style-registry.json`
- Maven: `META-INF/epistola-model/component-registry.json`
- Maven: `META-INF/epistola-model/style-registry.json`

The suite consumes those files. It does not maintain an independent canonical
registry for MCP or documentation.

## Contract vs Suite Responsibilities

The contract registry owns the serializable vocabulary:

- component `type`, `label`, `icon`, `category`, and `hidden`
- slot templates and `allowedChildren`
- `applicableStyles`, inspector fields, default styles, and default props
- `maxInstancesPerDocument`
- curated example fragments
- parameter metadata:
  `{ "kind": "dynamic" }` for per-node schemas and
  `{ "kind": "static", "schema": { ... } }` for shared schemas

The suite still owns runtime behavior that cannot live in JSON:

- custom canvas and inspector renderers
- command handlers
- dynamic slot/subtree creation
- editor callbacks
- runtime stencil parameter schemas
- PDF rendering implementation

## Editor Use

The editor imports the static style registry from the typed
`@epistola.app/epistola-model/registry` facade and exports a mutable clone as
`defaultStyleRegistry`. The mutable clone is deliberate: the host's font catalog
replaces the `fontFamily` select options at runtime.

Component runtime registration still happens through `ComponentDefinition` and
`createDefaultRegistry()` in
[`modules/editor/src/main/typescript/engine/registry.ts`](../modules/editor/src/main/typescript/engine/registry.ts).
That layer attaches the suite-owned behavior hooks to the static vocabulary.

## MCP Use

[`ComponentRegistryProvider`](../modules/epistola-mcp/src/main/kotlin/app/epistola/suite/mcp/support/ComponentRegistryProvider.kt)
reads the contract-published classpath resource:

```kotlin
@Value("classpath:META-INF/epistola-model/component-registry.json")
private lateinit var resource: Resource
```

`epistola-mcp` depends on `libs.epistola.model` for this data. It does not depend
on `:modules:editor` merely to get a generated JSON file onto the classpath.

## Test Coverage

- [`registry-examples.test.ts`](../modules/editor/src/main/typescript/engine/registry-examples.test.ts)
  keeps editor-authored examples structurally valid while runtime hooks still
  live in the suite.
- [`ComponentTypesIntegrationTest.kt`](../modules/epistola-mcp/src/test/kotlin/app/epistola/suite/mcp/ComponentTypesIntegrationTest.kt)
  proves MCP can read and expose the contract registry.
- [`ExampleRenderingIntegrationTest.kt`](../modules/epistola-mcp/src/test/kotlin/app/epistola/suite/mcp/ExampleRenderingIntegrationTest.kt)
  renders every contract example fragment to PDF, catching vocabulary that parses
  but does not render.

## Updating the Vocabulary

Change the static vocabulary in `epistola-contract`, release or locally publish
`epistola-model`, then bump both suite dependencies:

- `gradle/libs.versions.toml` `epistola-model`
- `modules/editor/package.json` `@epistola.app/epistola-model`

If the change also needs suite runtime behavior, update the editor registration
hooks and renderer/validator code in the same suite change. The static JSON and
runtime hooks must describe the same node types and props.
