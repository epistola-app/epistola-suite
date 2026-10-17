# Stencil Parameters

Decision records: [`adr/0002-stencil-parameters.md`](./adr/0002-stencil-parameters.md) (parameters)
and contract
[ADR 0006](https://github.com/epistola-app/epistola-contract/blob/feature/v2/docs/adr/0006-stencil-instances-by-reference.md)
(instances by reference, lexical scope, isolation). Parameters are an intrinsic property of every
stencil — the set is sometimes empty — so there is no feature toggle.

## 1. Summary

A stencil can declare typed **input parameters**: a JSON Schema
`{type:"object", properties:{...}, required:[...]}` stored on the stencil
(`stencils.parameter_schema`). A template that uses the stencil **binds** each parameter to a
JSONata expression evaluated where the instance sits — the template's data, surrounding loop
variables, and engine-provided values like `sys.pages.current`.

Inside the stencil's content, expressions read `params.<name>` the same way they would read
`customer.name` or `item.price`.

Parameters are orthogonal to [placeholders](stencil-placeholders.md): placeholders are content
slots the template fills; parameters are typed values fed in by expressions. A stencil can have
both.

## 2. Document model

### 2.1 Schema on the stencil

`stencils.parameter_schema` (JSONB, nullable) holds the parameters. NULL means "no parameters" —
semantically identical to `{"type":"object","properties":{}}`. It is part of the stencil's working
copy (set with `CreateStencil` / `UpdateStencil`, removed with `UpdateStencil.clearParameterSchema`)
and is released with the catalog like the stencil's content.

The supported shape:

```jsonc
{
  "type": "object",
  "properties": {
    "recipientName": { "type": "string", "description": "Recipient", "default": "Anonymous" },
    "pageBreakBefore": { "type": "boolean" },
    "tags": { "type": "array", "items": { "type": "string" } },
    "deliveryDate": { "type": "string", "format": "date" },
  },
  "required": ["recipientName"],
}
```

Type rules (`ParameterSchemaValidator`, run by `CreateStencil` and `UpdateStencil`):

- Property names match `^[a-z][a-zA-Z0-9_]{0,63}$` (camelCase allowed, because they surface as
  `params.recipientName`). Reserved names are rejected: `params`, `item`, `sys`, `index`, and
  anything ending in `_index|_first|_last`.
- Each property's `type` is one of `string` / `number` / `integer` / `boolean`, optionally with
  `format: "date"` or `"date-time"`, **or** `array` whose `items.type` is one of those primitives.
- Defaults must match the declared type.
- Anything else (nested objects, `oneOf`, enums, …) is rejected; the storage stays canonical JSON
  Schema, so these can be lifted later without a migration.

### 2.2 Bindings on the instance

A template's `stencil` node carries the bindings:

```jsonc
{
  "id": "<NodeId>",
  "type": "stencil",
  "props": {
    "stencilKey": "letter",
    "parameterBindings": {
      "recipientName": "customer.name",
      "tags": "['vip', 'priority']",
    },
    "paramsAlias": "params", // optional; defaults to "params"
  },
}
```

- `parameterBindings` and `paramsAlias` are **component-agnostic** and live in
  `templates.model.NodeParameterKeys`; any future parametrised component uses the same keys.
- The schema is **not** stored on the node. When an instance is expanded from its stencil (see
  [stencils.md](stencils.md#collapse-and-expand)), the stencil's schema is set on it as the
  transient prop `parameterSchemaSnapshot` (`stencils.StencilNodeKeys`), which the schema provider
  (`StencilNodeParameterSchemaProviderConfig`) hands to validation and rendering. Every template
  write drops it again, so the schema always comes from the stencil as it is in the working copy or
  the release being rendered.

### 2.3 The `params` namespace

At render time, a parametrised node pushes a named scope onto `RenderContext.parameterScopes` — a map
keyed by alias. The default alias is `params`, configurable with `paramsAlias`. `effectiveData`
exposes each named scope as a top-level key next to the data and `sys`.

What a stencil's content sees is **isolated** and **lexical**:

- By default a stencil's content sees only its own parameters and `sys` — not the template's data,
  loop variables, or an outer stencil's parameters. Everything it needs comes in through a
  parameter.
- A stencil that declares `readsTemplateData` also sees what is in scope where the instance sits:
  the template's data and loop variables, and, inside another stencil, what that stencil's content
  sees.
- The bindings themselves are evaluated where the instance sits, so a binding inside an outer
  stencil can read the outer stencil's `params`:

```
template
  └─ stencil "letter"                       (content reads params.title)
        └─ stencil "footer"                 (binding year: "params.year" reads letter's params)
              └─ ...content reads params.year — footer's own params
```

Because each stencil sees only its own scope, nested stencils no longer need distinct aliases;
`paramsAlias` stays supported (it must not be `sys`, `item` or `index`).

A placeholder's fill reads the scope where it was written, not the stencil's `params`, unless the
placeholder passes them — see
[stencil-placeholders.md](stencil-placeholders.md#4-passing-parameters-to-a-fill).

## 3. Validation

**On every write** of a template or stencil document (`TemplateDocumentValidator`, delegating to the
portable catalog validator), the binding shape is checked: `parameterBindings` is a map of valid
parameter names to non-blank strings (`NODE_PARAMETER_BINDINGS_INVALID_SHAPE`,
`NODE_PARAMETER_BINDING_NAME_INVALID`, `NODE_PARAMETER_BINDING_EMPTY`), each value is syntactically
valid JSONata (`NODE_PARAMETER_BINDING_SYNTAX_INVALID`), and `paramsAlias` is a string that is not a
reserved name (`NODE_PARAMS_ALIAS_RESERVED`).

**Against the stencil**, when the instance is expanded:

- a required parameter with neither binding nor `default` blocks marking the variant ready and
  releasing the catalog (`NODE_PARAMETER_BINDING_MISSING_REQUIRED`); the template may be saved with
  it;
- a binding for a parameter the stencil does not declare is harmless: it is ignored when
  rendering and blocks nothing.

The fit is checked against the stencil as it is now, so a stencil change that adds a required
parameter or removes one shows up there, and on the stencil's usage page; see
[stencils.md](stencils.md#when-a-template-no-longer-fits).

Each failure carries a first-class machine code (`ValidationException.code`, a `ValidationCode`
value). A single mapper emits the same `{ code, message, errors[] }` body on the REST
`ApiExceptionHandler` and the UI routes, and the editor switches on `code`, never on the message.

> **Dual-parser note.** The backend parses JSONata with `com.dashjoin:jsonata` (Java) while the
> editor uses the `jsonata` npm package. The two grammars can disagree on edge cases; the backend is
> authoritative and surfaces any mismatch at save time.

## 4. Render-time evaluation

`StencilNodeRenderer.render`, on an expanded instance:

1. Guards against recursion and nesting deeper than the limit.
2. Resolves the schema through `RenderContext.parameterSchemaProvider(node, doc)` — in production
   `NodeParameterSchemaProviderRegistry`, which dispatches by node type and, for a stencil, reads
   the expanded `parameterSchemaSnapshot`.
3. `ParameterScope.push(node, schema, context)` evaluates each binding with the expression evaluator
   (JSONata) in the scope the instance sits in, applies defaults, and adds the result under the
   alias. A required parameter without binding or default throws in `STRICT` mode and renders a
   `<name>` placeholder in `PREVIEW` mode.
4. Isolates the content: unless the stencil reads the template's data, the data, loop variables and
   other parameter scopes are cleared, leaving its own parameters and `sys`. It records the outer
   scope for the placeholders' fills.

A parameter that ends up with no value — its binding finds nothing in the data, or it is optional,
unbound and has no default — is **absent** from `params`, so `$exists(params.medewerker)` is false
for it, just as `$exists(medewerker)` is for template data. Test for a given value with
`$exists(params.x)`, or for a non-empty one with `params.x` alone. Releases cut against rendering
defaults before V5 keep the old behaviour, where every declared parameter is present (as null) and
`$exists(params.x)` is always true.

`ParameterScope.push` is component-agnostic: the same call adds parameter support to any future
renderer.

## 5. Two-pass detection

`TwoPassAnalyzer.collectExpressions` walks every node's expression-bearing props (`condition`,
`expression`, `value`), inline ProseMirror content, **and** `parameterBindings`. A binding to
`sys.pages.total` triggers two-pass rendering even when the stencil's own content doesn't reference
`sys.*` directly.

## 6. Changing a stencil's parameters

There is no upgrade step: a template reads the stencil's schema whenever it is expanded, so a schema
change applies to every template that uses the stencil at once.

- A **renamed or removed** parameter leaves the old binding in place; it is ignored. A renamed
  parameter that is required is unbound until the template binds the new name.
- A **new required** parameter without a default blocks marking ready, and releasing, every
  template that does not bind it.
- A **new default** applies to every instance that does not bind the parameter.

The authoring and binding UI in the editor is being moved to this model.

## 7. Critical files

| Concern                     | Path                                                                                                        |
| --------------------------- | ----------------------------------------------------------------------------------------------------------- |
| DB column                   | `modules/epistola-core/src/main/resources/db/migration/core/V20261016162314__core_stencil_working_copy.sql` |
| Generic prop keys           | `modules/epistola-core/src/main/kotlin/.../templates/model/NodeParameterKeys.kt`                            |
| Transient schema key        | `modules/epistola-core/src/main/kotlin/.../stencils/StencilNodeKeys.kt`                                     |
| Expansion and fit           | `modules/epistola-core/src/main/kotlin/.../stencils/model/StencilInstances.kt`                              |
| Schema validator            | `modules/epistola-core/src/main/kotlin/.../templates/validation/ParameterSchemaValidator.kt`                |
| Document validator          | `modules/epistola-core/src/main/kotlin/.../templates/validation/TemplateDocumentValidator.kt`               |
| Schema provider registry    | `modules/epistola-core/src/main/kotlin/.../templates/validation/NodeParameterSchemaProvider.kt`             |
| Stencil schema provider     | `modules/epistola-core/src/main/kotlin/.../stencils/validation/StencilNodeParameterSchemaProviderConfig.kt` |
| Render-time scope push      | `modules/generation/src/main/kotlin/.../pdf/ParameterScope.kt`                                              |
| Stencil renderer, isolation | `modules/generation/src/main/kotlin/.../pdf/StencilNodeRenderer.kt`                                         |
| Render context              | `modules/generation/src/main/kotlin/.../pdf/RenderContext.kt`                                               |
| Two-pass detection          | `modules/generation/src/main/kotlin/.../pdf/TwoPassAnalyzer.kt`                                             |
| Catalog wire field          | `StencilResource.parameterSchema` in `epistola-catalog` (`app.epistola.catalog.protocol`)                   |
| Catalog import              | `modules/epistola-core/src/main/kotlin/.../catalog/commands/ImportStencil.kt`                               |

## 8. Known limitations

- **Alias collision warnings** — reserved aliases are rejected, but a `paramsAlias` or `parametersAs`
  that hides a template data field of the same name is not warned about.
- **Unknown bindings are silent** — a binding the stencil does not declare is never refused and
  not shown on the usage page; expansion records it only as a non-blocking finding.
- **Nested-object parameters** — `{type:"object"}` properties are rejected; this can be lifted
  without a migration.
