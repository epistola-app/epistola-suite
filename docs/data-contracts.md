# Data Contracts

A data contract defines the JSON data a template accepts. It consists of:

- a JSON Schema describing the shape and validation rules;
- one or more named test data examples used while designing and previewing the template;
- its own draft and published version history.

The contract connects the complete document flow:

```text
JSON Schema + examples -> template expressions -> contract draft -> published contract
                                                                  -> API data validation -> document
```

The schema is the source of truth. Examples help authors test a template, but they do not weaken or
replace schema validation. Data supplied for preview or document generation is validated by the
backend against the contract.

## Schema requirements and dialects

A data contract must be a valid JSON Schema whose root always describes an object. A plain JSON
object, an array-shaped root, or a schema whose union can produce a non-object root is rejected.
Object roots expressed through a local `$ref`, `allOf`, or an object-only `oneOf`/`anyOf` are valid.
Property names must be expression-safe identifiers: they start with an ASCII letter or underscore
and contain only ASCII letters, digits, and underscores.

Epistola uses the dialect declared by `$schema`. Schemas created by the visual editor declare JSON
Schema Draft 7. When `$schema` is omitted, the backend uses Draft 2020-12. This distinction matters
for keywords whose meaning changed between drafts, such as siblings of `$ref` and
`unevaluatedProperties`.

The backend performs the authoritative schema and data validation. The browser-side editor
supports a deliberately smaller projection of JSON Schema so it never silently changes the
meaning of a schema it cannot represent.

## Visual schema editing

The visual editor supports object contracts composed from:

- `string`, `number`, `integer`, `boolean`, `object`, and homogeneous `array` fields;
- nested object and array fields;
- required and optional properties;
- property descriptions;
- `date`, `date-time`, `email`, and `uri` string formats;
- `minimum` and `maximum` for numeric fields;
- `minItems` for arrays;
- Epistola's registered inline and block rich-text `$ref` types.

Select a field to add another field in context. Container fields offer a primary child action and
a secondary sibling action; scalar fields offer a sibling action. Each action shows the complete
destination path, such as `customer › address` or `customer › recipients items`. The new field is
selected immediately with its generated name ready to replace, so nested fields can be created
without first navigating back to their parent object. Press Enter to confirm a name or Escape to
restore it. Delete, undo, and redo keep the closest remaining field selected.

The visual editor currently cannot represent every valid JSON Schema keyword. Among other things,
multi-value type unions, multi-branch compositions, tuple arrays, arrays directly containing
arrays, `additionalProperties: false`, arbitrary references, `enum`, `const`, `default`,
`maxItems`, string patterns and lengths, conditional schemas, and custom keywords require
JSON-only schema mode.

JSON-only does not mean that the contract is invalid. It means the schema is shown read-only
because a round trip through the visual editor would be lossy. Its examples remain editable.
Replace or change such a schema through a schema import, catalog resource, or the REST API.

### Import normalization

When a schema is imported through the UI, Epistola first tries to convert it losslessly to the
visual subset. It can:

- inline resolvable local `$ref` values from `$defs` or `definitions`;
- flatten compatible `allOf` object compositions;
- collapse a single-member `oneOf` or `anyOf`;
- collapse a type array containing exactly one type.

If fields or constraints conflict, a reference is unresolved or recursive, a union has multiple
meaningful branches, or another conversion would discard meaning, Epistola preserves the original
schema and switches to JSON-only mode. Schemas received through catalogs or the REST API are not
rewritten automatically.

## Support across the editors

"Supported by JSON Schema validation" and "editable in the visual schema editor" are different
capabilities:

