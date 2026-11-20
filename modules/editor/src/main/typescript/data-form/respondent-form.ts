// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * The interactive generation form as the person filling it in sees it (contract ADR 0011): fields
 * named in words, the author's descriptions as help, groups always open, lists with add and remove,
 * and a field marked only once the person has been there or tried to submit.
 *
 * Not the data contract editor's example form, which is a tool for authors (types, counters, raw
 * keys). Paths are JSON Pointers throughout, the notation the server's problems use.
 */

import { html, nothing, type TemplateResult } from 'lit';
import type {
  JsonObject,
  JsonSchema,
  JsonSchemaProperty,
  JsonValue,
} from '../data-contract/types.js';

/** A field the server refused, by JSON Pointer; `missing` when it is a required field left empty. */
export interface FieldProblem {
  message: string;
  missing: boolean;
}

export interface RespondentFormState {
  schema: JsonSchema | null;
  data: JsonObject;
  /** The server's problems, by JSON Pointer. A field's problem is dropped once the person changes it. */
  problems: Map<string, FieldProblem>;
  /** Fields the person has left after changing or visiting them. */
  touched: Set<string>;
  /** After a submit attempt: every problem shows, also on fields not visited. */
  showAll: boolean;
}

export interface RespondentFormHandlers {
  /** A field's new value; `undefined` removes it. */
  set(pointer: string, value: JsonValue | undefined): void;
  touch(pointer: string): void;
}

type Node = JsonSchemaProperty;

// ---------------------------------------------------------------------------------------------
// Pure helpers
// ---------------------------------------------------------------------------------------------

/** `invoiceNumber`, `zip_code`, `due-date` → "Invoice number", "Zip code", "Due date". */
export function humanize(key: string): string {
  const words = key
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .trim()
    .split(/\s+/)
    .map((w) => (/^[A-Z0-9]{2,}$/.test(w) ? w : w.toLowerCase()));
  const text = words.join(' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** A list's name for one of its items: "Delivery routes" → "delivery route". */
export function singular(label: string): string {
  const lower = label.charAt(0).toLowerCase() + label.slice(1);
  if (lower.endsWith('ies')) return `${lower.slice(0, -3)}y`;
  if (/[^s]s$/.test(lower)) return lower.slice(0, -1);
  return lower;
}

/** The pointer of [key] under [parent]: RFC 6901 escapes applied. */
export function childPointer(parent: string, key: string | number): string {
  return `${parent}/${String(key).replace(/~/g, '~0').replace(/\//g, '~1')}`;
}

function tokens(pointer: string): string[] {
  return pointer
    .split('/')
    .slice(1)
    .map((t) => t.replace(/~1/g, '/').replace(/~0/g, '~'));
}

/** The value at [pointer] in [data], or undefined. */
export function getAt(data: JsonValue | undefined, pointer: string): JsonValue | undefined {
  let current: JsonValue | undefined = data;
  for (const token of tokens(pointer)) {
    if (current === null || typeof current !== 'object') return undefined;
    current = Array.isArray(current) ? current[Number(token)] : (current as JsonObject)[token];
  }
  return current;
}

/**
 * [data] with [value] at [pointer], creating objects and arrays on the way; `undefined` removes the
 * key (or the item, from a list). The input is not changed.
 */
export function setAt(data: JsonObject, pointer: string, value: JsonValue | undefined): JsonObject {
  const path = tokens(pointer);
  const write = (node: JsonValue | undefined, i: number): JsonValue | undefined => {
    const token = path[i];
    const last = i === path.length - 1;
    const nextIsIndex = !last && /^\d+$/.test(path[i + 1]);
    if (Array.isArray(node)) {
      const copy = node.slice();
      const index = Number(token);
      if (last) {
        if (value === undefined) copy.splice(index, 1);
        else copy[index] = value;
      } else {
        copy[index] = write(copy[index] ?? (nextIsIndex ? [] : {}), i + 1) as JsonValue;
      }
      return copy;
    }
    const object: JsonObject = node && typeof node === 'object' ? { ...(node as JsonObject) } : {};
    if (last) {
      if (value === undefined) delete object[token];
      else object[token] = value;
    } else {
      const next = write(object[token] ?? (nextIsIndex ? [] : {}), i + 1);
      if (next === undefined) delete object[token];
      else object[token] = next;
    }
    return object;
  };
  return (path.length === 0 ? (value as JsonObject) : write(data, 0)) as JsonObject;
}

/** [node] with a local `$ref` (`#/$defs/x`, `#/definitions/x`) followed, against [root]. */
export function resolve(node: Node, root: JsonSchema | null): Node {
  let current = node;
  for (let depth = 0; current.$ref && depth < 16; depth++) {
    const target = getAt(root as unknown as JsonValue, current.$ref.replace(/^#/, ''));
    if (!target || typeof target !== 'object') return current;
    const { $ref: _ignored, ...rest } = current;
    current = { ...(target as unknown as Node), ...rest };
  }
  return current;
}

/** The type a field is edited as. */
export type FieldKind =
  | 'object'
  | 'array'
  | 'string'
  | 'number'
  | 'integer'
  | 'boolean'
  | 'enum'
  | 'json';

export function kindOf(node: Node): FieldKind {
  if (Array.isArray(node.enum) && node.enum.length > 0) return 'enum';
  const type = Array.isArray(node.type) ? node.type.find((t) => t !== 'null') : node.type;
  if (type === 'object' || (!type && node.properties)) return 'object';
  if (type === 'array') return 'array';
  if (type === 'string' || type === 'number' || type === 'integer' || type === 'boolean')
    return type;
  return 'json';
}

function isEmpty(value: JsonValue | undefined): boolean {
  return (
    value === undefined ||
    value === null ||
    value === '' ||
    (Array.isArray(value) && value.length === 0)
  );
}

/**
 * The pointers of required fields still empty: in every object that is required or already has
 * data, and in every item of a list. A required list counts as one field until it has its minimum.
 */
export function missingRequired(schema: JsonSchema | null, data: JsonObject): string[] {
  const missing: string[] = [];
  const visitObject = (node: Node, value: JsonValue | undefined, pointer: string) => {
    const object =
      value && typeof value === 'object' && !Array.isArray(value) ? (value as JsonObject) : {};
    const required = new Set(node.required ?? []);
    for (const [key, raw] of Object.entries(node.properties ?? {})) {
      const child = resolve(raw, schema);
      const p = childPointer(pointer, key);
      const v = object[key];
      visit(child, v, p, required.has(key));
    }
  };
  const visit = (node: Node, value: JsonValue | undefined, pointer: string, required: boolean) => {
    const kind = kindOf(node);
    if (kind === 'object') {
      if (required || !isEmpty(value)) visitObject(node, value, pointer);
      return;
    }
    if (kind === 'array') {
      const items = Array.isArray(value) ? value : [];
      if (required && items.length < Math.max(1, node.minItems ?? 1)) missing.push(pointer);
      const itemSchema = node.items ? resolve(node.items, schema) : null;
      if (itemSchema)
        items.forEach((item, i) =>
          visit(itemSchema, item, childPointer(pointer, i), kindOf(itemSchema) !== 'object'),
        );
      return;
    }
    if (required && isEmpty(value)) missing.push(pointer);
  };
  if (schema) visitObject(schema as unknown as Node, data, '');
  return missing;
}

/** An element id for [pointer]: `field-customer-name`. */
export function fieldId(pointer: string): string {
  return `field${pointer.replace(/[^A-Za-z0-9_/-]/g, '_').replace(/\//g, '-')}`;
}

// ---------------------------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------------------------

interface Ctx {
  state: RespondentFormState;
  on: RespondentFormHandlers;
  missing: Set<string>;
}

/** The form's fields, for `render(…, container)`. */
export function renderRespondentForm(
  state: RespondentFormState,
  on: RespondentFormHandlers,
): TemplateResult {
  const schema = state.schema;
  if (!schema || !schema.properties || Object.keys(schema.properties).length === 0) {
    return html`<p class="iform-empty">
      There is nothing to fill in. Submit to generate the document.
    </p>`;
  }
  const ctx: Ctx = { state, on, missing: new Set(missingRequired(schema, state.data)) };
  return html`<div class="iform-fieldset-root">
    ${properties(ctx, schema as unknown as Node, '')}
  </div>`;
}

function properties(ctx: Ctx, node: Node, pointer: string): TemplateResult[] {
  const required = new Set(node.required ?? []);
  return Object.entries(node.properties ?? {}).map(([key, raw]) =>
    field(
      ctx,
      resolve(raw, ctx.state.schema),
      childPointer(pointer, key),
      raw.title ?? resolve(raw, ctx.state.schema).title ?? humanize(key),
      required.has(key),
    ),
  );
}

/** The message a field shows now, if any. */
function messageFor(ctx: Ctx, pointer: string): string | null {
  const { problems, touched, showAll } = ctx.state;
  const problem = problems.get(pointer);
  const visited = showAll || touched.has(pointer);
  // A value the caller sent that is wrong shows at once; an empty required field waits for the person.
  if (problem && (!problem.missing || visited)) return problem.message;
  if (visited && ctx.missing.has(pointer)) return 'Fill this in.';
  return null;
}

function help(node: Node, id: string): TemplateResult | typeof nothing {
  return node.description
    ? html`<p class="iform-help" id="${id}-help">${node.description}</p>`
    : nothing;
}

function error(message: string | null, id: string): TemplateResult | typeof nothing {
  return message
    ? html`<p class="iform-error" id="${id}-error" role="alert">${message}</p>`
    : nothing;
}

function describedBy(node: Node, message: string | null, id: string): string | typeof nothing {
  const ids = [node.description ? `${id}-help` : '', message ? `${id}-error` : ''].filter(Boolean);
  return ids.length ? ids.join(' ') : nothing;
}

function requiredMark(required: boolean): TemplateResult | typeof nothing {
  return required ? html`<span class="iform-required" aria-hidden="true">*</span>` : nothing;
}

function field(
  ctx: Ctx,
  node: Node,
  pointer: string,
  label: string,
  required: boolean,
): TemplateResult {
  const kind = kindOf(node);
  if (kind === 'object') return group(ctx, node, pointer, label, required);
  if (kind === 'array') return list(ctx, node, pointer, label, required);
  const id = fieldId(pointer);
  const message = messageFor(ctx, pointer);
  if (kind === 'boolean') {
    const value = getAt(ctx.state.data, pointer);
    return html`<div class="iform-field iform-field-check" ?data-invalid=${!!message}>
      <label class="iform-check">
        <input
          type="checkbox"
          id="${id}"
          class="ep-checkbox"
          .checked=${value === true}
          aria-describedby=${describedBy(node, message, id)}
          @change=${(e: Event) => {
            ctx.on.set(pointer, (e.target as HTMLInputElement).checked);
            ctx.on.touch(pointer);
          }}
        />
        <span>${label}${requiredMark(required)}</span>
      </label>
      ${help(node, id)} ${error(message, id)}
    </div>`;
  }
  return html`<div class="iform-field" ?data-invalid=${!!message}>
    <label class="iform-label" for="${id}">${label}${requiredMark(required)}</label>
    ${help(node, id)} ${control(ctx, node, pointer, id, required, message)} ${error(message, id)}
  </div>`;
}

function placeholder(node: Node): string {
  const examples = (node as { examples?: JsonValue[] }).examples;
  if (Array.isArray(examples) && examples.length > 0 && typeof examples[0] !== 'object')
    return `For example: ${examples[0]}`;
  if (node.default !== undefined && typeof node.default !== 'object')
    return `For example: ${node.default}`;
  return '';
}

/** How an `enum` value reads: as itself, or as JSON for an object. */
function optionLabel(option: JsonValue): string {
  return option !== null && typeof option === 'object' ? JSON.stringify(option) : String(option);
}

function control(
  ctx: Ctx,
  node: Node,
  pointer: string,
  id: string,
  required: boolean,
  message: string | null,
): TemplateResult {
  const value = getAt(ctx.state.data, pointer);
  const kind = kindOf(node);
  const aria = describedBy(node, message, id);
  const touch = () => ctx.on.touch(pointer);

  if (kind === 'enum') {
    const options = node.enum ?? [];
    if (options.length <= 4) {
      return html`<div
        class="iform-choices"
        role="radiogroup"
        id="${id}"
        aria-describedby=${aria}
        aria-required=${required ? 'true' : 'false'}
      >
        ${options.map(
          (option, i) =>
            html`<label class="iform-check">
              <input
                type="radio"
                name="${id}"
                id="${id}-${i}"
                .checked=${value === option}
                @change=${() => {
                  ctx.on.set(pointer, option);
                  touch();
                }}
              />
              <span>${optionLabel(option)}</span>
            </label>`,
        )}
      </div>`;
    }
    return html`<select
      id="${id}"
      class="ep-select"
      aria-describedby=${aria}
      aria-invalid=${message ? 'true' : 'false'}
      ?required=${required}
      @change=${(e: Event) => {
        const index = (e.target as HTMLSelectElement).selectedIndex - 1;
        ctx.on.set(pointer, index >= 0 ? options[index] : undefined);
        touch();
      }}
    >
      <option value="" ?selected=${value === undefined}>Choose…</option>
      ${options.map((option) => html`<option ?selected=${value === option}>${optionLabel(option)}</option>`)}
    </select>`;
  }

  if (kind === 'json') {
    return html`<textarea
      id="${id}"
      class="ep-textarea"
      rows="4"
      spellcheck="false"
      aria-describedby=${aria}
      aria-invalid=${message ? 'true' : 'false'}
      .value=${value === undefined ? '' : JSON.stringify(value, null, 2)}
      @change=${(e: Event) => {
        const text = (e.target as HTMLTextAreaElement).value.trim();
        if (text === '') ctx.on.set(pointer, undefined);
        else {
          try {
            ctx.on.set(pointer, JSON.parse(text) as JsonValue);
          } catch {
            ctx.on.set(pointer, text);
          }
        }
        touch();
      }}
    ></textarea>`;
  }

  if (kind === 'number' || kind === 'integer') {
    return html`<input
      type="number"
      id="${id}"
      class="ep-input iform-input-short"
      inputmode=${kind === 'integer' ? 'numeric' : 'decimal'}
      step=${kind === 'integer' ? '1' : (node.multipleOf ?? 'any')}
      min=${node.minimum ?? nothing}
      max=${node.maximum ?? nothing}
      placeholder=${placeholder(node) || nothing}
      aria-describedby=${aria}
      aria-invalid=${message ? 'true' : 'false'}
      ?required=${required}
      .value=${typeof value === 'number' ? String(value) : ''}
      @input=${(e: Event) => {
        const text = (e.target as HTMLInputElement).value;
        ctx.on.set(pointer, text === '' ? undefined : Number(text));
      }}
      @blur=${touch}
    />`;
  }

  // Strings, by format.
  const format = node.format;
  const long = format === 'textarea' || (node.maxLength ?? 0) > 200;
  const text =
    typeof value === 'string' ? value : value === undefined || value === null ? '' : String(value);
  const onInput = (e: Event) => {
    const v = (e.target as HTMLInputElement | HTMLTextAreaElement).value;
    ctx.on.set(pointer, v === '' ? undefined : v);
  };
  if (long) {
    return html`<textarea
      id="${id}"
      class="ep-textarea"
      rows="4"
      maxlength=${node.maxLength ?? nothing}
      placeholder=${placeholder(node) || nothing}
      aria-describedby=${aria}
      aria-invalid=${message ? 'true' : 'false'}
      ?required=${required}
      .value=${text}
      @input=${onInput}
      @blur=${touch}
    ></textarea>`;
  }
  const type =
    format === 'date'
      ? 'date'
      : format === 'email'
        ? 'email'
        : format === 'uri' || format === 'url'
          ? 'url'
          : format === 'time'
            ? 'time'
            : 'text';
  const short = type === 'date' || type === 'time' || (node.maxLength ?? 999) <= 20;
  return html`<input
    type="${type}"
    id="${id}"
    class="ep-input ${short ? 'iform-input-short' : ''}"
    maxlength=${node.maxLength ?? nothing}
    autocomplete=${type === 'email' ? 'email' : 'off'}
    placeholder=${placeholder(node) || nothing}
    aria-describedby=${aria}
    aria-invalid=${message ? 'true' : 'false'}
    ?required=${required}
    .value=${text}
    @input=${onInput}
    @blur=${touch}
  />`;
}

function group(
  ctx: Ctx,
  node: Node,
  pointer: string,
  label: string,
  required: boolean,
): TemplateResult {
  const id = fieldId(pointer);
  // A required group the caller left out is marked through its own required fields; only a wrong
  // value for the group as a whole (not an object, say) is said here.
  const problem = ctx.state.problems.get(pointer);
  const message = problem && !problem.missing ? problem.message : null;
  return html`<fieldset class="iform-group" id="${id}">
    <legend class="iform-legend">${label}${requiredMark(required)}</legend>
    ${help(node, id)} ${error(message, id)}
    <div class="iform-group-body">${properties(ctx, node, pointer)}</div>
  </fieldset>`;
}

function list(
  ctx: Ctx,
  node: Node,
  pointer: string,
  label: string,
  required: boolean,
): TemplateResult {
  const id = fieldId(pointer);
  const items = getAt(ctx.state.data, pointer);
  const values = Array.isArray(items) ? items : [];
  const itemSchema = node.items ? resolve(node.items, ctx.state.schema) : ({} as Node);
  const itemLabel = itemSchema.title ?? singular(label);
  const message = messageFor(ctx, pointer);
  const objects = kindOf(itemSchema) === 'object';
  const canAdd = node.maxItems === undefined || values.length < node.maxItems;
  const add = () => {
    ctx.on.set(
      childPointer(pointer, values.length),
      objects ? {} : kindOf(itemSchema) === 'boolean' ? false : '',
    );
    ctx.on.touch(pointer);
  };
  const remove = (i: number) => {
    ctx.on.set(childPointer(pointer, i), undefined);
    ctx.on.touch(pointer);
  };
  return html`<fieldset class="iform-group" id="${id}" ?data-invalid=${!!message}>
    <legend class="iform-legend">${label}${requiredMark(required)}</legend>
    ${help(node, id)}
    <div class="iform-list">
      ${values.length === 0 ? html`<p class="iform-list-empty">None yet.</p>` : nothing}
      ${values.map((_, i) => {
        const itemPointer = childPointer(pointer, i);
        const name = `${itemLabel.charAt(0).toUpperCase()}${itemLabel.slice(1)} ${i + 1}`;
        return objects
          ? html`<div class="iform-item">
              <div class="iform-item-head">
                <span class="iform-item-title">${name}</span>
                <button
                  type="button"
                  class="ep-btn ep-btn-ghost ep-btn-sm"
                  aria-label="Remove ${name}"
                  @click=${() => remove(i)}
                >
                  Remove
                </button>
              </div>
              <div class="iform-group-body">${properties(ctx, itemSchema, itemPointer)}</div>
            </div>`
          : html`<div class="iform-item-row">
              <div class="iform-item-row-field">
                ${field(ctx, itemSchema, itemPointer, name, true)}
              </div>
              <button
                type="button"
                class="ep-btn ep-btn-ghost ep-btn-sm"
                aria-label="Remove ${name}"
                @click=${() => remove(i)}
              >
                Remove
              </button>
            </div>`;
      })}
    </div>
    ${canAdd ? html`<button type="button" class="ep-btn ep-btn-outline ep-btn-sm iform-add" id="${id}-add" @click=${add}>+ Add ${values.length === 0 ? '' : 'another '}${itemLabel}</button>` : nothing}
    ${error(message, id)}
  </fieldset>`;
}