| Schema shape                                                                              | Backend validation                    | Visual schema editor     | Examples and Autofill        | Expression field discovery  |
| ----------------------------------------------------------------------------------------- | ------------------------------------- | ------------------------ | ---------------------------- | --------------------------- |
| Properties and nested objects                                                             | Yes                                   | Yes                      | Yes                          | Yes                         |
| Homogeneous arrays, including object arrays                                               | Yes                                   | Yes                      | Yes                          | Yes                         |
| Arrays directly containing arrays                                                         | Yes                                   | JSON-only                | Yes                          | Yes, in Code mode           |
| Local `$ref`, `$defs`, and `definitions`                                                  | Yes                                   | Normalized when lossless | Yes                          | Yes                         |
| Compatible `allOf` object compositions                                                    | Yes                                   | Normalized when lossless | Yes                          | Yes                         |
| Multi-branch `oneOf` and `anyOf`                                                          | Yes                                   | JSON-only                | Value-aware branch selection | Fields from all branches    |
| Nullable type unions                                                                      | Yes                                   | JSON-only                | Yes                          | Non-null fields are exposed |
| `enum`, `const`, and `default`                                                            | Yes                                   | JSON-only                | Used by Autofill             | Scalar field is exposed     |
| Recursive local references                                                                | When valid in the declared dialect    | JSON-only                | Recursion is bounded         | Exposed as a safe leaf      |
| Unresolved local references                                                               | Must resolve for data validation      | JSON-only                | Not expanded                 | Exposed as a safe leaf      |
| Registered Epistola rich-text references                                                  | Yes                                   | Yes                      | Yes                          | Yes                         |
| Arbitrary external references                                                             | Not an Epistola-supported integration | JSON-only                | Not expanded                 | Exposed as a leaf only      |
| Conditionals, `not`, tuple validation, `patternProperties`, and similar advanced keywords | Dialect-dependent                     | JSON-only                | Not fully interpreted        | Not fully interpreted       |

For a JSON-only schema, backend validation still follows its declared dialect. The table describes
what Epistola's browser tooling understands for rendering forms, generating examples, and offering
field suggestions; it is not a replacement for the JSON Schema specification.

## Test data examples

Every authored contract must contain at least one saved test data example. The last saved example
cannot be deleted until another example has been saved. This keeps previewing and template design
possible for every contract.

The example form supports the same nested projection used for field discovery: local references,
compositions, nullable unions, union branches selected from the current value, objects, object
arrays, and directly nested arrays. For constructs it cannot interpret, use a valid example
supplied through an import or API and rely on backend validation.

Array add actions show where the new item will be inserted. Nested numeric path segments are
written as item numbers, for example `groups › item 1 › members`, so similarly named arrays remain
distinguishable even in deeply nested examples.

Empty controls show placeholder hints. Placeholders are presentation only and are never included
in saved JSON. **Autofill** writes actual test values into missing or unusable fields while
preserving meaningful values already entered. It uses, in order where applicable:

- `const`, `default`, and the first `enum` value;
- formats and numeric or array constraints;
- the field name, title, and description to infer realistic fictional values;
- a numbered `Example <field name> <n>` fallback when no meaning can be inferred.

Autofill recursively completes nested objects and arrays. Unconstrained arrays normally receive
two or three distinct items, while `minItems` and `maxItems` are respected. Generated values are
deterministic, so the same example remains stable between runs. Autofill is one undoable example
operation; it is intended only for test data.

Schema changes and example changes have separate undo/redo histories. **Save draft** saves the
current schema and all examples together as one contract draft. Publishing validates both again.

## Template expressions

The template editor derives its field picker from the contract schema. It resolves local
references, compositions, nullable types, union branches, and nested arrays without modifying the
stored schema.

Builder mode offers directly selectable non-array fields. Array paths are available in Code mode
with `[]` markers, for example `subjects[].name` or `deliveryRoutes[][].city`; inside a loop, item
fields are exposed through the loop's scoped alias. Discovery is intentionally bounded to five
nested property levels, and recursive or unresolved references stop at a safe leaf.

Field suggestions are an authoring aid. Expressions can still perform JSONata or JavaScript logic
that is more complex than the Builder can represent.

## Validation and publishing

On save and publish, Epistola checks that:

1. the schema is valid for its declared dialect and has an object-shaped root;
2. property names meet Epistola's naming rules;
3. at least one test data example exists;
4. every example validates against the schema.

Validation errors identify the example and failing JSON paths. The normal save flow blocks invalid
examples. If **Save anyway** is offered after a validation failure, it can preserve the inconsistent
work in a draft for later repair; an invalid draft still cannot be published. Publishing always
performs authoritative backend validation. Real data is validated again when previewing or
generating a document.

Contract versions are independent from visual template versions. Compatible changes can follow
the normal publish flow, while breaking changes require explicit confirmation and may leave older
template versions linked to an older contract. See [Contract Schema Versioning](schema-versioning.md)
for the lifecycle, compatibility rules, and internal architecture.

## Related documentation

- [Epistola project overview](epistola.md)
- [Contract Schema Versioning](schema-versioning.md)
- [Template catalog exchange format](exchange/v5/template.md)
- [MCP data-contract tools](mcp.md)
